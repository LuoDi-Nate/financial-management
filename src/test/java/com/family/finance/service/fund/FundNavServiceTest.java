package com.family.finance.service.fund;

import com.family.finance.domain.stock.HoldingShareEvent;
import com.family.finance.domain.stock.NavMode;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FundNavSnapshotMapper;
import com.family.finance.repository.HoldingShareEventMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.penetration.EastMoneyFundClient;
import com.family.finance.service.stock.AccountValuationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v1.30 · 净值行同步(护栏 v130-MMF-NEVER-FETCHED / v130-MMF-ACCRUE-ONCE / v130-NAV-EDITED-ELSEWHERE)。
 * 只断言「持仓行怎么写、事件记不记」—— 余额由估值服务按 MANUAL 支重算,不在这里。
 */
class FundNavServiceTest {

    private static final long FAM = 1L;
    private EastMoneyFundClient client;
    private FundNavSnapshotMapper snaps;
    private StockHoldingMapper holdings;
    private HoldingShareEventMapper shares;
    private FundNavService svc;

    @BeforeEach
    void setUp() {
        client = mock(EastMoneyFundClient.class);
        snaps = mock(FundNavSnapshotMapper.class);
        holdings = mock(StockHoldingMapper.class);
        shares = mock(HoldingShareEventMapper.class);
        PeriodMapper periods = mock(PeriodMapper.class);
        when(periods.findBalancePeriod(FAM)).thenReturn(Optional.empty());
        svc = new FundNavService(client, snaps, holdings, shares, periods, mock(FamilyMapper.class),
                mock(AccountValuationService.class), null);
    }

