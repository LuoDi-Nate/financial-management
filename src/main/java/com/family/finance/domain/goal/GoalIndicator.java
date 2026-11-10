package com.family.finance.domain.goal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A reading with its own availability; never a replacement for the goal condition. */
public record GoalIndicator(Kind kind, String label, Value value, String state, String source) {
    public enum Kind { FUNDING_PROGRESS, FUNDING_GAP, CASHFLOW_DATE, DEADLINE_DELTA }
    public sealed interface Value permits Ratio, Amount, DateAndMonths, MonthDifference { }
    public record Ratio(BigDecimal fraction) implements Value { }
    public record Amount(BigDecimal amount, String currency) implements Value { }
    public record DateAndMonths(LocalDate date, Integer months) implements Value { }
    public record MonthDifference(Long months) implements Value { }
}
