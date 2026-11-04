package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseNature;
import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.domain.family.Family;
import com.family.finance.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetirementBudgetSourceTest {
    private final CashFlowMapper cash = mock(CashFlowMapper.class);
    private final PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
    private final FamilyMapper family = mock(FamilyMapper.class);
    private final ExpenseNatureService nature = mock(ExpenseNatureService.class);
    private final ExpenseLedgerService ledger = new ExpenseLedgerService(cash, pmc, family, mock(PeriodMapper.class));

    private void setup() {
        ReflectionTestUtils.setField(ledger, "retirementExpenseNature", nature);
        when(family.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).expenseEntryMode(ExpenseEntryMode.ITEMIZED.name()).build()));
    }
    @Test void consumptionKeepsUnclassifiedAndRefundsButExcludesLoanPrincipal() {
        setup();
        when(cash.sumExpenseByPeriodCategoryOneOff(eq(1L),any())).thenReturn(List.of(
                new CashFlowMapper.CatOneOffSum(1L,"consumption",null,false,new BigDecimal("-50"),1),
                new CashFlowMapper.CatOneOffSum(1L,"loan_payment",null,false,new BigDecimal("1000"),1)));
        var r = ledger.retirementBudget(1,List.of(1L),Set.of(),false,Set.of()).get(1L);
        assertThat(r.amount()).isEqualByComparingTo("-50"); assertThat(r.totalOnly()).isFalse();
    }
    @Test void oneOffUsesSharedCategoryInheritanceRule() {
        setup(); when(nature.natureMap(1)).thenReturn(Map.of(9L, ExpenseNature.ONE_OFF));
        when(nature.natureOf(false,9L,Map.of(9L,ExpenseNature.ONE_OFF))).thenReturn(ExpenseNature.ONE_OFF);
        when(cash.sumExpenseByPeriodCategoryOneOff(eq(1L),any())).thenReturn(List.of(
                new CashFlowMapper.CatOneOffSum(1L,"consumption",9L,false,new BigDecimal("400"),1)));
        var r = ledger.retirementBudget(1,List.of(1L),Set.of(),true,Set.of()).get(1L);
        assertThat(r.amount()).isZero(); assertThat(r.removed()).isEqualByComparingTo("400");
        verify(nature).natureOf(eq(false),eq(9L),anyMap());
    }
    @Test void selectedParentAlsoExcludesItsChildren() {
        setup(); when(nature.expandCategories(1,Set.of(5L))).thenReturn(Set.of(5L,9L));
        when(cash.sumExpenseByPeriodCategoryOneOff(eq(1L),any())).thenReturn(List.of(
                new CashFlowMapper.CatOneOffSum(1L,"consumption",9L,false,new BigDecimal("400"),1)));
        assertThat(ledger.retirementBudget(1,List.of(1L),Set.of(5L),false,Set.of()).get(1L).amount()).isZero();
    }
    @Test void explicitZeroTotalDoesNotPretendThereWasNoData() {
        setup(); var r = ledger.retirementBudget(1,List.of(1L),Set.of(),false,Set.of(1L)).get(1L);
        assertThat(r.amount()).isZero(); assertThat(r.totalOnly()).isTrue();
    }
    @Test void itemizedRefundsRemainVisibleAlongsideAnOldZeroTotal() {
        setup(); when(cash.sumExpenseByPeriodCategoryOneOff(eq(1L),any())).thenReturn(List.of(
                new CashFlowMapper.CatOneOffSum(1L,"consumption",null,false,new BigDecimal("-50"),1)));
        var r = ledger.retirementBudget(1,List.of(1L),Set.of(),false,Set.of(1L)).get(1L);
        assertThat(r.amount()).isEqualByComparingTo("-50"); assertThat(r.totalOnly()).isFalse();
    }
}
