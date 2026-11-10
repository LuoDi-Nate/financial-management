package com.family.finance.service.goal;

import com.family.finance.calc.GoalCashflowCalculator;
import com.family.finance.calc.RetirementCalculator;
import com.family.finance.domain.goal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalProgressSummaryTest {
    private static final LocalDate TODAY = LocalDate.of(2026,11,10);
    private static BigDecimal n(String value) { return value == null ? null : new BigDecimal(value); }
    private GoalProgressService.GoalProgress retirement(String assets, String contribution, String rate, String currency) {
        var plan = new RetirementPlan(); plan.setVersion(2); plan.setBaseCurrency(currency); plan.setPriceDate(TODAY);
        plan.setTargetMonth("2036-11"); plan.setMonthlyExpense(n("15000")); plan.setDirectRealRate(true); plan.setRealReturnPct(n(rate));
        plan.setChildReserve(n("400000")); plan.setMedicalReserve(n("400000")); plan.setHousingReserve(BigDecimal.ZERO); plan.setOtherReserve(BigDecimal.ZERO);
        var result = RetirementCalculator.calculate(plan,TODAY,n(assets),n("2000000"),null,List.of());
        var view = mock(RetirementPlanService.View.class);
        when(view.plan()).thenReturn(plan); when(view.result()).thenReturn(result);
        when(view.returnBasis()).thenReturn("规划假设 · 小Lin 2.5% 实际回报");
        var forecastResult = GoalCashflowCalculator.calculate(TODAY,result.assets(),result.totalAssetNeed(),n(contribution),null);
        var forecast = new GoalForecastService.View(true,forecastResult,currency,"AUTO","家庭净结余中位数",null,new GoalForecastForm(),Map.of(),List.of());
        var goal = Goal.builder().goalType(GoalType.RETIREMENT).name("退休").description("这不是计算依据").build();
        return new GoalProgressService.GoalProgress(goal,null,result.assets(),result.totalAssetNeed(),result.coverage(),null,null,
                GoalMetric.AMOUNT_TOTAL,GoalComparator.GTE,TimeMode.OPEN,null,2,view,forecast);
    }
    private GoalProgressSummary.Section section(GoalProgressSummary summary, String title) {
        return summary.sections().stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
    }
    @Test void retirementExplainsTheDisplayedDenominatorAnd125MonthEstimate() {
        var summary = GoalProgressSummary.from(retirement("5000000","40000","2.5","CNY"),true);
        assertEquals("¥15,000.00 × 12 ÷ 2.5% = ¥7,200,000.00",section(summary,"生活本金").values());
        assertEquals("¥7,200,000.00 + ¥800,000.00 + ¥2,000,000.00 = ¥10,000,000.00",section(summary,"财务自由所需资金").values());
        assertTrue(section(summary,"当前进度").values().contains("50.0%"));
        assertEquals("¥5,000,000.00 ÷ ¥40,000.00 → 125个月",section(summary,"预计达成时间").values());
        assertTrue(section(summary,"预计达成时间").note().contains("2037年04月"));
        assertTrue(section(summary,"生活本金").note().contains("规划假设"));
        assertFalse(summary.toString().contains("这不是计算依据"));
        assertTrue(section(summary,"生活本金").privateValues());
    }
    @Test void explanationReadsEvaluatedValuesWithoutRecalculatingFromSavedPlan() {
        var progress = retirement("5000000","40000","2.5","CNY");
        progress.retirement().plan().setMonthlyExpense(n("777"));
        assertTrue(section(GoalProgressSummary.from(progress,true),"生活本金").values().startsWith("¥15,000.00"));
    }
    @Test void historicalReportOmitsCurrentForecastExplanation() {
        var summary = GoalProgressSummary.from(retirement("5000000","40000","2.5","CNY"),false);
        assertFalse(summary.sections().stream().anyMatch(s -> s.title().contains("时间")));
        assertEquals("",summary.assumption());
    }
    @ParameterizedTest @ValueSource(strings={"0","-5000"})
    void noGrowthDoesNotShowADivisionOrInventDate(String contribution) {
        var section = section(GoalProgressSummary.from(retirement("5000000",contribution,"2.5","CNY"),true),"预计达成时间");
        assertTrue(section.formula().contains("缺口暂不缩小"));
        assertFalse(section.values().contains("÷")); assertFalse(section.note().contains("2037"));
    }
    @Test void missingContributionNeverBecomesZero() {
        var section = section(GoalProgressSummary.from(retirement("5000000",null,"2.5","CNY"),true),"预计达成时间");
        assertNull(section.values()); assertTrue(section.note().contains("暂无完整月结余"));
    }
    @Test void achievedNeedsNoContribution() {
        var section = section(GoalProgressSummary.from(retirement("11000000",null,"2.5","CNY"),true),"预计达成时间");
        assertEquals("已达成 · 0个月",section.values());
    }
    @Test void nonPositiveRealReturnIsNotDescribedAsAZeroTarget() {
        var summary = GoalProgressSummary.from(retirement("5000000","40000","-1","CNY"),true);
        assertEquals("无有限的永久本金门槛",section(summary,"生活本金").values());
        assertTrue(section(summary,"当前进度").values().contains("暂不可用"));
    }
    @Test void moneyKeepsThePlanBaseCurrency() {
        var summary = GoalProgressSummary.from(retirement("5000000","40000","2.5","USD"),true);
        assertTrue(summary.basis().startsWith("USD")); assertTrue(section(summary,"生活本金").values().contains("$15,000.00"));
        assertFalse(summary.toString().contains("¥"));
    }
    private GoalProgressService.GoalProgress custom(GoalMetric metric, GoalComparator comparator) {
        var goal = Goal.builder().goalType(GoalType.CUSTOM).metric(metric).comparator(comparator).build();
        return new GoalProgressService.GoalProgress(goal,null,n("3.5"),n("7"),n("0.5"),null,null,metric,comparator,TimeMode.OPEN,null,0);
    }
    @Test void percentageGoalDoesNotPretendToBeMoneyOrAContributionForecast() {
        var summary = GoalProgressSummary.from(custom(GoalMetric.RETURN_XIRR,GoalComparator.GTE),true);
        assertTrue(section(summary,"当前进度").values().contains("3.5%"));
        assertFalse(section(summary,"当前进度").privateValues());
        assertEquals(2,summary.sections().size());
    }
    @Test void debtGoalExplainsItsReducingDirection() {
        var summary = GoalProgressSummary.from(custom(GoalMetric.TOTAL_LIAB,GoalComparator.LTE),true);
        assertTrue(section(summary,"当前进度").formula().contains("不高于目标"));
        assertFalse(summary.toString().contains("财务自由所需资金"));
    }
    @Test void reserveMonthMetricKeepsItsUnit() {
        var summary = GoalProgressSummary.from(custom(GoalMetric.EMERGENCY_MONTHS,GoalComparator.GTE),true);
        assertTrue(section(summary,"当前进度").values().contains("3.5个月"));
    }
}
