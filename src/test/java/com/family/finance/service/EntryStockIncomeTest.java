package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowCategory;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.*;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.stock.StockHoldingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v0.12 · 股票收入 = 持仓版 · 服务层防回归:
 * 「+股数」记 cash_flow(is_adjustment=0 + ref_holding_id/ref_shares)+ 持仓股数增;删除按 ref 冲回股数。
 */
class EntryStockIncomeTest {

    private AccountMapper accountMapper;
    private PeriodMapper periodMapper;
    private SnapshotMapper snapshotMapper;
    private SnapshotTodoMapper snapshotTodoMapper;
    private CashFlowMapper cashFlowMapper;
    private CashFlowCategoryMapper categoryMapper;
    private StockHoldingService stockHoldingService;
    private EntryService svc;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        periodMapper = mock(PeriodMapper.class);
        snapshotMapper = mock(SnapshotMapper.class);
        snapshotTodoMapper = mock(SnapshotTodoMapper.class);
        cashFlowMapper = mock(CashFlowMapper.class);
        categoryMapper = mock(CashFlowCategoryMapper.class);
        stockHoldingService = mock(StockHoldingService.class);
        svc = new EntryService(
                accountMapper, mock(com.family.finance.service.member.MemberDirectory.class), periodMapper, snapshotMapper, snapshotTodoMapper,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                cashFlowMapper, mock(TransferMapper.class), mock(AuditLogService.class),
                mock(FamilyConfigService.class), mock(StockValuationEventMapper.class),
                categoryMapper, stockHoldingService,
                // v1.18.5 · 手填余额落托管账户时要拿「持仓+现金」当前值算差额
                mock(com.family.finance.service.stock.AccountValuationService.class),
                // v1.21 · 支出录入要校验消费分类归属(isUsable)
                mock(com.family.finance.service.expense.ExpenseCategoryService.class),
                // v1.30 · 补录本金算进未解释差额的已知流入
                mock(com.family.finance.repository.PrincipalAdjustmentMapper.class));

        Period p = Period.builder().id(100L).familyId(1L).status(PeriodStatus.OPEN)
                .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31)).build();
        when(periodMapper.findById(anyLong(), eq(100L))).thenReturn(Optional.of(p));
        when(accountMapper.findById(anyLong(), eq(10L))).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.STOCK).currency("CNY")
                        .displayName("富途").build()));
        when(categoryMapper.findByCode("stock_salary")).thenReturn(Optional.of(
                CashFlowCategory.builder().code("stock_salary").displayName("薪资-股票")
                        .kind("INCOME").accountType("STOCK").build()));
    }

    @Test
    void existingHolding_addShares_recordsExternalInflowWithRef() {
        StockHolding h = StockHolding.builder().id(50L).accountId(10L)
                .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("2000"))
                .manualValue(new BigDecimal("240")).displayName("字节 RSU").build();
        when(stockHoldingService.require(1L, 50L)).thenReturn(h);
        when(stockHoldingService.currentUnitValueInAccountCcy(1L, h)).thenReturn(new BigDecimal("240"));

        svc.recordStockIncomeExistingHolding(1L, 7L, 100L, 10L, 50L,
                new BigDecimal("250"), "stock_salary", "Q2 归属");

        verify(stockHoldingService).addShares(1L, 50L, new BigDecimal("250.0000"));
        ArgumentCaptor<CashFlow> cap = ArgumentCaptor.forClass(CashFlow.class);
        verify(cashFlowMapper).insertOwned(eq(1L), cap.capture());
        CashFlow cf = cap.getValue();
        assertThat(cf.getKind()).isEqualTo(CashFlowKind.INCOME);
        assertThat(cf.isAdjustment()).isFalse();                       // 真实外部收入(不剔出家庭收入,进净流入被 PnL 剔)
        assertThat(cf.getAmount()).isEqualByComparingTo("60000");      // 250 × 240
        assertThat(cf.getRefHoldingId()).isEqualTo(50L);
        assertThat(cf.getRefShares()).isEqualByComparingTo("250");
        assertThat(cf.getCategoryCode()).isEqualTo("stock_salary");
    }

    /**
     * issue#3 回归(精度):股票收入「新建未上市持仓」时,单股估值 15.678 必须原样传给 createManual,
     * 不能像金额那样被 positiveMoney 压成 15.68。
     */
    @Test
    void newManual_income_preservesHighPrecisionUnitValue() {
        when(stockHoldingService.createManual(eq(1L), eq(10L), anyString(), any(), any()))
                .thenReturn(StockHolding.builder().id(60L).accountId(10L)
                        .valuationMode(ValuationMode.MANUAL).shares(new BigDecimal("1"))
                        .manualValue(new BigDecimal("15.678")).displayName("字节RSU").build());

        svc.recordStockIncomeNewManual(1L, 7L, 100L, 10L, "字节RSU",
                new BigDecimal("1"), new BigDecimal("15.678"), "stock_salary", null);

        ArgumentCaptor<BigDecimal> unitCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockHoldingService).createManual(eq(1L), eq(10L), eq("字节RSU"), any(), unitCap.capture());
        assertThat(unitCap.getValue()).isEqualByComparingTo("15.678");   // 非 15.68
    }

    @Test
    void noPrice_rejectsWithoutWriting() {
        StockHolding h = StockHolding.builder().id(51L).accountId(10L)
                .valuationMode(ValuationMode.AUTO).ticker("ZZZZ").currency("USD")
                .shares(new BigDecimal("10")).displayName("ZZZZ").build();
        when(stockHoldingService.require(1L, 51L)).thenReturn(h);
        when(stockHoldingService.currentUnitValueInAccountCcy(1L, h)).thenReturn(null);

        try {
            svc.recordStockIncomeExistingHolding(1L, 7L, 100L, 10L, 51L,
                    new BigDecimal("10"), "stock_salary", null);
        } catch (IllegalArgumentException expected) { /* 无价拒绝 */ }
        verify(cashFlowMapper, never()).insertOwned(anyLong(), any());
        verify(stockHoldingService, never()).addShares(anyLong(), anyLong(), any());
    }

    @Test
    void delete_shareIncome_reversesShares() {
        CashFlow cf = CashFlow.builder().id(900L).periodId(100L).accountId(10L)
                .kind(CashFlowKind.INCOME).categoryCode("stock_salary").amount(new BigDecimal("60000"))
                .refHoldingId(50L).refShares(new BigDecimal("250")).build();
        when(cashFlowMapper.findById(1L, 900L)).thenReturn(Optional.of(cf));

        svc.softDeleteCashFlow(1L, 7L, 900L);

        verify(stockHoldingService).addShares(1L, 50L, new BigDecimal("-250"));
        verify(cashFlowMapper).softDelete(1L, 900L);
        // 未走现金行冲回(那是 +现金 收入的路径)
        verify(stockHoldingService, never()).adjustAccountCash(anyLong(), anyLong(), any(), any());
    }
}
