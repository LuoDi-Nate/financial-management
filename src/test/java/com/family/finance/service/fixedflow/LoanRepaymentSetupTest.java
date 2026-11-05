package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.*;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.expense.ExpenseCategoryService;
import com.family.finance.service.member.MemberDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoanRepaymentSetupTest {
    final AccountMapper accounts=mock(AccountMapper.class);
    final FixedFlowMapper flows=mock(FixedFlowMapper.class);
    final PeriodMapper periods=mock(PeriodMapper.class);
    final SnapshotMapper snapshots=mock(SnapshotMapper.class);
    final MemberDirectory members=mock(MemberDirectory.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final Account loan=Account.builder().id(2L).familyId(1L).type(AccountType.LOAN).currency("CNY").displayName("样例房贷").build();
    LoanRepaymentSetupService service;

    @BeforeEach void setup() {
        service=new LoanRepaymentSetupService(accounts,flows,periods,snapshots,members,audit);
        when(accounts.findById(1,2)).thenReturn(Optional.of(loan));
        when(accounts.findActiveByFamily(1)).thenReturn(List.of(loan));
        when(flows.findActiveByFamily(1)).thenReturn(List.of());
        when(members.nameMap(1)).thenReturn(Map.of());
        when(accounts.updateLoanManualRepayment(anyLong(),anyLong(),anyBoolean())).thenReturn(1);
    }
    @Test void nonzeroLoanNeedsSetupRegardlessOfAlreadyConfirmedSnapshot() {
        var current=Period.builder().id(10L).periodStart(LocalDate.of(2026,11,1)).build();
        when(periods.findBalancePeriod(1)).thenReturn(Optional.of(current));when(periods.previousOf(1,10)).thenReturn(Optional.empty());
        when(snapshots.findByPeriod(1,10)).thenReturn(List.of(snapshot("SYSTEM_ADJUST")));
        assertThat(service.list(1).getFirst().needsSetup()).isTrue();
    }
    @Test void enabledFixedRepaymentHasPriorityOverManualFlag() {
        loan.setLoanManualRepayment(true);
        assertThat(LoanRepaymentSetupService.state(loan,BigDecimal.ZERO,true)).isEqualTo(LoanRepaymentSetupService.State.SET);
    }
    @Test void explicitlyManualLoanDoesNotRemind() {
        loan.setLoanManualRepayment(true);
        assertThat(LoanRepaymentSetupService.state(loan,new BigDecimal("-90000"),false)).isEqualTo(LoanRepaymentSetupService.State.MANUAL);
    }
    @Test void settledAndMissingBalancesHaveDifferentStates() {
        assertThat(LoanRepaymentSetupService.state(loan,BigDecimal.ZERO,false)).isEqualTo(LoanRepaymentSetupService.State.SETTLED);
        assertThat(LoanRepaymentSetupService.state(loan,null,false)).isEqualTo(LoanRepaymentSetupService.State.UNKNOWN);
    }
    @Test void disablingExistingRepaymentReturnsLoanToSetup() {
        assertThat(LoanRepaymentSetupService.state(loan,new BigDecimal("-1"),false)).isEqualTo(LoanRepaymentSetupService.State.UNSET);
    }
    @Test void manualChoiceWritesOnlyThePreferenceAndAudit() {
        service.setManual(1,7,2,true);verify(accounts).updateLoanManualRepayment(1,2,true);verifyNoInteractions(snapshots);
        verify(audit).record(eq(1L),eq(7L),any(),eq("account"),eq(2L),contains("自己填"));
    }
    @Test void manualChoiceCannotSilentlyLeaveAnActiveRepaymentEnabled() {
        when(flows.findActiveByFamily(1)).thenReturn(List.of(FixedFlow.builder().kind(FixedFlow.REPAY).targetAccountId(2L).build()));
        assertThatThrownBy(()->service.setManual(1,7,2,true)).hasMessageContaining("已有固定还款");
        verify(accounts,never()).updateLoanManualRepayment(anyLong(),anyLong(),anyBoolean());
    }
    @Test void restoringReminderIsReversibleAndDoesNotWriteMoney() {
        loan.setLoanManualRepayment(true);service.setManual(1,7,2,false);
        verify(accounts).updateLoanManualRepayment(1,2,false);verifyNoInteractions(snapshots);
    }
    @Test void foreignOrArchivedAccountCannotBeChanged() {
        when(accounts.findById(9,2)).thenReturn(Optional.empty());assertThatThrownBy(()->service.setManual(9,7,2,true)).hasMessageContaining("不存在");
        loan.setArchivedAt(java.time.LocalDateTime.now());assertThatThrownBy(()->service.setManual(1,7,2,true)).hasMessageContaining("在用贷款");
        verify(accounts,never()).updateLoanManualRepayment(anyLong(),anyLong(),anyBoolean());
    }
    @Test void carriedSnapshotWithSystemMemberDoesNotCountAsHumanSubmission() {
        assertThat(LoanRepaymentSetupService.submitted(snapshot("CARRIED_FORWARD"))).isFalse();
        assertThat(LoanRepaymentSetupService.submitted(snapshot("MANUAL"))).isTrue();
        assertThat(LoanRepaymentSetupService.submitted(snapshot("SYSTEM_ADJUST"))).isTrue();
    }
    @Test void newRepaymentStartsNextMonthWhenCurrentBalanceAlreadySubmitted() {
        var service=fixedService();when(snapshots.findByPeriodAndAccount(1,10,2)).thenReturn(Optional.of(snapshot("MANUAL")));
        assertThat(service.repayStarts(1,2L)).isEqualTo(LocalDate.of(2026,12,1));
    }
    @Test void newRepaymentStartsCurrentMonthForCarriedOrMissingBalance() {
        var service=fixedService();when(snapshots.findByPeriodAndAccount(1,10,2)).thenReturn(Optional.of(snapshot("CARRIED_FORWARD")));
        assertThat(service.repayStarts(1,2L)).isEqualTo(LocalDate.of(2026,11,1));
        when(snapshots.findByPeriodAndAccount(1,10,2)).thenReturn(Optional.empty());
        assertThat(service.repayStarts(1,2L)).isEqualTo(LocalDate.of(2026,11,1));
    }
    @Test void rateLoanPrefillsPrincipalInterestModeAndFirstDay() {
        var service=fixedService();loan.setAnnualRatePct(new BigDecimal("3.5"));loan.setDefaultPaymentSourceAccountId(3L);
        when(flows.templateCodeOf(1,2)).thenReturn(Optional.empty());
        var form=service.prefillForLoan(1,2,LocalDate.of(2026,11,20));
        assertThat(form.getAutoMethod()).isEqualTo(FixedFlow.LOAN_RATE);assertThat(form.getDayOfMonth()).isEqualTo(1);
        assertThat(form.getAccountId()).isEqualTo(3L);
    }
    @Test void cardAndUnratedLoanKeepTheirExistingCalculationRules() {
        var service=fixedService();when(flows.templateCodeOf(1,2)).thenReturn(Optional.of("credit_card"));
        assertThat(service.prefillForLoan(1,2,LocalDate.now()).getAutoMethod()).isEqualTo(FixedFlow.CARD_FULL);
        when(flows.templateCodeOf(1,2)).thenReturn(Optional.empty());
        assertThat(service.prefillForLoan(1,2,LocalDate.now()).getAutoMethod()).isEqualTo(FixedFlow.LOAN_DIFF);
    }
    private FixedFlowService fixedService() {
        var service=new FixedFlowService(flows,accounts,mock(CashFlowCategoryMapper.class),mock(InsurancePolicyMapper.class),
                mock(MemberMapper.class),periods,mock(ExpenseCategoryService.class),audit);
        ReflectionTestUtils.setField(service,"snapshotMapper",snapshots);
        when(periods.findBalancePeriod(1)).thenReturn(Optional.of(Period.builder().id(10L).periodStart(LocalDate.of(2026,11,1)).build()));
        return service;
    }
    private PeriodSnapshot snapshot(String source) {
        return PeriodSnapshot.builder().periodId(10L).accountId(2L).endBalance(new BigDecimal("-90000")).submittedBy(7L).sourceTag(source).build();
    }
}
