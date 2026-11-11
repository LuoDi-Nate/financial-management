package com.family.finance.service.goal;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.FactViewService;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.service.FamilyService;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v0.5.4 · 目标 AI 月报脱敏回写守护。
 *
 * <p>回归点:LLM 输出用「成员A / 成员B」代号,展示前必须 reverseMapping 回真名 ——
 * 原 v0.3 漏做导致月报里出现「建议成员A与成员B共同核查」。同时校验仍跑在代号 raw 上
 * (不因真名误判泄露)。</p>
 */
class GoalLlmServiceTest {

    /**
     * 固定返回带代号文案的假路由(v1.13:业务侧不再自己遍历 client,只认 {@link LlmRouter})。
     */
    private static LlmRouter stubRouter(String reply) {
        LlmRouter router = mock(LlmRouter.class);
        LlmInvocation used = new LlmInvocation(LlmCatalog.P_DASHSCOPE, "qwen", "qwen-plus");
        when(router.invoke(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new LlmRouter.Outcome(reply, used)));
        // v1.28 · 业务侧改走带 PromptTrace 的重载(记下发出去的内容)
        when(router.invoke(anyLong(), org.mockito.ArgumentMatchers.any(com.family.finance.service.llmtrace.PromptTrace.class),
                anyString(), anyString()))
                .thenReturn(Optional.of(new LlmRouter.Outcome(reply, used)));
        return router;
    }

    private static Member member(long id, String name) {
        Member m = new Member();
        m.setId(id);
        m.setDisplayName(name);
        return m;
    }

    @Test
    void monthlyReport_reverseMaps_codenames_to_real_names() {
        // ≥150 字(月报口径)· 含 成员A/成员B 代号 + 金融术语 · 无担保词/产品名
        LlmRouter stub = stubRouter(
                "本月家庭资产保持稳健,投资收益较上期小幅回暖,整体风险敞口处于合理区间,流动性也较为充足。"
                + "目标进度按计划稳步推进,储蓄节奏总体稳定,与长期目标之间的差距正在缓慢缩小。"
                + "建议成员A与成员B在本月内共同核查近三个月的消费结构与支出明细,适度提高每月的储蓄比例,"
                + "并一起复盘当前的资产配置是否与长期目标的时间表相匹配,必要时对部分仓位做再平衡,"
                + "以巩固后续的收益质量与整体抗风险能力。");
        MemberDirectory memberDirectory = mock(MemberDirectory.class);
        when(memberDirectory.listAll(1L)).thenReturn(List.of(member(1L, "张三"), member(2L, "李四")));

        GoalLlmService svc = new GoalLlmService(
                stub, mock(FamilyService.class), memberDirectory,
                mock(FactViewService.class), new ObjectMapper());

        Goal goal = Goal.builder().id(1L).familyId(1L).goalType(GoalType.RETIREMENT).name("退休").build();
        var progress = new GoalProgressService.GoalProgress(
                goal, null, new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("0.20"), new BigDecimal("5000"), null);

        var r = svc.generateMonthlyReport(1L, goal, progress);

        assertThat(r.ok()).isTrue();
        assertThat(r.value())
                .contains("张三")
                .contains("李四")
                .doesNotContain("成员A")
                .doesNotContain("成员B");
    }

    @Test
    void monthlyReport_without_members_keeps_text_unchanged() {
        // 无成员(空映射)· reverseMapping 应原样返回(不崩)
        String reply =
                "本月家庭资产保持稳健,投资收益较上期小幅回暖,整体风险敞口处于合理区间,流动性也较为充足。"
                + "目标进度按计划稳步推进,储蓄节奏总体稳定。建议继续保持当前的储蓄与投资节奏,"
                + "定期复盘资产配置是否与长期目标的时间表相匹配,必要时对部分仓位做再平衡,"
                + "以巩固后续的收益质量与整体抗风险能力,稳步缩小与目标之间的差距。";
        LlmRouter stub = stubRouter(reply);
        MemberDirectory memberDirectory = mock(MemberDirectory.class);
        when(memberDirectory.listAll(1L)).thenReturn(List.of());

        GoalLlmService svc = new GoalLlmService(
                stub, mock(FamilyService.class), memberDirectory,
                mock(FactViewService.class), new ObjectMapper());

        Goal goal = Goal.builder().id(1L).familyId(1L).goalType(GoalType.RETIREMENT).name("退休").build();
        var progress = new GoalProgressService.GoalProgress(
                goal, null, new BigDecimal("100000"), new BigDecimal("500000"),
                new BigDecimal("0.20"), new BigDecimal("5000"), null);

        var r = svc.generateMonthlyReport(1L, goal, progress);

        assertThat(r.ok()).isTrue();
        assertThat(r.value()).isEqualTo(reply);
    }
    @Test void monthlyReportUsesAvailableGoalProgressRatherThanTheLivingPrincipalState() {
        String reply="本月家庭资产保持稳健,投资收益较上期小幅回暖,整体风险敞口处于合理区间,流动性也较为充足。"
                + "目标进度按计划稳步推进,储蓄节奏总体稳定。建议继续保持当前的储蓄与投资节奏,"
                + "定期复盘资产配置是否与长期目标的时间表相匹配,必要时对部分仓位做再平衡,"
                + "以巩固后续的收益质量与整体抗风险能力,稳步缩小与目标之间的差距。";
        var router=stubRouter(reply);var members=mock(MemberDirectory.class);
        when(members.listAll(1L)).thenReturn(List.of());
        var service=new GoalLlmService(router,mock(FamilyService.class),members,mock(FactViewService.class),new ObjectMapper());
        var goal=Goal.builder().id(1L).familyId(1L).goalType(GoalType.RETIREMENT).name("退休").build();
        var plan=new com.family.finance.domain.goal.RetirementPlan();plan.setVersion(2);plan.setBaseCurrency("CNY");
        var view=mock(RetirementPlanService.View.class);var result=mock(com.family.finance.calc.RetirementCalculator.Result.class);
        when(view.plan()).thenReturn(plan);when(view.result()).thenReturn(result);
        when(view.progressAssets()).thenReturn(new BigDecimal("5000000"));when(view.progressTarget()).thenReturn(new BigDecimal("10000000"));
        when(view.returnBasis()).thenReturn("规划假设 · 小Lin 2.5% 实际回报");
        when(result.ready()).thenReturn(false);when(result.aiSummary()).thenReturn("所选账户资产A=5000000 · 所需总资金F=10000000 · 目标进度比率=0.5");
        var progress=new GoalProgressService.GoalProgress(goal,null,new BigDecimal("5000000"),new BigDecimal("10000000"),
                new BigDecimal("0.5"),null,null,null,null,null,null,1,view);
        var calculated = com.family.finance.calc.GoalCashflowCalculator.calculate(java.time.LocalDate.of(2026,11,11),
                progress.pv(), progress.target(), new BigDecimal("40000"), null);
        progress = progress.withForecast(new GoalForecastService.View(true,calculated,"CNY","MANUAL","本目标手填",null,
                new com.family.finance.domain.goal.GoalForecastForm(),java.util.Map.of(),java.util.List.of()));
        assertThat(service.generateMonthlyReport(1,goal,progress).ok()).isTrue();
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(router).invoke(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(com.family.finance.service.llmtrace.PromptTrace.class),anyString(),prompt.capture());
        assertThat(prompt.getValue()).contains("回报来源=规划假设 · 小Lin 2.5% 实际回报", "2037年04月", "125", "本目标手填");
        when(view.progressTarget()).thenReturn(null);
        assertThat(service.generateMonthlyReport(1,goal,progress).ok()).isFalse();
    }
}
