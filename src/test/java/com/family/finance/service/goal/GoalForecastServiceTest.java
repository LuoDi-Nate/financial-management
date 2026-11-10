package com.family.finance.service.goal;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.goal.*;
import com.family.finance.calc.GoalCashflowCalculator;
import com.family.finance.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GoalForecastServiceTest {
    final MonthlyContributionService contributions=mock(MonthlyContributionService.class);
    final FamilyMapper families=mock(FamilyMapper.class);
    final GoalMapper goals=mock(GoalMapper.class);
    final GoalForecastService service=new GoalForecastService(contributions,families,goals);
    final LocalDate today=LocalDate.of(2026,11,10);
    GoalForecastServiceTest(){when(families.findById(1)).thenReturn(Optional.of(Family.builder().id(1L).baseCurrency("CNY").build()));}
    Goal goal(){return Goal.builder().id(2L).familyId(1L).goalType(GoalType.CUSTOM).metric(GoalMetric.AMOUNT_TOTAL).comparator(GoalComparator.GTE).build();}
    GoalForecastService.View evaluate(Goal goal,GoalForecastService.Context context){return service.evaluate(goal,new BigDecimal("5000000"),new BigDecimal("10000000"),new BigDecimal("0.5"),null,null,context);}
    @ParameterizedTest @EnumSource(GoalMetric.class)
    void applicabilityIsExplicitNotAllAmounts(GoalMetric metric){
        var g=goal();g.setMetric(metric);
        boolean eligible=Set.of(GoalMetric.AMOUNT_TOTAL,GoalMetric.CASH_TOTAL,GoalMetric.NET_PRINCIPAL,GoalMetric.TOTAL_ASSETS).contains(metric);
        assertEquals(eligible,GoalForecastService.supports(g,null));g.setComparator(GoalComparator.LTE);assertFalse(GoalForecastService.supports(g,null));
    }
    @Test void noImplicitMigrationOfLegacyRetirement(){var g=goal();g.setGoalType(GoalType.RETIREMENT);assertFalse(GoalForecastService.supports(g,null));}
    @Test void oneContextSharesWindowAcrossTenGoals(){
        when(contributions.load(1,today)).thenReturn(new MonthlyContributionService.Window("CNY",today,today.minusMonths(6),today,List.of(),new BigDecimal("40000"),"source"));
        var context=service.context(1,today);
        for(int i=0;i<10;i++){var r=evaluate(goal(),context);assertEquals(125,r.result().months());assertEquals(4,r.indicators().size());}
        verify(contributions,times(1)).load(1,today);
    }
    @Test void sourceFailureIsAnIndependentIndicatorState(){
        when(contributions.load(1,today)).thenThrow(new IllegalStateException("private-details"));var r=evaluate(goal(),service.context(1,today));
        assertEquals(GoalCashflowCalculator.State.ERROR,r.result().state());assertEquals(new BigDecimal("5000000"),r.result().assets());
        assertEquals("AVAILABLE",r.indicators().getFirst().state());assertFalse(r.reason().contains("private-details"));
    }
    @Test void achievedDoesNotLoadSavings(){var r=service.evaluate(goal(),BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN,null,null,service.context(1,today));assertEquals(GoalCashflowCalculator.State.ACHIEVED,r.result().state());verifyNoInteractions(contributions);}
    @Test void storedManualCurrencyCannotBeReinterpreted(){
        var g=goal();g.setForecastSource("MANUAL");g.setForecastManualAmount(new BigDecimal("40000"));g.setForecastManualCurrency("USD");
        var r=evaluate(g,service.context(1,today));assertTrue(r.reason().contains("币种"));assertNull(r.result().date());assertEquals("AVAILABLE",r.indicators().getFirst().state());verifyNoInteractions(contributions);
    }
    @Test void zeroAndNegativeManualStayManual(){for(String c:List.of("0","-100")){var g=goal();g.setForecastSource("MANUAL");g.setForecastManualAmount(new BigDecimal(c));g.setForecastManualCurrency("CNY");assertEquals(GoalCashflowCalculator.State.NO_GROWTH,evaluate(g,service.context(1,today)).result().state());}verifyNoInteractions(contributions);}
    @Test void autoSaveDoesNotSubmitHiddenManualAmountOrCurrency(){
        var g=goal();g.setForecastManualAmount(BigDecimal.TEN);g.setForecastManualCurrency("USD");
        when(goals.findById(1,2)).thenReturn(Optional.of(g));when(goals.updateForecast(anyLong(),anyLong(),anyString(),any(),anyString(),any())).thenReturn(1);
        var form=new GoalForecastForm();form.setEstimateSource("AUTO");form.setEstimateMonthlyAmount("invalid");service.save(1,2,form);
        verify(goals).updateForecast(1,2,"AUTO",null,"CNY",null);verifyNoInteractions(contributions);
    }
    @Test void foreignAndArchivedGoalsCannotSave(){var form=new GoalForecastForm();form.setEstimateSource("AUTO");assertThrows(IllegalArgumentException.class,()->service.save(9,2,form));verify(goals,never()).updateForecast(anyLong(),anyLong(),any(),any(),any(),any());}
}
