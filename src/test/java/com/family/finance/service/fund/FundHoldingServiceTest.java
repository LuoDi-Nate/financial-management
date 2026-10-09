package com.family.finance.service.fund;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.HoldingShareEvent;
import com.family.finance.domain.stock.NavMode;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FundNavSnapshotMapper;
import com.family.finance.repository.HoldingShareEventMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.penetration.EastMoneyFundClient;
import com.family.finance.service.penetration.EastMoneyFundClient.FundInfo;
import com.family.finance.service.penetration.FundPenetrationService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockHoldingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v1.30 · 用户对基金做的动作(护栏 v130-CASH-LINK / v130-IMPORT-NAV-SAFE)。
 */
class FundHoldingServiceTest {

    private static final long FAM = 1L, ACC = 10L;
    private FundCatalog catalog;
    private FundNavSnapshotMapper snaps;
    private StockHoldingMapper holdings;
    private HoldingShareEventMapper shares;
    private StockHoldingService stock;
    private AccountValuationService valuation;
    private com.family.finance.service.ledger.PrincipalAdjustmentService principal;
    private FundHoldingService svc;

    @BeforeEach
    void setUp() {
        catalog = mock(FundCatalog.class);
        snaps = mock(FundNavSnapshotMapper.class);
        holdings = mock(StockHoldingMapper.class);
        shares = mock(HoldingShareEventMapper.class);
        stock = mock(StockHoldingService.class);
        valuation = mock(AccountValuationService.class);
        AccountMapper accounts = mock(AccountMapper.class);
        when(accounts.findById(FAM, ACC)).thenReturn(Optional.of(
                Account.builder().id(ACC).familyId(FAM).type(AccountType.FUND).currency("CNY").build()));
        PeriodMapper periods = mock(PeriodMapper.class);
        when(periods.findBalancePeriod(FAM)).thenReturn(Optional.of(
                com.family.finance.domain.period.Period.builder().id(99L).familyId(FAM).build()));
        principal = mock(com.family.finance.service.ledger.PrincipalAdjustmentService.class);
        when(principal.checkBalancePeriod(FAM, ACC)).thenReturn(
                new com.family.finance.service.ledger.PrincipalAdjustmentService.Eligibility(null, true, null));
        svc = new FundHoldingService(catalog, mock(EastMoneyFundClient.class), snaps, holdings, shares, accounts, periods,
                stock, valuation, mock(FundPenetrationService.class), principal,
                mock(org.springframework.context.ApplicationEventPublisher.class), null);
        when(catalog.find(FAM, "002943")).thenReturn(FundCatalog.classify(
                new FundInfo("002943", "GFDYZHH", "广发多因子混合", "混合型-灵活", "G")));
        when(snaps.lastFetchedAt("002943")).thenReturn(LocalDateTime.now());
        when(snaps.findLatestNav("002943")).thenReturn(new FundNavSnapshotMapper.Row("002943", LocalDate.of(2026, 9, 30),
                new BigDecimal("4.78"), null, null, "eastmoney-lsjz", null));
    }

