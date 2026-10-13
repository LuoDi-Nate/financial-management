package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.fixedflow.FixedFlowOccurrence;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.InsurancePolicyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.EntryService;
import com.family.finance.service.FxService;
import com.family.finance.service.PeriodOpener;
import com.family.finance.service.expense.ExpenseLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.32 · 确认 / 撤销只经 EntryService 的既有方法写(tech-design 选型二),而且写对东西:
 * 信用卡账单只有划转(选型四 · 失败模式 ①)、按差值的贷款余额与「接受」同一个数(FR-1108 · 失败模式 ③)、
 * 有人改过的贷款余额不覆盖(选型三)、总数模式不单独记支出(FR-1160)、重复确认挡在库这一层。
 */
class FixedFlowConfirmServiceTest {

    static final long FAM = 1L, ME = 7L, PID = 100L;
    static final long CASH = 11L, LOAN = 21L, CARD = 31L, INS = 41L;

    FixedFlowMapper mapper = mock(FixedFlowMapper.class);
    AccountMapper accounts = mock(AccountMapper.class);
    PeriodMapper periods = mock(PeriodMapper.class);
    SnapshotMapper snapshots = mock(SnapshotMapper.class);
    EntryService entry = mock(EntryService.class);
    ExpenseLedgerService ledger = mock(ExpenseLedgerService.class);
    FixedFlowConfirmService svc;

    final Period period = Period.builder().id(PID).familyId(FAM).status(PeriodStatus.OPEN)
            .periodStart(LocalDate.of(2026, 10, 1)).periodEnd(LocalDate.of(2026, 10, 31)).build();

