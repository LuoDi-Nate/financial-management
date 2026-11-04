package com.family.finance.service.goal;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.service.FamilyService;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.checkup.llm.OutputValidator;
import com.family.finance.service.checkup.llm.PromptBuilder;
import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标场景 LLM · v0.3 FR-53a/b/c。
 *
 * <p>复用 v0.2 LLM 接入 + PromptBuilder 真名脱敏;v1.13 起主备编排统一走 {@link LlmRouter}。
 * 3 个场景:</p>
 * <ul>
 *   <li>FR-53a recommendParams · 目标创建向导 · 用户 click [🤖 AI 推荐] 时实时调用</li>
 *   <li>FR-53b generateMonthlyReport · 周期关闭后异步生成 · 写 goal_ai_report 表</li>
 *   <li>FR-53c generateAlertAdvice · 偏离预警 · 90 天节流</li>
 * </ul>
 *
 * <p>全部失败容忍 · 走 {@link AiResult#unavailable} · 上游显占位文案。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoalLlmService {

    private final LlmRouter llmRouter;
    private final FamilyService familyService;
    /**
     * v1.15 FR-382 · 脱敏映射必须**含已归档成员**。
     * 只拿活跃列表的话,已归档成员的真名不在映射表里 → 替换不掉 → 真名原样进 LLM prompt。
     * 这不是"名字显示不全"那种体感问题,是隐私红线({@code PrivacyIsolationTest} 守着)。
     */
    private final MemberDirectory memberDirectory;
    private final FactViewService factViewService;
    private final ObjectMapper objectMapper;

    /** v1.27 · 分析偏好(FR-851 目标 AI 也读)· 可缺:测试用的构造器不带它 = 没有偏好 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.analysis.AnalysisPreferenceService preferenceService;

    /** v1.28 · 发出去了、我们自己的校验没收时补记(字段注入,单测构造不带它) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PromptRecorder promptRecorder;

    /** 偏好段(放在材料之后,原文过真名映射);没有偏好 → 空串,提示词不变 */
    private String preferenceSuffix(long familyId, PromptBuilder.NameMapping mapping) {
        if (preferenceService == null) return "";
        String b = com.family.finance.service.analysis.AnalysisPromptBlocks.preferencesOnly(
                preferenceService.enabledTexts(familyId), mapping.realToCodename());
        return b.isBlank() ? "" : "\n\n" + b;
    }

    // ---------- FR-53a 目标向导 AI ----------

    /**
     * 给指定类型推荐参数 · 返回 GoalParams(用户在表单上看到默认值后可调整再保存)。
     */
    public AiResult<GoalParams> recommendParams(long familyId, GoalType type) {
        try {
            KpiSnapshot kpis = factViewService.kpis(factViewService.loadDefault(familyId));
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 严格按以下规则输出:
                1. 只输出 JSON 对象 · 不要 markdown 包裹 · 不要解释
                2. 字段范围必须合理(年龄 18-80 / 月支出 1000-100000 / 比率 0.0-0.1)
                3. 不要使用真名 · 不要使用具体产品名 · 不要担保性词汇(保证 / 稳赚 / 一定)
                """;
            String user = buildRecommendPrompt(type, kpis, members, mapping) + preferenceSuffix(familyId, mapping);
            PromptTrace trace = trace(PromptSurface.GOAL_PARAMS, familyId, mapping);
            String raw = invokeWithFailover(familyId, trace, system, user);
            if (raw == null) return AiResult.<GoalParams>unavailable(trace.failureMessage()).withRecord(trace.recordId());

            GoalParams params = parseRecommendation(type, raw);
            if (params == null) {
                rejected(familyId, trace, "回答解析不出参数");
                return AiResult.<GoalParams>unavailable("LLM 输出解析失败").withRecord(trace.recordId());
            }
            return AiResult.ok(params, extractRationale(raw)).withRecord(trace.recordId());
        } catch (Exception e) {
            log.warn("recommendParams failed · type={} family={}: {}", type, familyId, e.toString());
            return AiResult.unavailable("目标参数暂时无法生成,请稍后重试。");
        }
    }

    // ---------- FR-53b 月报 ----------

    /**
     * 给目标生成本周期月报叙事 · 150-300 字。
     */
    public AiResult<String> generateMonthlyReport(long familyId, Goal goal,
                                                  GoalProgressService.GoalProgress progress) {
        try {
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 撰写本月目标进度叙事:
                1. 150-300 字 · 一段话或最多 3 段
                2. 不使用真名(用 成员A / 成员B 代号)· 不担保 · 不推荐具体产品
                3. 聚焦本月进度变化 + 节奏点评 + 1 个可执行建议
                """;
            String user = buildMonthlyReportPrompt(goal, progress) + preferenceSuffix(familyId, mapping);
            PromptTrace trace = trace(PromptSurface.GOAL_REPORT, familyId, mapping);
            String raw = invokeWithFailover(familyId, trace, system, user);
            if (raw == null) return AiResult.<String>unavailable(trace.failureMessage()).withRecord(trace.recordId());

            // 先在 raw(仍是代号)上过校验:不会因真名误判泄露
            OutputValidator.Result valid = OutputValidator.check(raw, mapping.realToCodename().keySet());
            if (!valid.accepted()) {
                log.warn("monthly report rejected by validator: {}", valid.reason());
                rejected(familyId, trace, valid.reason());
                return AiResult.<String>unavailable("AI 输出未通过校验:" + valid.reason()).withRecord(trace.recordId());
            }
            // v0.5.4 修 · 反向映射 成员A/成员B → 真名供用户阅读(原 v0.3 漏做 → 月报里出现「成员A与成员B」)
            String mapped = PromptBuilder.reverseMapping(raw.trim(), mapping.codenameToReal());
            return AiResult.ok(mapped, null).withRecord(trace.recordId());
        } catch (Exception e) {
            log.warn("generateMonthlyReport failed: {}", e.toString());
            return AiResult.unavailable("目标月报生成失败,原报告已保留,请稍后重试。");
        }
    }

    // ---------- FR-53c 偏离预警 ----------

    public AiResult<String> generateAlertAdvice(long familyId, Goal goal,
                                                GoalProgressService.GoalProgress progress,
                                                String alertReason) {
        try {
            List<Member> members = memberDirectory.listAll(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 撰写目标偏离预警:
                1. 200-300 字 · 朱印感(严肃但不焦虑)
                2. 不使用真名 · 不担保 · 不推荐具体产品
                3. 给出具体调整方案 1-2 条(增加月供 / 调整账户配置 / 重设目标参数)
                """;
            String user = buildAlertPrompt(goal, progress, alertReason) + preferenceSuffix(familyId, mapping);
            PromptTrace trace = trace(PromptSurface.GOAL_ALERT, familyId, mapping);
            String raw = invokeWithFailover(familyId, trace, system, user);
            if (raw == null) return AiResult.<String>unavailable(trace.failureMessage()).withRecord(trace.recordId());

            OutputValidator.Result valid = OutputValidator.check(raw, mapping.realToCodename().keySet());
            if (!valid.accepted()) {
                rejected(familyId, trace, valid.reason());
                return AiResult.<String>unavailable("AI 输出未通过校验:" + valid.reason()).withRecord(trace.recordId());
            }
            // v0.5.4 修 · 同月报:反向映射 成员A/成员B → 真名供用户阅读
            String mapped = PromptBuilder.reverseMapping(raw.trim(), mapping.codenameToReal());
            return AiResult.ok(mapped, null).withRecord(trace.recordId());
        } catch (Exception e) {
            log.warn("generateAlertAdvice failed: {}", e.toString());
            return AiResult.unavailable("目标预警暂时无法生成,请稍后重试。");
        }
    }

    // ---------- 内部 ----------

    /**
     * v1.13 修:这里原本是<b>裸遍历</b> {@code List<LlmClient>} —— 没排序,永远按 Spring 的 {@code @Order}
     * 先打百炼,管理页把主选改成 DeepSeek 对目标 AI(向导推荐 / 月报 / 偏离预警)<b>完全无效</b>。
     * 现在顺序只能来自配置,由 {@link LlmRouter} 统一编排。
     */
    private String invokeWithFailover(long familyId, PromptTrace trace, String systemPrompt, String userPrompt) {
        return llmRouter.invoke(familyId, trace, systemPrompt, userPrompt)
                .map(LlmRouter.Outcome::text).orElse(null);
    }

    /** v1.28 · 这一次调用的随行说明:用在哪 + 成员代号对照 + 有没有用分析偏好 */
    private PromptTrace trace(PromptSurface surface, long familyId, PromptBuilder.NameMapping mapping) {
        int prefs = preferenceService == null ? 0 : preferenceService.enabledTexts(familyId).size();
        return PromptTrace.of(surface).legend(mapping.codenameToReal())
                .settings(prefs == 0 ? null : "分析偏好 " + prefs + " 条");
    }

    /** v1.28 · 发出去了、我们自己的校验没收 → 面板上写「回答没采用」 */
    private void rejected(long familyId, PromptTrace trace, String reason) {
        if (promptRecorder != null) promptRecorder.markRejected(familyId, trace.recordId(), reason);
    }

    private String buildRecommendPrompt(GoalType type, KpiSnapshot kpis,
                                        List<Member> members, PromptBuilder.NameMapping mapping) {
        String memberInfo = members.stream()
            .limit(3)
            .map(m -> mapping.realToCodename().getOrDefault(m.getDisplayName(), m.getDisplayName())
                    + "(" + (m.getRoleLabel() == null ? "成员" : m.getRoleLabel()) + ")")
            .reduce((a, b) -> a + "、" + b).orElse("(无)");

        String kpiSummary = "净资产 ¥" + (kpis.netWorth() == null ? "?" : kpis.netWorth().setScale(0, RoundingMode.HALF_UP).toPlainString())
                          + " · 总资产 ¥" + (kpis.totalAssets() == null ? "?" : kpis.totalAssets().setScale(0, RoundingMode.HALF_UP).toPlainString())
                          + " · 总负债 ¥" + (kpis.totalLiabilities() == null ? "?" : kpis.totalLiabilities().setScale(0, RoundingMode.HALF_UP).toPlainString());

        return switch (type) {
            case RETIREMENT -> """
                家庭基础:%s · %s
                请为 [退休 / FIRE] 目标推荐合理参数 · 输出严格 JSON:
                {"retire_age": 60, "current_age": 38, "monthly_expense": 15000, "inflation_rate": 0.025, "withdrawal_rate": 0.04, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
            case EDUCATION -> """
                家庭基础:%s · %s
                请为 [子女教育金] 目标推荐合理参数 · 输出严格 JSON:
                {"child_birth_year": 2020, "target_year_offset": 18, "target_amount": 800000, "inflation_rate": 0.03, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
            case EMERGENCY -> """
                家庭基础:%s · %s
                请为 [应急储备] 目标推荐合理参数 · 输出严格 JSON:
                {"months_target": 6, "auto_baseline": true, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
            case CUSTOM -> ""; // v0.16:自定义追踪目标无参数 AI 推荐(目标值用户直填)
        };
    }

    private String buildMonthlyReportPrompt(Goal goal, GoalProgressService.GoalProgress p) {
        // v0.16 · 自定义追踪目标:按指标 / 当前·目标 / 达标率 / 倒计时 / pace 组 prompt(预设仍走下方)
        if (goal.getGoalType() == GoalType.CUSTOM) {
            String unit = p.isRate() ? "%" : "元";
            String metricLabel = p.metric() != null ? p.metric().label() : "金额";
            String timeInfo = p.daysLeft() != null ? ("还剩 " + p.daysLeft() + " 天") : "长期(不设期限)";
            String paceInfo = switch (p.paceStatus() == null ? "NONE" : p.paceStatus()) {
                case "AHEAD" -> "进度领先时间"; case "BEHIND" -> "进度落后时间";
                case "ACHIEVED" -> "已达成"; default -> "追踪中"; };
            return """
                目标:%s(自定义追踪)%s
                追踪指标:%s · 当前 %s%s / 目标 %s%s · 达标率 %.0f%%
                时间:%s · 进度状态:%s
                请生成本月叙事(150-300 字)· 结合达标率与时间进度点评 + 1 条可执行建议 · 不担保、不荐产品、不预测涨跌。
                """.formatted(goal.getName(),
                    goal.getDescription() == null || goal.getDescription().isBlank() ? "" : (" · 备注:" + goal.getDescription()),
                    metricLabel,
                    p.pv() == null ? "?" : p.pv().setScale(0, RoundingMode.HALF_UP).toPlainString(), unit,
                    p.target() == null ? "?" : p.target().setScale(0, RoundingMode.HALF_UP).toPlainString(), unit,
                    p.progress() == null ? 0d : p.progress().movePointRight(2).doubleValue(),
                    timeInfo, paceInfo);
        }
        BigDecimal pv = p.pv();
        BigDecimal target = p.target();
        BigDecimal progress = p.progress() == null ? BigDecimal.ZERO : p.progress();
        return """
            目标:%s(类型 %s)
            当前进度:%.0f%% · 已积累 ¥%s / 目标 ¥%s
            月储蓄能力(6 期中位):¥%s
            中性情景达成日:%s

            请生成本月叙事(150-300 字)· 含进度变化点评 + 1 条可执行建议。
            """.formatted(goal.getName(), goal.getGoalType(),
                progress.movePointRight(2).doubleValue(),
                pv == null ? "?" : pv.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                target == null ? "?(应急 derived)" : target.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                p.monthlyContribution() == null ? "?" : p.monthlyContribution().setScale(0, RoundingMode.HALF_UP).toPlainString(),
                p.neutralDate() == null ? "未达成范围" : p.neutralDate().toString());
    }

    private String buildAlertPrompt(Goal goal, GoalProgressService.GoalProgress p, String reason) {
        return """
            目标:%s(类型 %s)· 当前进度 %.0f%%
            预警原因:%s

            请生成偏离预警 + 调整建议(200-300 字)· 不担保 · 不推荐具体产品。
            """.formatted(goal.getName(), goal.getGoalType(),
                p.progress() == null ? 0d : p.progress().movePointRight(2).doubleValue(),
                reason);
    }

    /**
     * 从 LLM 原文中提取 JSON 对象 · 容忍 markdown ```json 包裹 / 前后多余空白。
     */
    private GoalParams parseRecommendation(GoalType type, String raw) {
        String json = extractJson(raw);
        if (json == null) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            GoalParams p = new GoalParams();
            if (type == GoalType.RETIREMENT) {
                if (node.hasNonNull("retire_age")) p.setRetireAge(node.get("retire_age").asInt());
                if (node.hasNonNull("current_age")) p.setCurrentAge(node.get("current_age").asInt());
                if (node.hasNonNull("monthly_expense")) p.setMonthlyExpense(new BigDecimal(node.get("monthly_expense").asText()));
                if (node.hasNonNull("inflation_rate")) p.setInflationRate(new BigDecimal(node.get("inflation_rate").asText()));
                if (node.hasNonNull("withdrawal_rate")) p.setWithdrawalRate(new BigDecimal(node.get("withdrawal_rate").asText()));
                if (!validRetirement(p)) return null;
            } else if (type == GoalType.EDUCATION) {
                if (node.hasNonNull("child_birth_year")) p.setChildBirthYear(node.get("child_birth_year").asInt());
                if (node.hasNonNull("target_year_offset")) p.setTargetYearOffset(node.get("target_year_offset").asInt());
                if (node.hasNonNull("target_amount")) p.setTargetAmount(new BigDecimal(node.get("target_amount").asText()));
                if (node.hasNonNull("inflation_rate")) p.setInflationRate(new BigDecimal(node.get("inflation_rate").asText()));
                if (!validEducation(p)) return null;
            } else { // EMERGENCY
                if (node.hasNonNull("months_target")) p.setMonthsTarget(node.get("months_target").asInt());
                if (node.hasNonNull("auto_baseline")) p.setAutoBaseline(node.get("auto_baseline").asBoolean(true));
                if (!validEmergency(p)) return null;
            }
            return p;
        } catch (Exception e) {
            log.warn("parseRecommendation failed: {}", e.toString());
            return null;
        }
    }

    private static final Pattern JSON_RE = Pattern.compile("\\{[\\s\\S]*\\}");

    private static String extractJson(String raw) {
        if (raw == null) return null;
        // 移除 ```json ... ``` 包裹
        String cleaned = raw.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("```", "");
        Matcher m = JSON_RE.matcher(cleaned);
        return m.find() ? m.group() : null;
    }

    private String extractRationale(String raw) {
        try {
            String json = extractJson(raw);
            if (json == null) return null;
            JsonNode n = objectMapper.readTree(json);
            return n.hasNonNull("rationale") ? n.get("rationale").asText() : null;
        } catch (Exception e) { return null; }
    }

    private static boolean validRetirement(GoalParams p) {
        return p.getCurrentAge() != null && p.getCurrentAge() >= 18 && p.getCurrentAge() <= 80
            && p.getRetireAge() != null && p.getRetireAge() > p.getCurrentAge() && p.getRetireAge() <= 90
            && p.getMonthlyExpense() != null && p.getMonthlyExpense().compareTo(new BigDecimal("1000")) >= 0
                                              && p.getMonthlyExpense().compareTo(new BigDecimal("1000000")) <= 0;
    }
    private static boolean validEducation(GoalParams p) {
        return p.getTargetAmount() != null && p.getTargetAmount().compareTo(new BigDecimal("10000")) >= 0
                                            && p.getTargetAmount().compareTo(new BigDecimal("10000000")) <= 0;
    }
    private static boolean validEmergency(GoalParams p) {
        return p.getMonthsTarget() != null && p.getMonthsTarget() >= 1 && p.getMonthsTarget() <= 24;
    }

    /**
     * AI 调用结果 · 成功携带 value · 失败携带原因。
     */
    public record AiResult<T>(boolean ok, T value, String rationale, String error, Long promptRecordId) {
        /** v1.28 · 四参老构造(不带记录编号) */
        public AiResult(boolean ok, T value, String rationale, String error) {
            this(ok, value, rationale, error, null);
        }
        /** v1.28 · 带上「这份结果是哪一次调用生成的」 */
        public AiResult<T> withRecord(Long id) {
            return new AiResult<>(ok, value, rationale, error, id);
        }
        public static <T> AiResult<T> ok(T value, String rationale) {
            return new AiResult<>(true, value, rationale, null);
        }
        public static <T> AiResult<T> unavailable(String error) {
            return new AiResult<>(false, null, null, error);
        }
    }
}
