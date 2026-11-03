package com.family.finance.service;

import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.CashflowBreakdown;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.service.expense.ExpenseLedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportSavingsTest {
    private final PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
    private final FactViewService facts = mock(FactViewService.class);
    private final ExpenseLedgerService expenses = mock(ExpenseLedgerService.class);
    private final HouseholdCashflowService service = new HouseholdCashflowService(pmc, facts, expenses);
    private final Map<Long, ExpenseLedgerService.PeriodExpense> expenseById = new LinkedHashMap<>();

    private FactSlice slice(Long... months) {
        FactSlice slice = mock(FactSlice.class);
        when(slice.filter()).thenReturn(new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1), false, null, "CNY"));
        when(slice.closedPeriodIds()).thenReturn(List.of(months));
        for (Long month : months) {
            when(slice.periodStartOf(month)).thenReturn(LocalDate.of(2026, month.intValue(), 1));
        }
        when(pmc.findFamilyAggregateForPeriods(anyLong(), any())).thenReturn(List.of());
        when(expenses.byPeriods(anyLong(), any())).thenReturn(expenseById);
        return slice;
    }

    private void flow(FactSlice slice, long month, String income, String expense) {
        BigDecimal in = new BigDecimal(income), out = new BigDecimal(expense);
        when(facts.cashflowBreakdown(slice, month)).thenReturn(new CashflowBreakdown(in, out, in.subtract(out)));
        expenseById.put(month, new ExpenseLedgerService.PeriodExpense(month, LocalDate.of(2026, (int) month, 1), out,
                out.signum() == 0 ? ExpenseLedgerService.PeriodExpense.Source.NONE
                        : ExpenseLedgerService.PeriodExpense.Source.ITEMIZED, out.signum() == 0 ? 0 : 1));
    }

    @Test
    void selectedMonthUsesItsOwnCashflowEvenWhenLegacyTotalsEndEarlier() {
        FactSlice slice = slice(7L, 10L);
        flow(slice, 7, "20000", "1000");
        flow(slice, 10, "10000", "4000");

        var result = service.reportSavings(slice, 12);

        assertThat(result.latest().periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.latest().rate()).isEqualByComparingTo("0.60");
        assertThat(result.sumIncome()).isEqualByComparingTo("30000");
        assertThat(result.sumExpense()).isEqualByComparingTo("5000");
        assertThat(result.averageIncome()).isEqualByComparingTo("15000");
        assertThat(result.averageExpense()).isEqualByComparingTo("2500");
        assertThat(result.medianSavings()).isEqualByComparingTo("12500");
        assertThat(result.filledPeriods()).isEqualTo(2);
        assertThat(result.totalPeriods()).isEqualTo(12);
        verify(pmc, never()).findFamilyAggregateRecent(anyLong(), anyInt());
        verify(facts, never()).loadDefault(anyLong());
    }

    @Test
    void historicalReportDoesNotReadALaterMonth() {
        FactSlice slice = slice(7L, 9L);
        flow(slice, 7, "20000", "1000");
        flow(slice, 9, "12000", "6000");

        var result = service.reportSavings(slice, 3);

        assertThat(result.latest().periodId()).isEqualTo(9L);
        assertThat(result.latest().rate()).isEqualByComparingTo("0.50");
        verify(facts, never()).cashflowBreakdown(any(), eq(10L));
    }

    @Test
    void missingMonthStaysBlankAndDoesNotDiluteAverageOrMedian() {
        FactSlice slice = slice(7L, 8L, 10L);
        flow(slice, 7, "20000", "1000");
        flow(slice, 8, "0", "0");
        flow(slice, 10, "10000", "4000");

        var result = service.reportSavings(slice, 6);

        assertThat(result.series().get(1).income()).isNull();
        assertThat(result.series().get(1).expense()).isNull();
        assertThat(result.filledPeriods()).isEqualTo(2);
        assertThat(result.averageIncome()).isEqualByComparingTo("15000");
        assertThat(result.medianSavings()).isEqualByComparingTo("12500");
    }

    @Test
    void zeroIncomeCannotReuseAnOlderSavingsRate() {
        FactSlice slice = slice(7L, 10L);
        flow(slice, 7, "20000", "1000");
        flow(slice, 10, "0", "4000");

        var result = service.reportSavings(slice, 12);

        assertThat(result.latest().periodId()).isEqualTo(10L);
        assertThat(result.latest().rate()).isNull();
    }

    @Test
    void explicitlyRecordedZeroCountsAsDataAndAllUsesActualPeriodCount() {
        FactSlice slice = slice(10L);
        flow(slice, 10, "0", "0");
        when(pmc.findFamilyAggregateForPeriods(anyLong(), any())).thenReturn(List.of(
                new PeriodMemberCashflowMapper.SinglePeriodAggregate(10L, 10L, BigDecimal.ZERO, BigDecimal.ZERO, 1)));

        var result = service.reportSavings(slice, 0);

        assertThat(result.filledPeriods()).isEqualTo(1);
        assertThat(result.totalPeriods()).isEqualTo(1);
        assertThat(result.series().getFirst().income()).isEqualByComparingTo("0");
        assertThat(result.latest().rate()).isNull();
    }

    @Test
    void refundOnlyMonthStillCountsAsRecordedCashflow() {
        FactSlice slice = slice(10L);
        flow(slice, 10, "0", "-600");
        expenseById.clear();

        var result = service.reportSavings(slice, 1);

        assertThat(result.filledPeriods()).isEqualTo(1);
        assertThat(result.averageExpense()).isEqualByComparingTo("-600");
        assertThat(result.medianSavings()).isEqualByComparingTo("600");
        assertThat(result.latest().rate()).isNull();
    }

    @Test
    void noSettledPeriodDoesNotQueryAnEmptySqlInList() {
        FactSlice slice = mock(FactSlice.class);
        when(slice.closedPeriodIds()).thenReturn(List.of());

        var result = service.reportSavings(slice, 12);

        assertThat(result.filledPeriods()).isZero();
        assertThat(result.latest().rate()).isNull();
        verifyNoInteractions(pmc, facts, expenses);
    }
}
