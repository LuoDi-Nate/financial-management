package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Only current funding gap / monthly savings; independent of investment scenarios. */
public final class GoalCashflowCalculator {
    private GoalCashflowCalculator() { }
    public enum State { READY, ACHIEVED, NEEDS_DATA, NO_GROWTH, OVER_HORIZON, ERROR }
    public record Result(State state, LocalDate asOf, BigDecimal assets, BigDecimal target, BigDecimal gap,
                         BigDecimal contribution, Integer months, LocalDate date, YearMonth wish, Long monthDelta,
                         String reason) {
        public String dateLabel() {
            return switch (state) {
                case READY -> date.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
                case ACHIEVED -> "已达成";
                case OVER_HORIZON -> "超过50年";
                case NO_GROWTH -> "暂无达成月份";
                case NEEDS_DATA -> "暂不估算";
                case ERROR -> "估算暂不可用";
            };
        }
        public String durationLabel() { return months == null ? state == State.OVER_HORIZON ? "超过50年" : "暂不估算" : duration(months); }
        public String comparisonLabel() {
            if (state == State.ACHIEVED) return "当前资金已达成";
            if (wish == null) return "未设希望月份";
            if (monthDelta == null) return "有结余参考后再对照";
            return monthDelta == 0 ? "预计与希望月份一致" : "比希望月份" + (monthDelta > 0 ? "晚" : "早") + duration(Math.abs(monthDelta));
        }
        public boolean available() { return state == State.READY || state == State.ACHIEVED; }
    }
    public static Result calculate(LocalDate asOf, BigDecimal assets, BigDecimal target, BigDecimal contribution, YearMonth wish) {
        if (assets == null || target == null || target.signum() < 0)
            return result(State.NEEDS_DATA, asOf, assets, target, null, contribution, null, null, wish, "当前资金或所需资金暂不可用");
        BigDecimal gap = target.subtract(assets).max(BigDecimal.ZERO);
        if (gap.signum() == 0) return result(State.ACHIEVED, asOf, assets, target, gap, contribution, 0, asOf, wish, "当前资金已达到门槛");
        if (contribution == null)
            return result(State.NEEDS_DATA, asOf, assets, target, gap, null, null, null, wish, "暂无完整月结余,可手填本目标的月可用结余");
        if (contribution.signum() <= 0)
            return result(State.NO_GROWTH, asOf, assets, target, gap, contribution, null, null, wish,
                    contribution.signum() == 0 ? "当前月结余为零,资金缺口暂不缩小" : "当前每月净流出,资金缺口暂不缩小");
        BigDecimal count = gap.divide(contribution, 0, RoundingMode.CEILING);
        if (count.compareTo(BigDecimal.valueOf(600)) > 0)
            return result(State.OVER_HORIZON, asOf, assets, target, gap, contribution, null, null, wish, "按当前结余需要超过50年");
        int months = count.intValueExact();
        return result(State.READY, asOf, assets, target, gap, contribution, months,
                YearMonth.from(asOf).plusMonths(months).atEndOfMonth(), wish, "仅按当前目标金额和月结余不变估算");
    }
    public static Result unavailable(Result basis, String reason) {
        if (basis.state == State.ACHIEVED) return basis;
        return result(State.ERROR, basis.asOf, basis.assets, basis.target, basis.gap, null, null, null, basis.wish, reason);
    }
    private static Result result(State state, LocalDate asOf, BigDecimal a, BigDecimal f, BigDecimal gap,
                                 BigDecimal c, Integer n, LocalDate date, YearMonth wish, String reason) {
        Long delta = date == null || wish == null ? null : ChronoUnit.MONTHS.between(wish, YearMonth.from(date));
        return new Result(state, asOf, a, f, gap, c, n, date, wish, delta, reason);
    }
    public static String duration(long months) {
        if (months == 0) return "0个月";
        return (months / 12 > 0 ? months / 12 + "年" : "") + (months % 12 > 0 ? months % 12 + "个月" : "");
    }
}
