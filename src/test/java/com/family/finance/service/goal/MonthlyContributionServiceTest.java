package com.family.finance.service.goal;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.*;
import com.family.finance.repository.*;
import com.family.finance.service.expense.ExpenseLedgerService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MonthlyContributionServiceTest {
    final FactViewService facts=mock(FactViewService.class);
    final FamilyMapper families=mock(FamilyMapper.class);
    final RetirementSourceMapper presence=mock(RetirementSourceMapper.class);
    final ExpenseLedgerService expenses=mock(ExpenseLedgerService.class);
    final FxMapper fx=mock(FxMapper.class);
    final MonthlyContributionService service=new MonthlyContributionService(facts,families,presence,expenses,fx);
    final FactSlice slice=mock(FactSlice.class);
    final LocalDate today=LocalDate.of(2026,11,10);
    final List<RetirementSourceMapper.Presence> rows=new ArrayList<>();
    MonthlyContributionServiceTest(){
        when(families.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).periodType(PeriodType.MONTHLY).baseCurrency("CNY").build()));
        when(facts.load(any())).thenReturn(slice);when(slice.rows()).thenReturn(List.of());
        when(presence.presence(eq(1L),any())).thenReturn(rows);when(fx.findRatesAsOf(1,today)).thenReturn(List.of());
        when(expenses.byPeriods(eq(1L),any())).thenReturn(Map.of());
    }
    void periods(Long...ids){when(slice.periodIds()).thenReturn(List.of(ids));when(slice.closedPeriodIds()).thenReturn(List.of(ids));for(Long id:ids)when(slice.periodStartOf(id)).thenReturn(LocalDate.of(2026,id.intValue(),1));}
    void month(long id,String inc,String exp,int incomeFields,int expenseFields,int completed){
        rows.add(new RetirementSourceMapper.Presence(id,incomeFields,expenseFields,0,completed,2));
        when(facts.cashflowBreakdown(slice,id)).thenReturn(new CashflowBreakdown(new BigDecimal(inc),new BigDecimal(exp),new BigDecimal(inc).subtract(new BigDecimal(exp))));
    }
    @Test void medianUsesMonthlyDifferencesNotDifferenceOfMedians(){
        periods(5L,6L,7L);month(5,"100","0",1,1,0);month(6,"200","150",1,1,0);month(7,"1000","950",1,1,0);
        var w=service.load(1,today);assertEquals(new BigDecimal("50"),w.median());assertEquals(3,w.count());
        assertEquals(LocalDate.of(2026,5,1),w.from());assertEquals(LocalDate.of(2026,10,31),w.to());
        verify(facts).load(argThat(f->f.accountIds()==null&&f.includeArchived()&&f.viewCurrency().equals("CNY")&&f.rangeEnd().equals(w.to())));
    }
    @Test void explicitZeroIsIncludedWhileMissingExpenseIsNot(){
        periods(7L,8L,9L);month(7,"0","0",0,0,2);month(8,"100","0",1,0,0);month(9,"100","0",1,1,0);
        var w=service.load(1,today);assertEquals(new BigDecimal("50.00"),w.median());assertEquals(2,w.count());assertTrue(w.note().contains("样本较少"));
        assertFalse(w.months().get(3).complete());
    }
    @Test void currentFutureAndOlderMonthsAreNotBackfilled(){
        periods(4L,10L,11L,12L);month(4,"1000000","0",1,1,0);month(10,"100","0",1,1,0);month(11,"1000000","0",1,1,0);month(12,"1000000","0",1,1,0);
        var w=service.load(1,today);assertEquals(new BigDecimal("100"),w.median());assertEquals(1,w.count());
        verify(facts,never()).cashflowBreakdown(slice,11L);verify(facts,never()).cashflowBreakdown(slice,12L);verify(facts,never()).cashflowBreakdown(slice,4L);
    }
    @Test void unfinishedOpenIsExcludedAndSettledOpenCanBeUsed(){
        periods(9L,10L);month(9,"100","20",1,1,0);month(10,"9999","0",1,1,0);
        when(slice.closedPeriodIds()).thenReturn(List.of(9L));assertEquals(new BigDecimal("80"),service.load(1,today).median());
        when(slice.closedPeriodIds()).thenReturn(List.of(9L,10L));assertEquals(2,service.load(1,today).count());
    }
    @Test void negativeRealSavingsIsNotClamped(){periods(10L);month(10,"10","20",1,1,0);assertEquals(new BigDecimal("-10"),service.load(1,today).median());}
    @Test void noCompletedSourcesRemainsNull(){periods(10L);assertNull(service.load(1,today).median());}
    @Test void weeklyCannotMasqueradeAsMonthly(){
        when(families.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).periodType(PeriodType.WEEKLY).baseCurrency("CNY").build()));
        assertNull(service.load(1,today).median());verify(facts,never()).load(any());
    }
    @Test void missingHistoricalFxExcludesOnlyThatMonth(){
        periods(10L);month(10,"100","20",1,1,0);
        var row=mock(AccountPeriodFact.class);when(row.periodId()).thenReturn(10L);when(row.incomeOrig()).thenReturn(BigDecimal.ONE);when(row.accountCurrency()).thenReturn("USD");
        when(slice.rows()).thenReturn(List.of(row));var w=service.load(1,today);assertNull(w.median());assertTrue(w.months().getLast().reason().contains("汇率"));
    }
}
