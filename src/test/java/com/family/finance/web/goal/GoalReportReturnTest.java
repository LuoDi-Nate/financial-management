package com.family.finance.web.goal;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.goal.Goal;
import com.family.finance.service.goal.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoalReportReturnTest {
    private void check(boolean retirement) {
        var controller = mock(GoalController.class, CALLS_REAL_METHODS);
        var goals = mock(GoalService.class);
        var progress = mock(GoalProgressService.class);
        var reports = mock(GoalReportService.class);
        ReflectionTestUtils.setField(controller, "goalService", goals);
        ReflectionTestUtils.setField(controller, "progressService", progress);
        ReflectionTestUtils.setField(controller, "goalReportService", reports);
        var me = mock(MemberPrincipal.class);
        when(me.getFamilyId()).thenReturn(7L);
        var goal = new Goal();
        when(goals.require(7, 3)).thenReturn(goal);
        var gp = mock(GoalProgressService.GoalProgress.class);
        when(gp.isNewRetirement()).thenReturn(retirement);
        when(progress.compute(7, goal)).thenReturn(gp);
        var ra = new RedirectAttributesModelMap();
        when(reports.generateNowResult(7, 3)).thenReturn(GoalLlmService.AiResult.unavailable("测试保护已拦截"));
        assertThat(controller.generateReport(me, 3, ra))
                .isEqualTo("redirect:/goals/3" + (retirement ? "/retirement" : "") + "#ai-report");
        assertThat(ra.getFlashAttributes()).containsEntry("reportError", "测试保护已拦截");
        when(reports.generateNowResult(7, 3)).thenReturn(GoalLlmService.AiResult.ok("合成月报", null));
        assertThat(controller.generateReport(me, 3, ra)).endsWith("#ai-report");
        assertThat(ra.getFlashAttributes()).containsEntry("reportNote", "目标月报已更新。");
    }

    @Test void newRetirementReturnsDirectlyToInsightsAndPreservesFeedback() { check(true); }
    @Test void originalGoalKeepsItsOwnDetailPageAndInsightsAnchor() { check(false); }
}