    private static StockHolding fund(long id, String code, String nav, LocalDate navDate) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 6, 5);
        return StockHolding.builder().id(id).accountId(10L).displayName("基金" + code).valuationMode(ValuationMode.MANUAL)
                .shares(new BigDecimal("1000")).manualValue(new BigDecimal(nav)).fundCode(code)
                .navMode(NavMode.FUND.name()).navDate(navDate).manualValueAt(t).navCheckedAt(t).build();
    }

    private static StockHolding mmf(long id, String amount, LocalDate accruedThrough) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 6, 5);
        return StockHolding.builder().id(id).accountId(10L).displayName("余额宝").valuationMode(ValuationMode.MANUAL)
                .shares(new BigDecimal(amount)).manualValue(BigDecimal.ONE).fundCode("000198")
                .navMode(NavMode.MMF.name()).navDate(accruedThrough).manualValueAt(t).navCheckedAt(t).build();
    }

    @Test
    void 普通基金_有更新的净值就写进单价() {
        StockHolding h = fund(1, "002943", "4.7700", LocalDate.of(2026, 9, 29));
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(h));
        when(client.latestNav(FAM, "002943")).thenReturn(new EastMoneyFundClient.NavFetch(true, LocalDate.of(2026, 9, 30),
                new BigDecimal("4.78"), "eastmoney-lsjz", null));
        when(snaps.findLatestNav("002943")).thenReturn(new FundNavSnapshotMapper.Row("002943", LocalDate.of(2026, 9, 30),
                new BigDecimal("4.78"), null, null, "eastmoney-lsjz", null));

        var r = svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        assertThat(r.navUpdated()).isEqualTo(1);
        assertThat(r.failed()).isEmpty();
        verify(snaps).upsert(argThat(row -> row.fundCode().equals("002943") && row.unitNav().compareTo(new BigDecimal("4.78")) == 0));
        verify(holdings).writeNav(eq(FAM), eq(1L), eq(new BigDecimal("1000")), eq(new BigDecimal("4.78")), eq("FUND"),
                eq(LocalDate.of(2026, 9, 30)), isNull(), isNull(), any());
        verifyNoInteractions(shares);   // 普通基金净值变化不是份额变化
    }

    @Test
    void 普通基金_这次没拉到_单价不动_记原因并点名() {
        StockHolding h = fund(1, "002943", "4.7800", LocalDate.of(2026, 9, 30));
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(h));
        when(client.latestNav(FAM, "002943")).thenReturn(new EastMoneyFundClient.NavFetch(false, null, null, null, "数据源查不到这只基金"));
        when(snaps.findLatestNav("002943")).thenReturn(null);

        var r = svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        assertThat(r.failed()).singleElement().asString().contains("基金002943").contains("查不到");
        verify(holdings, never()).writeNav(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(holdings).markNavError(eq(FAM), eq(1L), contains("查不到"), any());
    }

    @Test
    void 货币基金不进取净值的清单() {
        StockHolding m = mmf(2, "12000.00", LocalDate.now(FundNavService.CN));   // 今天已结过 → 什么都不做
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(m));
        when(holdings.findById(FAM, 2L)).thenReturn(Optional.of(m));

        svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        verify(client, never()).latestNav(anyLong(), anyString());
    }

    @Test
    void 货币基金_逐日结转_一次只记一条持仓数量变动() {
        LocalDate today = LocalDate.now(FundNavService.CN);
        LocalDate from = today.minusDays(3);
        StockHolding m = mmf(2, "12000.00", from);
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(m));
        when(holdings.findById(FAM, 2L)).thenReturn(Optional.of(m));
        when(holdings.lockById(FAM, 2L)).thenReturn(Optional.of(m));
        when(client.mmfIncome(eq(FAM), eq("000198"), eq(from), eq(today)))
                .thenReturn(new EastMoneyFundClient.IncomeFetch(true, List.of(), null));
        List<FundNavSnapshotMapper.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(new FundNavSnapshotMapper.Row("000198", from.plusDays(i), null, new BigDecimal("0.2253"), null, "eastmoney-lsjz", null));
        }
        when(snaps.findIncome("000198", from, today)).thenReturn(rows);

        var r = svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        assertThat(r.mmfAccrued()).isEqualTo(1);
        verify(holdings).writeNav(eq(FAM), eq(2L), eq(new BigDecimal("12000.81")), eq(BigDecimal.ONE), eq("MMF"),
                eq(from.plusDays(3)), isNull(), isNull(), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares, times(1)).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("MMF_ACCRUAL");
        assertThat(ev.getValue().getSharesDelta()).isEqualByComparingTo("0.81");
        assertThat(ev.getValue().getDateFrom()).isEqualTo(from.plusDays(1));
        assertThat(ev.getValue().getDateTo()).isEqualTo(from.plusDays(3));
        assertThat(ev.getValue().getMemberId()).as("系统自动").isNull();
    }

    @Test
    void 货币基金_行锁里读到已经结过_什么都不结() {
        LocalDate today = LocalDate.now(FundNavService.CN);
        StockHolding before = mmf(2, "12000.00", today.minusDays(2));
        StockHolding alreadyDone = mmf(2, "12000.54", today);   // 另一个请求刚结完
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(before));
        when(holdings.findById(FAM, 2L)).thenReturn(Optional.of(before));
        when(holdings.lockById(FAM, 2L)).thenReturn(Optional.of(alreadyDone));
        when(client.mmfIncome(anyLong(), anyString(), any(), any())).thenReturn(new EastMoneyFundClient.IncomeFetch(true, List.of(), null));
        when(snaps.findIncome(eq("000198"), eq(today), eq(today))).thenReturn(List.of());

        var r = svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        assertThat(r.mmfAccrued()).isZero();
        verify(holdings, never()).writeNav(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(shares);
    }

    @Test
    void 别处改过的行_不覆盖_标出来() {
        StockHolding h = fund(1, "002943", "4.7000", LocalDate.of(2026, 9, 29));
        h.setManualValueAt(h.getNavCheckedAt().plusMinutes(5));   // 我们写完之后,别人又写过
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(h));
        when(client.latestNav(anyLong(), anyString())).thenReturn(new EastMoneyFundClient.NavFetch(true, LocalDate.of(2026, 9, 30),
                new BigDecimal("4.78"), "eastmoney-lsjz", null));

        var r = svc.refresh(FAM, FundNavService.BUTTON_THROTTLE);

        verify(holdings, never()).writeNav(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(holdings).markNavError(eq(FAM), eq(1L), eq(FundNavService.EDITED_ELSEWHERE), any());
        assertThat(r.failed()).singleElement().asString().contains("暂停自动更新");
    }

    @Test
    void 节流_刚拉过就不再去拉() {
        StockHolding h = fund(1, "002943", "4.7800", LocalDate.of(2026, 9, 30));
        when(holdings.findActiveNavRowsByFamily(FAM)).thenReturn(List.of(h));
        when(snaps.lastFetchedAt("002943")).thenReturn(LocalDateTime.now().minusMinutes(1));
        when(snaps.findLatestNav("002943")).thenReturn(new FundNavSnapshotMapper.Row("002943", LocalDate.of(2026, 9, 30),
                new BigDecimal("4.78"), null, null, "eastmoney-lsjz", null));

        svc.refresh(FAM, FundNavService.CRON_THROTTLE);

        verify(client, never()).latestNav(anyLong(), anyString());
    }
}
