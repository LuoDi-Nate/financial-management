package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.NavMode;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.FxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v1.30 · 净值行的单价 / 份额只能经 FundHoldingService 改(护栏 v130-NAV-NOT-HAND-EDITED / v130-STOCK-INCOME-NO-FUND),
 * 以及估值来源把净值行算「基金」。
 */
class NavRowGuardTest {

    private StockHoldingMapper holdingMapper;
    private StockHoldingService svc;

    private static StockHolding navRow() {
        return StockHolding.builder().id(7L).accountId(10L).displayName("广发多因子混合").valuationMode(ValuationMode.MANUAL)
                .shares(new BigDecimal("1000")).manualValue(new BigDecimal("4.78")).fundCode("002943")
                .navMode(NavMode.FUND.name()).build();
    }

    @BeforeEach
    void setUp() {
        holdingMapper = mock(StockHoldingMapper.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        when(accountMapper.findById(anyLong(), eq(10L))).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.FUND).currency("CNY").build()));
        when(holdingMapper.findById(1L, 7L)).thenReturn(Optional.of(navRow()));
        svc = new StockHoldingService(holdingMapper, accountMapper, mock(FxService.class), mock(PeriodMapper.class),
                mock(StockPriceFetcher.class), mock(com.family.finance.repository.CashFlowMapper.class));
    }

    @Test
    void 手改单价的接口_拒绝净值行() {
        assertThatThrownBy(() -> svc.updateManual(1L, 7L, new BigDecimal("1000"), new BigDecimal("5.00")))
                .hasMessageContaining("只能改份额");
        verify(holdingMapper, never()).update(anyLong(), any());
    }

    @Test
    void 股票收入加股数_拒绝净值行() {
        assertThatThrownBy(() -> svc.addShares(1L, 7L, new BigDecimal("10")))
                .hasMessageContaining("改份额");
        verify(holdingMapper, never()).update(anyLong(), any());
    }

    @Test
    void 估值来源_净值行多就记基金净值() {
        StockHolding stockRow = StockHolding.builder().valuationMode(ValuationMode.AUTO).market(Market.CN).ticker("600519").build();
        assertThat(AccountValuationService.inferSource(null, AccountValuationService.TriggerKind.CRON, null,
                List.of(navRow(), navRow(), stockRow))).isEqualTo(LedgerSource.SYNC_FUND_NAV);
        assertThat(AccountValuationService.inferSource(null, AccountValuationService.TriggerKind.CRON, null,
                List.of(navRow(), stockRow, stockRow))).isEqualTo(LedgerSource.SYNC_STOCK_API);
        assertThat(LedgerSource.SYNC_FUND_NAV.isAutomatic()).isTrue();
        assertThat(LedgerSource.parse("SYNC_FUND_NAV")).isEqualTo(LedgerSource.SYNC_FUND_NAV);
    }
}
