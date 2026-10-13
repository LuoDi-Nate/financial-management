package com.family.finance.service.fixedflow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.32 · 某一期里哪天该有、到了没有(FR-1100 / FR-1140) */
class FixedFlowScheduleTest {

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test
    void monthly_dayInsideMonthlyPeriod() {
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 5, null, null, d("2026-10-01"), d("2026-10-31")))
                .isEqualTo(d("2026-10-05"));
    }

    @Test
    void monthly_dayClampedToMonthEnd() {
        // 设的 30 号,二月没有 → 取当月最后一天(闰年 29、平年 28)
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 30, null, null, d("2027-02-01"), d("2027-02-28")))
                .isEqualTo(d("2027-02-28"));
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 31, null, null, d("2028-02-01"), d("2028-02-29")))
                .isEqualTo(d("2028-02-29"));
    }

    @Test
    void yearly_onlyInListedMonths() {
        assertThat(FixedFlowSchedule.occurrenceIn("YEARLY", "3", 15, null, null, d("2027-03-01"), d("2027-03-31")))
                .isEqualTo(d("2027-03-15"));
        assertThat(FixedFlowSchedule.occurrenceIn("YEARLY", "3", 15, null, null, d("2027-04-01"), d("2027-04-30"))).isNull();
        assertThat(FixedFlowSchedule.occurrenceIn("YEARLY", "3,9", 1, null, null, d("2027-09-01"), d("2027-09-30")))
                .isEqualTo(d("2027-09-01"));
        assertThat(FixedFlowSchedule.occurrenceIn("YEARLY", "", 1, null, null, d("2027-09-01"), d("2027-09-30"))).isNull();
    }

    @Test
    void startAndEndMonthBound() {
        YearMonth start = YearMonth.of(2026, 10), end = YearMonth.of(2026, 12);
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 8, start, end, d("2026-09-01"), d("2026-09-30"))).isNull();
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 8, start, end, d("2026-12-01"), d("2026-12-31")))
                .isEqualTo(d("2026-12-08"));
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 8, start, end, d("2027-01-01"), d("2027-01-31"))).isNull();
    }

    @Test
    void weeklyPeriod_zeroOrOneOccurrence() {
        // 按周记账的家庭:这一周里有那天才算
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 5, null, null, d("2026-10-05"), d("2026-10-11")))
                .isEqualTo(d("2026-10-05"));
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 5, null, null, d("2026-10-12"), d("2026-10-18"))).isNull();
        // 跨月的一周
        assertThat(FixedFlowSchedule.occurrenceIn("MONTHLY", null, 2, null, null, d("2026-09-28"), d("2026-10-04")))
                .isEqualTo(d("2026-10-02"));
    }

    @Test
    void due_fromTheDayOn() {
        assertThat(FixedFlowSchedule.isDue(d("2026-10-12"), d("2026-10-12"))).as("当天起算").isTrue();
        assertThat(FixedFlowSchedule.isDue(d("2026-10-13"), d("2026-10-12"))).isFalse();
        assertThat(FixedFlowSchedule.isDue(null, d("2026-10-12"))).isFalse();
    }

    @Test
    void months_parseAndFormat() {
        assertThat(FixedFlowSchedule.parseMonths("9, 3,，13,x")).containsExactly(3, 9);
        assertThat(FixedFlowSchedule.formatMonths(Set.of(9, 3))).isEqualTo("3,9");
        assertThat(FixedFlowSchedule.remainingCount("YEARLY", "3", YearMonth.of(2026, 10), YearMonth.of(2033, 3))).isEqualTo(7);
        assertThat(FixedFlowSchedule.remainingCount("MONTHLY", null, YearMonth.of(2026, 10), null)).isEqualTo(-1);
    }
}
