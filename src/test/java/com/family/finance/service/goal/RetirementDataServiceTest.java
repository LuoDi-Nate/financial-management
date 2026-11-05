package com.family.finance.service.goal;

import com.family.finance.domain.account.*;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.goal.RetirementPlan;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.*;
import com.family.finance.repository.*;
import com.family.finance.service.expense.ExpenseLedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetirementDataServiceTest {
    private final FactViewService facts = mock(FactViewService.class);
    private final FamilyMapper families = mock(FamilyMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final SnapshotMapper snapshots = mock(SnapshotMapper.class);
    private final FxMapper rates = mock(FxMapper.class);
    private final RetirementSourceMapper source = mock(RetirementSourceMapper.class);
    private final ExpenseLedgerService expenses = mock(ExpenseLedgerService.class);
    private final MacroBenchmarkMapper macro = mock(MacroBenchmarkMapper.class);
    private final RetirementDataService service = new RetirementDataService(facts,families,accounts,snapshots,rates,source,expenses,macro);
    private final Map<Long, ExpenseLedgerService.RetirementBudget> budgets = new LinkedHashMap<>();
    private final List<RetirementSourceMapper.Presence> presence = new ArrayList<>();
    private final LocalDate today = LocalDate.of(2026,11,4);

    private RetirementPlan setup(Long... ids) {
        when(families.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).baseCurrency("CNY").periodType(PeriodType.MONTHLY).build()));
        var slice = mock(FactSlice.class); when(facts.load(any())).thenReturn(slice); when(slice.closedPeriodIds()).thenReturn(List.of(ids));
        when(slice.lastPeriodId()).thenReturn(11L); when(slice.rows()).thenReturn(List.of());
        when(slice.periodStartOf(11L)).thenReturn(LocalDate.of(2026,11,1));
        for (Long id : ids) {
            when(slice.periodStartOf(id)).thenReturn(LocalDate.of(2026,id.intValue(),1));
            when(facts.cashflowBreakdown(slice,id)).thenReturn(new CashflowBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        }
        when(source.presence(eq(1L),any())).thenReturn(presence);
        when(expenses.retirementBudget(eq(1L),any(),any(),anyBoolean(),any())).thenReturn(budgets);
        var p = new RetirementPlan(); p.setPriceDate(today); p.setInflationPct(BigDecimal.ZERO); return p;
    }
    private void month(long id, String income, String expense, int incomeFields, int incomeRows) {
        presence.add(new RetirementSourceMapper.Presence(id,incomeFields,1,incomeRows,0,2));
        budgets.put(id,new ExpenseLedgerService.RetirementBudget(new BigDecimal(expense),BigDecimal.ZERO,"测试来源",false));
        var s = facts.load(null);
        when(facts.cashflowBreakdown(s,id)).thenReturn(new CashflowBreakdown(new BigDecimal(income),new BigDecimal(expense),new BigDecimal(income).subtract(new BigDecimal(expense))));
    }
    @Test void oldPmcAndNewAccountIncomeUseSameSettledWindow() {
        var p = setup(7L,8L,10L); month(7,"10000","4000",1,0); month(8,"12000","6000",0,2); month(10,"14000","7000",0,1);
        var result = service.load(1,p,today);
        assertThat(result.suggestedContribution()).isEqualByComparingTo("6000");
        assertThat(result.suggestedExpense()).isEqualByComparingTo("5666.67");
        assertThat(result.months()).extracting(RetirementDataService.Month::id).containsExactly(7L,8L,10L);
        verify(facts,never()).loadDefault(anyLong());
    }
    @Test void realZeroEntersDenominatorWhileMissingMonthDoesNot() {
        var p = setup(7L,8L,9L,10L); month(7,"0","0",1,0); month(8,"600","300",0,1); month(10,"900","600",0,1);
        var result = service.load(1,p,today);
        assertThat(result.suggestedExpense()).isEqualByComparingTo("300");
        assertThat(result.months().get(0).consumption()).isZero();
        assertThat(result.months().get(2).consumption()).isNull();
    }
    @Test void refundsRemainNegativeAndRequireExplicitBudget() {
        var p = setup(7L,8L,9L); month(7,"100","-50",0,1); month(8,"100","100",0,1); month(9,"100","100",0,1);
        var result = service.load(1,p,today);
        assertThat(result.months().getFirst().consumption()).isNegative();
        assertThat(result.suggestedExpense()).isNull(); assertThat(result.budgetNotes()).isNotEmpty();
    }
    @Test void fullZeroCompletionIsVisibleButUnfilledClosedMonthStaysMissing() {
        var p = setup(7L,8L); presence.add(new RetirementSourceMapper.Presence(7L,0,0,0,2,2));
        var result = service.load(1,p,today);
        assertThat(result.months().get(0).complete()).isTrue(); assertThat(result.months().get(0).consumption()).isZero();
        assertThat(result.months().get(1).complete()).isFalse(); assertThat(result.months().get(1).consumption()).isNull();
    }
    @Test void lostSelectionNeverTurnsIntoAllFamilyAssets() {
        var p = setup(); p.getAccountIds().add(99L);
        var result = service.load(1,p,today);
        assertThat(result.selectedAssets()).isZero(); assertThat(result.problems()).anyMatch(s -> s.contains("已归档或删除"));
    }
    @Test void fxSupportsInverseAndCrossRatesAndNeverAssumesOne() {
        var fx = List.of(FxRate.builder().baseCurrency("CNY").quoteCurrency("USD").rate(new BigDecimal("0.125")).build(),
                FxRate.builder().baseCurrency("CNY").quoteCurrency("HKD").rate(new BigDecimal("1.1")).build());
        assertThat(RetirementDataService.factor("USD","CNY",fx)).isEqualByComparingTo("8");
        assertThat(RetirementDataService.factor("USD","HKD",fx)).isEqualByComparingTo("8.8");
        assertThat(RetirementDataService.factor("JPY","CNY",fx)).isNull();
    }
    @Test void assetEligibilityDoesNotIncludeHomeOrInsurance() {
        assertThat(RetirementDataService.eligible(AccountType.CASH)).isTrue(); assertThat(RetirementDataService.eligible(AccountType.FUND)).isTrue();
        assertThat(RetirementDataService.eligible(AccountType.PROPERTY)).isFalse(); assertThat(RetirementDataService.eligible(AccountType.INSURANCE)).isFalse();
    }
    @Test void weeklyPeriodCannotBeSilentlyTreatedAsMonthlyContribution() {
        var p = setup(7L,8L,9L); month(7,"1000","100",0,1); month(8,"1000","100",0,1); month(9,"1000","100",0,1);
        when(families.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).baseCurrency("CNY").periodType(PeriodType.WEEKLY).build()));
        var result = service.load(1,p,today);
        assertThat(result.suggestedExpense()).isNull(); assertThat(result.suggestedContribution()).isNull();
        assertThat(result.budgetNotes()).anyMatch(s -> s.contains("周账期"));
    }
    @Test void unsplitTotalContainingPotentialRepaymentsCannotBecomeAutomaticLivingBudget() {
        var p = setup(7L,8L,9L); month(7,"1000","100",1,0); month(8,"1000","100",0,1); month(9,"1000","100",0,1);
        budgets.put(7L,new ExpenseLedgerService.RetirementBudget(new BigDecimal("100"),BigDecimal.ZERO,"家庭总额",true));
        var result = service.load(1,p,today);
        assertThat(result.suggestedExpense()).isNull(); assertThat(result.budgetNotes()).anyMatch(s -> s.contains("还款与消费"));
        assertThat(result.suggestedContribution()).isEqualByComparingTo("900");
    }
    @Test void revisionTwoUsesActualMonthlyAmountsAndExcludesOnlyUnusableMonth() {
        var p=setup(6L,7L,8L,9L,10L);p.setVersion(2);p.setInflationPct(new BigDecimal("15"));
        month(6,"1000","100",1,0);month(7,"1000","100",0,1);month(8,"1000","100",0,1);month(9,"1000","100",0,1);
        budgets.put(6L,new ExpenseLedgerService.RetirementBudget(new BigDecimal("100"),BigDecimal.ZERO,"家庭总额",true));
        var result=service.load(1,p,today);
        assertThat(result.suggestedExpense()).isEqualByComparingTo("100");
        assertThat(result.sources().budgetPeriods()).containsExactly(7L,8L,9L);
        assertThat(result.months().getFirst().consumption()).isNull();
    }
    @Test void revisionTwoRetainsRealRefundsInTheMeanWhileMissingMonthStaysMissing() {
        var p=setup(7L,8L,9L,10L);p.setVersion(2);
        month(7,"100","-50",0,1);month(8,"100","100",0,1);month(9,"100","100",0,1);
        var result=service.load(1,p,today);assertThat(result.suggestedExpense()).isEqualByComparingTo("50");
        assertThat(result.sources().budgetPeriods()).containsExactly(7L,8L,9L);assertThat(result.months().getLast().consumption()).isNull();
    }
}