    @BeforeEach
    void setUp() {
        FixedFlowSummaryService summary = new FixedFlowSummaryService(mapper, accounts, mock(com.family.finance.service.member.MemberDirectory.class), periods,
                snapshots, mock(InsurancePolicyMapper.class), ledger, mock(FxService.class));
        svc = new FixedFlowConfirmService(mapper, accounts, periods, snapshots, entry, summary, ledger,
                mock(AuditLogService.class), mock(FixedFlowService.class));
        when(periods.findById(FAM, PID)).thenReturn(Optional.of(period));
        when(ledger.modeOf(FAM)).thenReturn(ExpenseEntryMode.ITEMIZED);
        acc(CASH, "工资卡", AccountType.CASH, null);
        acc(LOAN, "房贷", AccountType.LOAN, new BigDecimal("3.93"));
        acc(CARD, "招行信用卡", AccountType.LOAN, null);
        acc(INS, "年金险", AccountType.INSURANCE, null);
        // 接口的 default 方法在 mock 上也被替身了 —— 直接替 insertOccurrenceOwned(业务代码调的就是它)
        doAnswer(inv -> { ((FixedFlowOccurrence) inv.getArgument(1)).setId(900L); return null; })
                .when(mapper).insertOccurrenceOwned(eq(FAM), any());
        when(mapper.recentAmountsBefore(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        // EntryService 写进去的流水 / 划转 id 由回执带回(真实的写入口在插入后填)
        doAnswer(inv -> { ((EntryService.WriteReceipt) inv.getArgument(10)).cashFlowIds().add(501L + inv.<BigDecimal>getArgument(5).intValue() % 7); return null; })
                .when(entry).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
        doAnswer(inv -> { ((EntryService.WriteReceipt) inv.getArgument(9)).transferIds().add(701L); return null; })
                .when(entry).addTransfer(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), anyBoolean(), any(EntryService.WriteReceipt.class));
        doAnswer(inv -> { ((EntryService.WriteReceipt) inv.getArgument(7)).cashFlowIds().add(601L); return null; })
                .when(entry).recordIncome(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(EntryService.WriteReceipt.class));
    }

    private void acc(long id, String name, AccountType type, BigDecimal rate) {
        when(accounts.findById(FAM, id)).thenReturn(Optional.of(Account.builder().id(id).familyId(FAM).displayName(name)
                .type(type).currency("CNY").annualRatePct(rate).build()));
    }

    private FixedFlow flow(long id, String kind, String repayKind, String mode, String method, String amount, long account, Long target) {
        FixedFlow f = FixedFlow.builder().id(id).familyId(FAM).name("f" + id).kind(kind).repayKind(repayKind)
                .amountMode(mode).autoMethod(method).amount(amount == null ? null : new BigDecimal(amount))
                .accountId(account).targetAccountId(target).categoryCode(FixedFlow.EXPENSE.equals(kind) ? "consumption" : "salary")
                .cycle("MONTHLY").dayOfMonth(5).startMonth(LocalDate.of(2026, 1, 1)).build();
        when(mapper.findById(FAM, id)).thenReturn(Optional.of(f));
        return f;
    }

    private void loanHistory(long acct, String prev, String prevPrev, String current) {
        when(snapshots.findLatestBefore(eq(FAM), eq(acct), eq(period.getPeriodStart()), anyInt2()))
                .thenReturn(List.of(snap(prev), snap(prevPrev)));
        when(snapshots.findByPeriodAndAccount(FAM, PID, acct)).thenReturn(Optional.ofNullable(current == null ? null : snap(current)));
    }

    private static int anyInt2() { return org.mockito.ArgumentMatchers.anyInt(); }

    private static PeriodSnapshot snap(String b) { return PeriodSnapshot.builder().endBalance(new BigDecimal(b)).build(); }

    // ─────────────────────────────────────────────────────────────────────

    @Test
    void expense_goesThroughRecordExpense_markedFixedFlow_andIdKeptForUndo() {
        flow(1, FixedFlow.EXPENSE, null, "FIXED", null, "480", CASH, null);
        svc.confirm(FAM, ME, PID, 1, null, false);
        ArgumentCaptor<EntryService.WriteReceipt> rc = ArgumentCaptor.forClass(EntryService.WriteReceipt.class);
        verify(entry).recordExpense(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("consumption"), eq(new BigDecimal("480.00")),
                eq("f1"), isNull(), eq(true), eq(false), rc.capture());
        assertThat(rc.getValue().sourceTag()).isEqualTo(LedgerSource.FIXED_FLOW.name());
        ArgumentCaptor<FixedFlowOccurrence> occ = ArgumentCaptor.forClass(FixedFlowOccurrence.class);
        verify(mapper).fillOccurrence(eq(FAM), occ.capture());
        assertThat(occ.getValue().getCashFlowId()).isNotNull();
        assertThat(occ.getValue().getStatus()).isEqualTo(FixedFlowOccurrence.CONFIRMED);
    }

    @Test
    void creditCardBill_isOnlyATransfer_neverAnExpense() {
        flow(2, FixedFlow.REPAY, FixedFlow.REPAY_CARD, "AUTO", FixedFlow.CARD_FULL, null, CASH, CARD);
        when(snapshots.findLatestBefore(eq(FAM), eq(CARD), eq(period.getPeriodStart()), anyInt2())).thenReturn(List.of(snap("-8642.10")));
        svc.confirm(FAM, ME, PID, 2, null, false);
        verify(entry).addTransfer(eq(FAM), eq(ME), eq(PID), eq(CASH), eq(CARD), eq(new BigDecimal("8642.10")), isNull(),
                eq("f2"), eq(false), any(EntryService.WriteReceipt.class));
        verify(entry, never()).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
        verify(entry, never()).setLoanBalanceBySystem(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void premium_isOnlyATransfer() {
        flow(3, FixedFlow.PREMIUM, null, "FIXED", null, "12000", CASH, INS);
        svc.confirm(FAM, ME, PID, 3, null, false);
        verify(entry).addTransfer(eq(FAM), eq(ME), eq(PID), eq(CASH), eq(INS), eq(new BigDecimal("12000.00")), isNull(),
                eq("f3"), eq(false), any(EntryService.WriteReceipt.class));
        verify(entry, never()).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
    }

    @Test
    void loanByDifference_balanceIsExactlyWhatAcceptWouldWrite() {
        flow(4, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        loanHistory(LOAN, "-1190000.00", "-1195600.00", "-1190000.00");   // 本期还没人动过(= 上月末)
        svc.confirm(FAM, ME, PID, 4, null, false);
        BigDecimal expected = PeriodOpener.predictLoanBalance(new BigDecimal("-1190000.00"), new BigDecimal("-1195600.00"));
        verify(entry).setLoanBalanceBySystem(eq(FAM), eq(ME), eq(PID), eq(LOAN), eq(expected), anyString());
        verify(entry).recordExpense(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("loan_payment"), eq(new BigDecimal("5600.00")),
                anyString(), isNull(), eq(true), eq(false), any(EntryService.WriteReceipt.class));
        verify(entry, never()).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), eq("interest_paid"), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
        ArgumentCaptor<FixedFlowOccurrence> occ = ArgumentCaptor.forClass(FixedFlowOccurrence.class);
        verify(mapper).fillOccurrence(eq(FAM), occ.capture());
        assertThat(occ.getValue().getLoanBalanceBefore()).isEqualByComparingTo("-1190000.00");
        assertThat(occ.getValue().getLoanBalanceAfter()).isEqualByComparingTo(expected);
    }

    @Test
    void loanByRate_recordsPrincipalAndInterest() {
        flow(5, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_RATE, "6280", CASH, LOAN);
        loanHistory(LOAN, "-1186700", "-1189000", null);
        svc.confirm(FAM, ME, PID, 5, null, false);
        verify(entry).recordExpense(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("loan_payment"), eq(new BigDecimal("2393.56")),
                anyString(), isNull(), eq(true), eq(false), any(EntryService.WriteReceipt.class));
        verify(entry).recordExpense(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("interest_paid"), eq(new BigDecimal("3886.44")),
                anyString(), isNull(), eq(true), eq(false), any(EntryService.WriteReceipt.class));
        verify(entry).setLoanBalanceBySystem(eq(FAM), eq(ME), eq(PID), eq(LOAN), eq(new BigDecimal("-1184306.44")), anyString());
    }

    @Test
    void loanBalanceAlreadyEditedThisPeriod_isNotOverwritten() {
        flow(6, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        loanHistory(LOAN, "-1190000.00", "-1195600.00", "-1184000.00");   // 有人已经按账单填过
        var out = svc.confirm(FAM, ME, PID, 6, null, false);
        verify(entry, never()).setLoanBalanceBySystem(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        assertThat(out.message()).contains("已经有人填过");
        ArgumentCaptor<FixedFlowOccurrence> occ = ArgumentCaptor.forClass(FixedFlowOccurrence.class);
        verify(mapper).fillOccurrence(eq(FAM), occ.capture());
        assertThat(occ.getValue().getLoanBalanceAfter()).as("没动贷款余额 → 撤销时也不还原").isNull();
    }

    @Test
    void totalMode_expenseIsListOnly_loanOnlyMovesBalance() {
        when(ledger.modeOf(FAM)).thenReturn(ExpenseEntryMode.TOTAL);
        flow(7, FixedFlow.EXPENSE, null, "FIXED", null, "480", CASH, null);
        assertThatThrownBy(() -> svc.confirm(FAM, ME, PID, 7, null, false)).hasMessageContaining("总数");
        flow(8, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        loanHistory(LOAN, "-1190000.00", "-1195600.00", "-1190000.00");
        svc.confirm(FAM, ME, PID, 8, null, false);
        verify(entry, never()).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
        verify(entry).setLoanBalanceBySystem(eq(FAM), eq(ME), eq(PID), eq(LOAN), any(), anyString());
    }

    @Test
    void secondConfirmSamePeriod_isRefusedBeforeAnyWrite() {
        flow(9, FixedFlow.EXPENSE, null, "FIXED", null, "480", CASH, null);
        doThrow(new DuplicateKeyException("uk_ffo_flow_period")).when(mapper).insertOccurrenceOwned(eq(FAM), any());
        assertThatThrownBy(() -> svc.confirm(FAM, ME, PID, 9, null, false)).hasMessageContaining("已经确认过");
        verify(entry, never()).recordExpense(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean(), any(EntryService.WriteReceipt.class));
    }

    @Test
    void closedPeriod_refused() {
        flow(10, FixedFlow.EXPENSE, null, "FIXED", null, "480", CASH, null);
        when(periods.findById(FAM, PID)).thenReturn(Optional.of(Period.builder().id(PID).familyId(FAM).status(PeriodStatus.CLOSED)
                .periodStart(period.getPeriodStart()).periodEnd(period.getPeriodEnd()).build()));
        assertThatThrownBy(() -> svc.confirm(FAM, ME, PID, 10, null, false)).hasMessageContaining("关账");
    }

    @Test
    void editedAmount_andRemember_updatesFixedAmountOnly() {
        flow(11, FixedFlow.EXPENSE, null, "FIXED", null, "480", CASH, null);
        svc.confirm(FAM, ME, PID, 11, new BigDecimal("520"), true);
        verify(entry).recordExpense(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("consumption"), eq(new BigDecimal("520.00")),
                anyString(), isNull(), eq(true), eq(false), any(EntryService.WriteReceipt.class));
        verify(mapper).updateAmount(FAM, 11L, new BigDecimal("520.00"));
    }

    @Test
    void undo_skipsFlowsAlreadyDeletedByHand_andRestoresUntouchedLoan() {
        FixedFlow f = flow(12, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        when(mapper.findOccurrenceById(FAM, 55L)).thenReturn(Optional.of(FixedFlowOccurrence.builder().id(55L).familyId(FAM)
                .fixedFlowId(f.getId()).periodId(PID).status(FixedFlowOccurrence.CONFIRMED)
                .cashFlowId(501L).loanBalanceBefore(new BigDecimal("-1190000.00")).loanBalanceAfter(new BigDecimal("-1184400.00")).build()));
        when(mapper.countLiveCashFlow(FAM, 501L)).thenReturn(0);     // 人已经在流水列表里删过
        when(snapshots.findByPeriodAndAccount(FAM, PID, LOAN)).thenReturn(Optional.of(snap("-1184400.00")));
        svc.undo(FAM, ME, 55L);
        verify(entry, never()).softDeleteCashFlow(anyLong(), anyLong(), anyLong());
        verify(entry).setLoanBalanceBySystem(eq(FAM), eq(ME), eq(PID), eq(LOAN), eq(new BigDecimal("-1190000.00")), anyString());
        verify(mapper).deleteOccurrence(FAM, 55L);
    }

    @Test
    void undo_loanChangedLater_isLeftAlone() {
        FixedFlow f = flow(13, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        when(mapper.findOccurrenceById(FAM, 56L)).thenReturn(Optional.of(FixedFlowOccurrence.builder().id(56L).familyId(FAM)
                .fixedFlowId(f.getId()).periodId(PID).status(FixedFlowOccurrence.CONFIRMED)
                .cashFlowId(502L).loanBalanceBefore(new BigDecimal("-1190000.00")).loanBalanceAfter(new BigDecimal("-1184400.00")).build()));
        when(mapper.countLiveCashFlow(FAM, 502L)).thenReturn(1);
        when(snapshots.findByPeriodAndAccount(FAM, PID, LOAN)).thenReturn(Optional.of(snap("-1180000.00")));
        var out = svc.undo(FAM, ME, 56L);
        verify(entry).softDeleteCashFlow(FAM, ME, 502L);
        verify(entry, never()).setLoanBalanceBySystem(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        assertThat(out.message()).contains("没有还原");
    }

    @Test
    void income_goesThroughRecordIncome() {
        flow(14, FixedFlow.INCOME, null, "FIXED", null, "25000", CASH, null);
        svc.confirm(FAM, ME, PID, 14, null, false);
        verify(entry).recordIncome(eq(FAM), eq(ME), eq(PID), eq(CASH), eq("salary"), eq(new BigDecimal("25000.00")), eq("f14"),
                any(EntryService.WriteReceipt.class));
    }

    @Test
    void writeFailureMidway_propagates_soTransactionRollsBack() {
        // 还贷记了、贷款余额写失败 → 异常冒出去(@Transactional 整体回滚,一笔不留)
        flow(15, FixedFlow.REPAY, FixedFlow.REPAY_LOAN, "AUTO", FixedFlow.LOAN_DIFF, null, CASH, LOAN);
        loanHistory(LOAN, "-1190000.00", "-1195600.00", "-1190000.00");
        doThrow(new IllegalStateException("boom")).when(entry).setLoanBalanceBySystem(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        assertThatThrownBy(() -> svc.confirm(FAM, ME, PID, 15, null, false)).hasMessage("boom");
        verify(mapper, never()).fillOccurrence(anyLong(), any());
    }
}