    private static StockHolding navRow(String shares) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 6, 5);
        return StockHolding.builder().id(7L).accountId(ACC).displayName("广发多因子混合").valuationMode(ValuationMode.MANUAL)
                .shares(new BigDecimal(shares)).manualValue(new BigDecimal("4.78")).fundCode("002943")
                .navMode(NavMode.FUND.name()).navDate(LocalDate.of(2026, 9, 30)).manualValueAt(t).navCheckedAt(t).build();
    }

    @Test
    void 按份额添加_单价是净值_记添加事件_写回估值() {
        svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, new BigDecimal("1000"), false);

        ArgumentCaptor<StockHolding> h = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdings).insertOwned(eq(FAM), h.capture());
        assertThat(h.getValue().getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(h.getValue().getNavMode()).isEqualTo("FUND");
        assertThat(h.getValue().getManualValue()).isEqualByComparingTo("4.78");
        assertThat(h.getValue().getShares()).isEqualByComparingTo("1000");
        assertThat(h.getValue().getFundCode()).isEqualTo("002943");
        assertThat(h.getValue().getSharesEstimatedOn()).isNull();
        assertThat(h.getValue().getManualValueAt()).as("与 nav_checked_at 同一时刻").isEqualTo(h.getValue().getNavCheckedAt());
        verify(stock, never()).adjustAccountCash(anyLong(), anyLong(), any(), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("CREATE");
        verify(valuation).refreshOneAccount(eq(FAM), eq(ACC), eq(AccountValuationService.TriggerKind.HOLDING_CHANGE), eq(3L), isNull());
    }

    @Test
    void 按市值添加_反推份额4位_标明估算() {
        svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.VALUE, new BigDecimal("5000"), false);
        ArgumentCaptor<StockHolding> h = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdings).insertOwned(eq(FAM), h.capture());
        assertThat(h.getValue().getShares()).isEqualByComparingTo("1046.0251");   // 5000 / 4.78
        assertThat(h.getValue().getSharesEstimatedOn()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void 添加时勾现金联动_现金行扣同样的钱_记申购() {
        svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, new BigDecimal("1000"), true);
        verify(stock).adjustAccountCash(FAM, ACC, "CNY", new BigDecimal("-4780.00"));
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("CASH_BUY");
    }

    @Test
    void 改份额勾现金联动_现金行减_余额不变_记申购() {
        StockHolding h = navRow("1046.03");
        when(stock.require(FAM, 7L)).thenReturn(h);

        svc.editShares(FAM, 3L, 7L, new BigDecimal("1250.00"), true);

        verify(holdings).writeNav(eq(FAM), eq(7L), eq(new BigDecimal("1250.00")), eq(new BigDecimal("4.78")), eq("FUND"),
                eq(LocalDate.of(2026, 9, 30)), isNull(), isNull(), any());
        verify(stock).adjustAccountCash(FAM, ACC, "CNY", new BigDecimal("-974.98"));   // 203.97 × 4.78
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("CASH_BUY");
        assertThat(ev.getValue().getSharesDelta()).isEqualByComparingTo("203.97");
    }

    @Test
    void 减份额勾现金联动_钱回到现金行_记赎回() {
        when(stock.require(FAM, 7L)).thenReturn(navRow("1000"));
        svc.editShares(FAM, 3L, 7L, new BigDecimal("900"), true);
        verify(stock).adjustAccountCash(FAM, ACC, "CNY", new BigDecimal("478.00"));
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("CASH_REDEEM");
    }

    @Test
    void 改份额不勾_算估值变动_不碰现金行() {
        when(stock.require(FAM, 7L)).thenReturn(navRow("1000"));
        svc.editShares(FAM, 3L, 7L, new BigDecimal("1100"), false);
        verify(stock, never()).adjustAccountCash(anyLong(), anyLong(), any(), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("MANUAL_EDIT");
    }

    @Test
    void 货币基金改金额_已结转到改成前一天_记手动校正() {
        StockHolding m = navRow("12001.89");
        m.setNavMode(NavMode.MMF.name());
        m.setManualValue(BigDecimal.ONE);
        when(stock.require(FAM, 7L)).thenReturn(m);

        svc.editShares(FAM, 3L, 7L, new BigDecimal("12000.00"), false);

        verify(holdings).writeNav(eq(FAM), eq(7L), eq(new BigDecimal("12000.00")), eq(BigDecimal.ONE), eq("MMF"),
                eq(LocalDate.now(FundNavService.CN).minusDays(1)), isNull(), isNull(), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("MANUAL_CORRECTION");
    }

    @Test
    void 截图导入命中净值行_改份额不碰单价() {
        StockHolding h = navRow("1000");
        svc.applyImportedValue(FAM, 3L, h, new BigDecimal("5000"));
        verify(holdings).writeNav(eq(FAM), eq(7L), eq(new BigDecimal("1046.0251")), eq(new BigDecimal("4.78")), eq("FUND"),
                any(), isNull(), any(), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("IMPORT");
    }

    @Test
    void 不合格的账户_不能添加() {
        AccountMapper accounts = mock(AccountMapper.class);
        when(accounts.findById(FAM, ACC)).thenReturn(Optional.of(
                Account.builder().id(ACC).familyId(FAM).type(AccountType.CRYPTO).currency("CNY").build()));
        FundHoldingService s = new FundHoldingService(catalog, mock(EastMoneyFundClient.class), snaps, holdings, shares,
                accounts, mock(PeriodMapper.class), stock, valuation, mock(FundPenetrationService.class),
                mock(com.family.finance.service.ledger.PrincipalAdjustmentService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class), null);
        assertThatThrownBy(() -> s.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, BigDecimal.TEN, false))
                .hasMessageContaining("不能添加场外基金");
        verify(holdings, never()).insertOwned(anyLong(), any());
    }

    // ---------- v1.30 · 持仓成本价(FR-971)/ 补录本金(FR-973) ----------

    @Test
    void 添加时填成本价_存下来_货基不存() {
        svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, new BigDecimal("1000"),
                FundHoldingService.MoneyFrom.NEW, new BigDecimal("4"));
        ArgumentCaptor<StockHolding> h = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdings).insertOwned(eq(FAM), h.capture());
        assertThat(h.getValue().getCostBasis()).isEqualByComparingTo("4");
        verify(principal, never()).record(anyLong(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void 添加时选以前就有_按市值记一笔补录本金_挂在这只持仓上() {
        svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, new BigDecimal("1000"),
                FundHoldingService.MoneyFrom.PRIOR, null);
        // 1000 份 × 4.78 = 4780.00 —— 与估值写回的余额同一个数
        verify(principal).record(eq(FAM), eq(3L), eq(ACC), eq(99L), eq(new BigDecimal("4780.00")), any(), any());
        verify(stock, never()).adjustAccountCash(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 账户第一期选以前就有_拒绝_什么都不写() {
        when(principal.checkBalancePeriod(FAM, ACC)).thenReturn(
                new com.family.finance.service.ledger.PrincipalAdjustmentService.Eligibility(null, false, "这是这个账户的第一期"));
        assertThatThrownBy(() -> svc.create(FAM, 3L, ACC, "002943", FundHoldingService.By.SHARES, BigDecimal.TEN,
                FundHoldingService.MoneyFrom.PRIOR, null)).hasMessageContaining("第一期");
        verify(holdings, never()).insertOwned(anyLong(), any());
    }

    @Test
    void 改份额选以前就有_只记增加的那部分_份额减少不允许() {
        StockHolding h = navRow("1000");
        when(stock.require(FAM, 7L)).thenReturn(h);
        svc.editShares(FAM, 3L, 7L, new BigDecimal("1100"), FundHoldingService.MoneyFrom.PRIOR, null);
        verify(principal).record(eq(FAM), eq(3L), eq(ACC), eq(99L), eq(new BigDecimal("478.00")), eq(7L), any());
        ArgumentCaptor<HoldingShareEvent> ev = ArgumentCaptor.forClass(HoldingShareEvent.class);
        verify(shares).insertOwned(eq(FAM), ev.capture());
        assertThat(ev.getValue().getReason()).isEqualTo("PRIOR");
        assertThatThrownBy(() -> svc.editShares(FAM, 3L, 7L, new BigDecimal("900"), FundHoldingService.MoneyFrom.PRIOR, null))
                .hasMessageContaining("份额减少");
    }

    @Test
    void 成本价_现金申购没改它时按本次净值加权平均_赎回不变_用户改了用用户的() {
        BigDecimal cur = new BigDecimal("4.0000");
        // 1000 份 @4 + 申购 500 份 @4.78 → (4000 + 2390) / 1500 = 4.26
        assertThat(FundHoldingService.nextCost(cur, cur, new BigDecimal("1000"), new BigDecimal("500"),
                new BigDecimal("4.78"), true)).isEqualByComparingTo("4.2600");
        assertThat(FundHoldingService.nextCost(cur, cur, new BigDecimal("1000"), new BigDecimal("-500"),
                new BigDecimal("4.78"), true)).as("赎回不改单位成本").isEqualByComparingTo("4");
        assertThat(FundHoldingService.nextCost(cur, cur, new BigDecimal("1000"), new BigDecimal("500"),
                new BigDecimal("4.78"), false)).as("不联动的改份额不动成本").isEqualByComparingTo("4");
        assertThat(FundHoldingService.nextCost(cur, new BigDecimal("3.9"), new BigDecimal("1000"), new BigDecimal("500"),
                new BigDecimal("4.78"), true)).as("用户改了就用用户的").isEqualByComparingTo("3.9");
        assertThat(FundHoldingService.nextCost(null, null, new BigDecimal("1000"), new BigDecimal("500"),
                new BigDecimal("4.78"), true)).as("原来没有成本价的,不凭空算一个").isNull();
        assertThat(FundHoldingService.nextCost(cur, null, new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("4.78"), false)).as("清空 = 去掉成本价").isNull();
    }

    @Test
    void 只改成本价_不动份额_不碰估值_不记事件() {
        StockHolding h = navRow("1000");
        h.setCostBasis(new BigDecimal("4"));
        when(stock.require(FAM, 7L)).thenReturn(h);
        svc.editShares(FAM, 3L, 7L, new BigDecimal("1000"), FundHoldingService.MoneyFrom.NEW, new BigDecimal("3.5"));
        verify(holdings).updateCostBasis(FAM, 7L, new BigDecimal("3.5000"));
        verify(holdings, never()).writeNav(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(shares, never()).insertOwned(anyLong(), any());
    }
}
