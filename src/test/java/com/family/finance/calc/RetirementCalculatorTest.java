package com.family.finance.calc;

import com.family.finance.domain.goal.RetirementPlan;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetirementCalculatorTest {
    private final LocalDate today = LocalDate.of(2026, 11, 4);
    private BigDecimal n(String value) { return new BigDecimal(value); }
    private RetirementPlan plan() {
        var p = new RetirementPlan(); p.setBaseCurrency("CNY"); p.setPriceDate(today); p.setTargetMonth("2036-11");
        p.setMonthlyExpense(n("10000")); p.setMonthlyContribution(n("8000"));
        p.setChildReserve(n("400000")); p.setMedicalReserve(n("300000")); p.setHousingReserve(n("0")); p.setOtherReserve(n("500000"));
        p.setBudgetConfirmed(true); p.setReservesConfirmed(true); p.setNoOverlapConfirmed(true); p.setAssetsConfirmed(true);
        p.setIncomeConfirmed(true); p.setContributionConfirmed(true); p.setAfterDebtConfirmed(true);
        p.setDirectRealRate(true); return p;
    }
    private RetirementCalculator.Result calculate(RetirementPlan p, String assets, String debt) {
        return RetirementCalculator.calculate(p, today, n(assets), n(debt), null, List.of());
    }
    @Test void videoExampleReconcilesForwardReverseAndFundsBridge() {
        var r = calculate(plan(), "6000000", "0");
        assertThat(r.target()).isEqualByComparingTo("4800000");
        assertThat(r.principal()).isEqualByComparingTo("4800000");
        assertThat(r.supportedExpense()).isEqualByComparingTo("10000");
        assertThat(r.coverage()).isEqualByComparingTo("1");
        assertThat(r.reached()).isTrue();
        assertThat(r.netFunds().subtract(r.totalNeed())).isEqualByComparingTo(r.principal().subtract(r.target()));
    }
    @Test void exactRealReturnDiffersFromSubtractingInflation() {
        var p = plan(); p.setDirectRealRate(false);
        var r = calculate(p, "6000000", "0");
        assertThat(r.target()).isEqualByComparingTo("4896000");
        assertThat(r.realRate().doubleValue()).isCloseTo((1.045 / 1.02) - 1, org.assertj.core.data.Offset.offset(1e-14));
    }
    @Test void debtAndReservesAreOnlyDeductedOnce() {
        var r = calculate(plan(), "6000000", "200000");
        assertThat(r.principal()).isEqualByComparingTo("4600000");
        assertThat(r.gap()).isEqualByComparingTo("200000");
        assertThat(r.netFunds().subtract(r.totalNeed())).isEqualByComparingTo("-200000");
    }
    @Test void negativePrincipalStopsProjection() {
        var r = calculate(plan(), "100000", "0");
        assertThat(r.state()).isEqualTo(RetirementCalculator.State.RESERVES_SHORT);
        assertThat(r.scenarios()).isEmpty(); assertThat(r.reached()).isFalse();
    }
    @Test void zeroAndNegativeRealReturnNeverBecomeZeroTarget() {
        for (String rate : List.of("0", "-1")) {
            var p = plan(); p.setRealReturnPct(n(rate));
            var r = calculate(p, "6000000", "0");
            assertThat(r.state()).isEqualTo(RetirementCalculator.State.NO_FINITE_TARGET);
            assertThat(r.target()).isNull(); assertThat(r.scenarios()).isEmpty();
        }
    }
    @Test void incomeCoverageDoesNotDivideByZeroAndKeepsReserveGap() {
        var p = plan(); p.setMonthlyIncome(n("10000")); p.setRealReturnPct(BigDecimal.ZERO);
        var r = calculate(p, "6000000", "0");
        assertThat(r.target()).isZero(); assertThat(r.coverage()).isNull();
        assertThat(r.state()).isEqualTo(RetirementCalculator.State.INCOME_COVERS);
        assertThat(calculate(p, "10000", "0").state()).isEqualTo(RetirementCalculator.State.RESERVES_SHORT);
    }
    @Test void missingReserveIsNotAnAssumedZero() {
        var p = plan(); p.setMedicalReserve(null);
        var r = calculate(p, "6000000", "0");
        assertThat(r.state()).isEqualTo(RetirementCalculator.State.MISSING);
        assertThat(r.principal()).isNull(); assertThat(r.reached()).isFalse();
    }
    @Test void zeroReturnMonthlyPathUsesMonthEndContributionsAndFixedDate() {
        var p = plan(); p.setInflationPct(BigDecimal.ZERO); p.setLowReturnPct(BigDecimal.ZERO);
        p.setMiddleReturnPct(BigDecimal.ZERO); p.setHighReturnPct(BigDecimal.ZERO);
        p.setTargetMonth("2027-01"); p.setMonthlyContribution(n("100"));
        var s = calculate(p, "2000000", "0").scenarios().get(1);
        assertThat(s.path().get(0)).isEqualByComparingTo("800000");
        assertThat(s.path().get(2)).isEqualByComparingTo("800200");
        assertThat(s.atTargetMonth()).isEqualByComparingTo("800200");
    }
    @Test void nominalAndInflationIndexedContributionsRemainDifferent() {
        var p = plan(); p.setContributionFollowsInflation(false);
        var fixed = calculate(p, "2000000", "0").scenarios().get(1);
        p.setContributionFollowsInflation(true);
        var indexed = calculate(p, "2000000", "0").scenarios().get(1);
        assertThat(indexed.path().get(120)).isGreaterThan(fixed.path().get(120));
    }
    @Test void negativeContributionIsRetainedRatherThanClamped() {
        var p = plan(); p.setMonthlyContribution(n("-10000")); p.setInflationPct(BigDecimal.ZERO);
        p.setLowReturnPct(BigDecimal.ZERO); p.setMiddleReturnPct(BigDecimal.ZERO); p.setHighReturnPct(BigDecimal.ZERO);
        var r = calculate(p, "2000000", "0");
        assertThat(r.monthlyContribution()).isNegative();
        assertThat(r.scenarios().get(1).path().get(120)).isNegative();
    }
    @Test void zeroContributionIsNotMissing() {
        var p = plan(); p.setMonthlyContribution(BigDecimal.ZERO);
        assertThat(calculate(p, "2000000", "0").ready()).isTrue();
        p.setMonthlyContribution(null); assertThat(calculate(p, "2000000", "0").ready()).isFalse();
    }
    @Test void noConfirmationCannotClaimTargetReached() {
        var p = plan(); p.setAssetsConfirmed(false);
        assertThat(calculate(p, "6000000", "0").reached()).isFalse();
    }
    @Test void currencyScalingPreservesCoverageAndCrossingDates() {
        var p = plan(); var base = calculate(p, "2000000", "100000");
        p.setMonthlyExpense(p.getMonthlyExpense().multiply(n("0.125")));
        p.setMonthlyIncome(p.getMonthlyIncome().multiply(n("0.125")));
        p.setMonthlyContribution(p.getMonthlyContribution().multiply(n("0.125")));
        p.setChildReserve(p.getChildReserve().multiply(n("0.125"))); p.setMedicalReserve(p.getMedicalReserve().multiply(n("0.125")));
        p.setOtherReserve(p.getOtherReserve().multiply(n("0.125")));
        var changed = calculate(p, "250000", "12500");
        assertThat(changed.coverage()).isEqualByComparingTo(base.coverage());
        assertThat(changed.neutralDate()).isEqualTo(base.neutralDate());
        assertThat(changed.target()).isEqualByComparingTo(base.target().multiply(n("0.125")));
    }
    @Test void currentTargetMonthIsSeparateFromForecastFirstCrossing() {
        var p = plan(); p.setTargetMonth("2026-11");
        var r = calculate(p, "2000000", "0");
        assertThat(r.retirementDate()).isEqualTo(LocalDate.of(2026,11,30));
        assertThat(r.scenarios().get(1).atTargetMonth()).isEqualByComparingTo(r.principal());
        assertThat(r.scenarios().get(1).firstReached()).isAfter(r.retirementDate());
    }
    @Test void sourceProblemBlocksFormalResultAndProjection() {
        var r = RetirementCalculator.calculate(plan(), today, n("6000000"), BigDecimal.ZERO, null, List.of("汇率缺失"));
        assertThat(r.ready()).isFalse(); assertThat(r.scenarios()).isEmpty();
        assertThat(r.problems()).contains("汇率缺失");
    }
    @Test void originalMoneyIsRebasedWhenPriceDateChanges() {
        var p = plan(); p.setPriceDate(today.minusYears(1));
        assertThat(calculate(p, "6000000", "0").monthlyExpense()).isGreaterThan(n("10000"));
    }
    @Test void firstCrossingIsFoundEvenWhenFiftyYearEndpointFallsBelowTarget() {
        var p = plan(); p.setMonthlyExpense(n("1041.666666")); p.setInflationPct(n("15")); p.setMonthlyContribution(n("20000"));
        p.setLowReturnPct(BigDecimal.ZERO); p.setMiddleReturnPct(BigDecimal.ZERO); p.setHighReturnPct(BigDecimal.ZERO);
        p.setChildReserve(BigDecimal.ZERO); p.setMedicalReserve(BigDecimal.ZERO); p.setOtherReserve(BigDecimal.ZERO);
        var r = calculate(p, "110000", "0"); var s = r.scenarios().get(1);
        assertThat(s.path().getLast()).isLessThan(r.target());
        assertThat(s.firstReached()).isNotNull();
    }
}
