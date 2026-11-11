package com.family.finance.service.goal;

import com.family.finance.calc.GoalCashflowCalculator;
import com.family.finance.domain.goal.GoalForecastForm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoalInsightContextTest {
    private GoalForecastService.View forecast(String contribution, String source) {
        var result = GoalCashflowCalculator.calculate(LocalDate.of(2026, 11, 11),
                new BigDecimal("5000000"), new BigDecimal("10000000"),
                contribution == null ? null : new BigDecimal(contribution), null);
        return new GoalForecastService.View(true, result, "CNY", source, source, null,
                new GoalForecastForm(), Map.of(), List.of());
    }

    @Test void contextUsesTheSameComputedDateAndIdentifiesTheSavingsBasis() {
        assertThat(GoalLlmService.forecastContext(forecast("40000", "家庭月结余")))
                .contains("2037年04月", "125", "10年5个月", "家庭月结余", "不额外计入未来投资收益");
    }

    @Test void missingSavingsDoNotBecomeZeroOrAnInventedDate() {
        assertThat(GoalLlmService.forecastContext(forecast(null, "待来源")))
                .contains("NEEDS_DATA", "暂不估算", "月可用结余=—", "无预计日期时不得推算")
                .doesNotContain("2037年04月");
    }

    @Test void zeroAndNegativeSavingsKeepTheirOwnStates() {
        assertThat(GoalLlmService.forecastContext(forecast("0", "手填"))).contains("NO_GROWTH", "当前月结余为零");
        assertThat(GoalLlmService.forecastContext(forecast("-1000", "手填"))).contains("NO_GROWTH", "当前每月净流出");
    }

    @Test void unsupportedMetricsHaveNoInventedSavingsContext() {
        assertThat(GoalLlmService.forecastContext(null)).isEmpty();
        var unsupported = new GoalForecastService.View(false, null, "CNY", "AUTO", "", null,
                new GoalForecastForm(), Map.of(), List.of());
        assertThat(GoalLlmService.forecastContext(unsupported)).isEmpty();
    }

    @Test void reportFingerprintChangesWhenSavingsOrTheirSourceChanges() {
        var retirement = mock(RetirementPlanService.View.class);
        when(retirement.fingerprint()).thenReturn("same-retirement-plan");
        String before = GoalReportService.contextFingerprint(retirement, forecast("40000", "自动"));
        assertThat(before).hasSize(64).isEqualTo(GoalReportService.contextFingerprint(retirement, forecast("40000", "自动")));
        assertThat(before).isNotEqualTo(GoalReportService.contextFingerprint(retirement, forecast("20000", "自动")));
        assertThat(before).isNotEqualTo(GoalReportService.contextFingerprint(retirement, forecast("40000", "手填")));
        assertThat(GoalReportService.contextFingerprint(retirement, null)).isEqualTo("same-retirement-plan");
        assertThat(GoalReportService.contextFingerprint(null, forecast("40000", "自动"))).isNull();
    }
}
