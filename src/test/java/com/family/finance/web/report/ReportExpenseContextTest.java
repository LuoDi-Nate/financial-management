package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportExpenseContextTest {
    @Mock PeriodMapper periods;
    @Mock CashFlowMapper flows;
    @Mock FamilyMapper families;
    @InjectMocks ReportsController controller;

    private Period period(int month) {
        var p = new Period();
        p.setId((long) month);
        p.setPeriodStart(LocalDate.of(2026, month, 1));
        p.setStatus(PeriodStatus.CLOSED);
        return p;
    }

    private MemberPrincipal me() {
        var me = mock(MemberPrincipal.class);
        when(me.getFamilyId()).thenReturn(1L);
        when(periods.findLatestSettledAsOf(eq(1L), any())).thenReturn(Optional.of(period(10)));
        return me;
    }

    @Test
    void threeMonthDetailUsesSelectedHistoricalAnchorAndSameRange() {
        var me = me();
        when(periods.findAllByFamily(1L)).thenReturn(List.of(period(5), period(6), period(10)));
        when(periods.findRecentAsOf(1L, LocalDate.of(2026, 6, 1), 3))
                .thenReturn(List.of(period(6), period(5), period(3)));

        String view = controller.expenseMixDetail(me, "category", "consumption", 3,
                "3M", "2026-06-01", null, null, new ExtendedModelMap());

        assertThat(view).isEqualTo("reports/_expense-section :: detail");
        verify(flows).expenseBreakdownDetail(1L, List.of(6L, 5L), "category", "consumption");
    }

    @Test
    void futureAnchorCannotPullOpenMonthIntoSealedReport() {
        var me = me();
        when(periods.findAllByFamily(1L)).thenReturn(List.of(period(10), period(11)));
        when(periods.findRecentAsOf(1L, LocalDate.of(2026, 10, 1), 1)).thenReturn(List.of(period(10)));

        controller.expenseMixDetail(me, "category", "consumption", null,
                "1M", "2026-11-01", null, null, new ExtendedModelMap());

        verify(flows).expenseBreakdownDetail(1L, List.of(10L), "category", "consumption");
    }
}
