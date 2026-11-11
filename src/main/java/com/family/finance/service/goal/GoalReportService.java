package com.family.finance.service.goal;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalAiReport;
import com.family.finance.repository.GoalAiReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 目标 AI 月报 + 偏离预警 · v0.3 FR-53b/c。
 *
 * <p>由 PeriodCloseService 异步调用 · 失败不阻塞主流程(@Async + try-catch)。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoalReportService {

    private final GoalService goalService;
    private final GoalProgressService progressService;
    private final GoalLlmService llmService;
    private final GoalAiReportMapper reportMapper;

    /**
     * FR-53b · 周期关闭后异步生成全部 active 目标的月报。
     */
    @Async
    public void generateMonthlyReportsAsync(long familyId, long periodId) {
        try {
            List<Goal> goals = goalService.findActiveByFamily(familyId);
            for (Goal g : goals) {
                try {
                    var progress = progressService.compute(familyId, g);
                    var r = llmService.generateMonthlyReport(familyId, g, progress);
                    if (r.ok()) {
                        GoalAiReport report = GoalAiReport.builder()
                            .goalId(g.getId())
                            .periodId(periodId)
                            .reportType("MONTHLY")
                            .content(r.value())
                            .validatorStatus("PASS")
                            .promptRecordId(r.promptRecordId())
                            .contextFingerprint(contextFingerprint(progress.retirement(), progress.forecast()))
                            .build();
                        reportMapper.upsertOwned(familyId, report);
                        log.info("goal={} monthly report generated · period={}", g.getId(), periodId);
                    } else {
                        log.info("goal={} monthly report unavailable · {}", g.getId(), r.error());
                    }
                } catch (Exception inner) {
                    log.warn("goal={} monthly report failed: {}", g.getId(), inner.toString());
                }
            }
        } catch (Exception e) {
            log.warn("monthly reports batch failed · family={} err={}", familyId, e.toString());
        }
    }

    /**
     * 手动触发单目标月报生成(同步 · 供 UI "立即生成"按钮触发)。
     *
     * <p>period_id = 0 作为按需生成标记(非周期关闭自动触发)·
     * goal_ai_report 表无 FK 约束 · UNIQUE(goal_id, period_id, report_type) upsert 幂等。</p>
     *
     * @return true 生成成功 · false LLM 不可用或失败
     */
    public boolean generateNow(long familyId, long goalId) {
        return generateNowResult(familyId, goalId).ok();
    }

    public GoalLlmService.AiResult<String> generateNowResult(long familyId, long goalId) {
        try {
            Goal g = goalService.require(familyId, goalId);
            var progress = progressService.compute(familyId, g);
            var r = llmService.generateMonthlyReport(familyId, g, progress);
            if (r.ok()) {
                GoalAiReport report = GoalAiReport.builder()
                    .goalId(goalId)
                    .periodId(0L)
                    .reportType("MONTHLY")
                    .content(r.value())
                    .validatorStatus("PASS")
                    .promptRecordId(r.promptRecordId())
                    .contextFingerprint(contextFingerprint(progress.retirement(), progress.forecast()))
                    .build();
                reportMapper.upsertOwned(familyId, report);
                log.info("goal={} monthly report generated on-demand", goalId);
                return r;
            } else {
                log.info("goal={} monthly report unavailable · {}", goalId, r.error());
                return r;
            }
        } catch (Exception e) {
            log.warn("goal={} monthly report on-demand failed: {}", goalId, e.toString());
            return GoalLlmService.AiResult.unavailable("月报生成失败,原报告已保留,请稍后重试。");
        }
    }

    /**
     * FR-53c · 偏离预警检测 + 生成(周期关闭后异步触发)。
     *
     * 触发条件(任一):
     *   1. 中性达成日比上期延后 > 24 个月
     *   2. 月储蓄能力 < 历史中位 × 50%(简化:只看当前 monthlyContribution / 中位)
     *   3. 进度环比下降 > 5pct
     * 节流:90 天内同 goal 已 ALERT 跳过。
     */
    @Async
    public void checkAndAlertAsync(long familyId, long periodId) {
        try {
            List<Goal> goals = goalService.findActiveByFamily(familyId);
            for (Goal g : goals) {
                try {
                    if (reportMapper.countRecentAlerts(familyId, g.getId()) > 0) {
                        log.debug("goal={} alert throttled (90 days)", g.getId());
                        continue;
                    }
                    var progress = progressService.compute(familyId, g);
                    String reason = detectAlertReason(progress);
                    if (reason == null) continue;
                    var r = llmService.generateAlertAdvice(familyId, g, progress, reason);
                    if (r.ok()) {
                        GoalAiReport report = GoalAiReport.builder()
                            .goalId(g.getId())
                            .periodId(periodId)
                            .reportType("ALERT")
                            .content(r.value())
                            .validatorStatus("PASS")
                            .promptRecordId(r.promptRecordId())
                            .contextFingerprint(contextFingerprint(progress.retirement(), progress.forecast()))
                            .build();
                        reportMapper.upsertOwned(familyId, report);
                        log.info("goal={} alert generated · reason={}", g.getId(), reason);
                    }
                } catch (Exception inner) {
                    log.warn("goal={} alert failed: {}", g.getId(), inner.toString());
                }
            }
        } catch (Exception e) {
            log.warn("alerts batch failed · family={} err={}", familyId, e.toString());
        }
    }

    /** Same saved context for report writes and the detail page's historical label. */
    public static String contextFingerprint(RetirementPlanService.View retirement, GoalForecastService.View forecast) {
        if (retirement == null) return null;
        String context = GoalLlmService.forecastContext(forecast);
        if (context.isEmpty()) return retirement.fingerprint();
        try {
            byte[] bytes = (retirement.fingerprint() + context).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 简化版偏离检测:历史进度对比尚未保存,仅检查当前月储蓄能力。 */
    private String detectAlertReason(GoalProgressService.GoalProgress p) {
        if (p.monthlyContribution() != null && p.monthlyContribution().signum() < 0) {
            return "近期月度支出 > 收入 · 储蓄能力为负";
        }
        // v0.3 简化:只在月储蓄能力 < 3000 时预警
        if (p.monthlyContribution() != null
                && p.monthlyContribution().compareTo(new BigDecimal("3000")) < 0
                && !p.targetReached()) {
            return "月储蓄能力偏低(¥" + p.monthlyContribution().setScale(0) + ")· 目标进度可能滞后";
        }
        return null;
    }
}
