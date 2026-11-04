package com.family.finance.web.goal;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.goal.TimeMode;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.GoalAiReportMapper;
import com.family.finance.repository.MemberMapper;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import com.family.finance.service.NavService;
import com.family.finance.service.goal.GoalLlmService;
import com.family.finance.service.goal.GoalProgressService;
import com.family.finance.service.goal.GoalProgressService.GoalProgress;
import com.family.finance.service.goal.GoalReportService;
import com.family.finance.service.goal.GoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 财务目标 Web 控制器 · v0.3 FR-50。
 *
 * <p>路由清单:</p>
 * <ul>
 *   <li>GET  /goals               · 列表 + 三情景缩略</li>
 *   <li>GET  /goals/new           · 类型选择页</li>
 *   <li>GET  /goals/new/{type}    · 类型特定向导</li>
 *   <li>POST /goals/new/{type}    · 创建</li>
 *   <li>GET  /goals/{id}          · 详情 + 完整三情景图 + AI 月报</li>
 *   <li>GET  /goals/{id}/edit     · 编辑表单</li>
 *   <li>POST /goals/{id}/edit     · 更新</li>
 *   <li>POST /goals/{id}/archive  · 软删</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final com.family.finance.service.expense.NormalExpenseService normalExpenseService; // v1.24 FR-652
    private final com.family.finance.service.config.FamilyConfigService configService;   // v1.24 FR-652 应急月数
    private final GoalProgressService progressService;
    private final GoalLlmService llmService;
    private final GoalReportService goalReportService;
    private final GoalAiReportMapper aiReportMapper;
    /** 仅活跃:新建教育目标的孩子下拉 + 进度页按 id 单查 */
    private final MemberMapper memberMapper;
    /** v1.15 FR-382 · 编辑表单候选(活跃 ∪ 当前的孩子) */
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final AccountMapper accountMapper;   // v0.16 · 自定义目标账户多选
    private final NavService navService;
    /** v1.28 · 目标 AI(月报 / 预警 / 推荐参数)的 >_(PRD FR-900) */
    private final com.family.finance.service.llmtrace.PromptPeekLinks promptPeek;

    // ---------- 列表 ----------

    @GetMapping("/goals")
    public String list(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        List<GoalProgress> goals = new java.util.ArrayList<>(progressService.computeAll(me.getFamilyId()));
        // FR-16-9 · 截止型按剩余天数升序在前(临期在前),长期型(daysLeft=null)在后
        goals.sort(java.util.Comparator.comparing(g -> g.daysLeft() == null ? Long.MAX_VALUE : g.daysLeft()));
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goals", goals);
        return "goals/index";
    }

    // ---------- 新建 ----------

    @GetMapping("/goals/new")
    public String newGoalChooser(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("nav", navService.load(me));
        return "goals/new";
    }

    @GetMapping("/goals/new/{type}")
    public String newGoalForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable String type,
                              Model model) {
        GoalType goalType = parseType(type);
        if (goalType == GoalType.RETIREMENT) return "redirect:/goals/retirement/new";
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goalType", goalType);
        model.addAttribute("currentYear", java.time.Year.now().getValue());
        if (goalType == GoalType.EDUCATION) {
            // 准备孩子下拉 · displayName 仅 UI 用
            List<Member> children = memberMapper.findActiveByFamily(me.getFamilyId());
            model.addAttribute("childCandidates", children);
        }
        if (goalType == GoalType.EMERGENCY) {
            BigDecimal baseline = progressService.computeEmergencyAutoBaseline(me.getFamilyId());
            model.addAttribute("autoBaseline", baseline);
        }
        return "goals/new-" + goalType.name().toLowerCase(Locale.ROOT);
    }

    @PostMapping("/goals/new/{type}")
    public String createGoal(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable String type,
                             @RequestParam(required = false) String name,
                             @RequestParam(required = false) Integer currentAge,
                             @RequestParam(required = false) Integer retireAge,
                             @RequestParam(required = false) BigDecimal monthlyExpense,
                             @RequestParam(required = false) BigDecimal inflationRate,
                             @RequestParam(required = false) BigDecimal withdrawalRate,
                             @RequestParam(required = false) Long childMemberId,
                             @RequestParam(required = false) Integer childBirthYear,
                             @RequestParam(required = false) Integer targetYearOffset,
                             @RequestParam(required = false) BigDecimal targetAmount,
                             @RequestParam(required = false) Integer monthsTarget,
                             @RequestParam(required = false) Boolean autoBaseline,
                             @RequestParam(required = false) BigDecimal fixedBaseline,
                             @RequestParam(required = false) String expenseMode,
                             @RequestParam(required = false) Integer expenseWindowMonths,
                             @RequestParam(required = false) String expenseSmoothing) {
        GoalType goalType = parseType(type);
        GoalParams params = GoalParams.builder()
            .currentAge(currentAge)
            .retireAge(retireAge)
            .monthlyExpense(monthlyExpense)
            .inflationRate(inflationRate)
            .withdrawalRate(withdrawalRate)
            .childMemberId(childMemberId)
            .childBirthYear(childBirthYear)
            .targetYearOffset(targetYearOffset)
            .targetAmount(targetAmount)
            .monthsTarget(monthsTarget)
            .autoBaseline(autoBaseline)
            .fixedBaseline(fixedBaseline)
            .expenseMode(expenseMode)
            .expenseWindowMonths(expenseWindowMonths)
            .expenseSmoothing(expenseSmoothing)
            .build();
        Goal created = goalService.create(me.getFamilyId(), goalType, name, params);
        // v0.5 FR-82 · AUTO 模式立即派生一次(不必等周期关闭 · 用户建完即见)
        if ("AUTO_MONTHLY".equalsIgnoreCase(expenseMode)) {
            goalService.recomputeAutoExpenseGoals(me.getFamilyId());
        }
        return "redirect:/goals/" + created.getId();
    }

    // ---------- 自定义追踪目标(CUSTOM · v0.16)----------

    @GetMapping("/goals/new/custom")
    public String newCustomForm(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("accounts", accountMapper.findActiveByFamily(me.getFamilyId()));
        model.addAttribute("metrics", GoalMetric.values());
        model.addAttribute("editing", false);
        return "goals/new-custom";
    }

    @PostMapping("/goals/new/custom")
    public String createCustom(@AuthenticationPrincipal MemberPrincipal me,
                               @RequestParam(required = false) String name,
                               @RequestParam(required = false) String description,
                               @RequestParam String metric,
                               @RequestParam(required = false) String comparator,
                               @RequestParam(required = false) BigDecimal targetValue,
                               @RequestParam(required = false, defaultValue = "OPEN") String timeRange,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate customDate,
                               @RequestParam(required = false) List<Long> accountIds) {
        GoalMetric m = GoalMetric.fromOrDefault(metric);
        GoalComparator c = (comparator == null || comparator.isBlank()) ? m.defaultComparator() : GoalComparator.fromOrDefault(comparator);
        Goal g = goalService.createCustom(me.getFamilyId(), name, description, m, c, targetValue,
            timeModeFor(timeRange), deadlineFor(timeRange, customDate), accountIds);
        return "redirect:/goals/" + g.getId();
    }

    @PostMapping("/goals/{id}/edit-custom")
    public String updateCustom(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long id,
                               @RequestParam(required = false) String name,
                               @RequestParam(required = false) String description,
                               @RequestParam String metric,
                               @RequestParam(required = false) String comparator,
                               @RequestParam(required = false) BigDecimal targetValue,
                               @RequestParam(required = false, defaultValue = "OPEN") String timeRange,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate customDate,
                               @RequestParam(required = false) List<Long> accountIds) {
        GoalMetric m = GoalMetric.fromOrDefault(metric);
        GoalComparator c = (comparator == null || comparator.isBlank()) ? m.defaultComparator() : GoalComparator.fromOrDefault(comparator);
        goalService.updateCustom(me.getFamilyId(), id, name, description, m, c, targetValue,
            timeModeFor(timeRange), deadlineFor(timeRange, customDate), accountIds);
        return "redirect:/goals/" + id;
    }

    private static TimeMode timeModeFor(String timeRange) {
        return (timeRange == null || "OPEN".equals(timeRange)) ? TimeMode.OPEN : TimeMode.DEADLINE;
    }
    private static LocalDate deadlineFor(String timeRange, LocalDate customDate) {
        return switch (timeRange == null ? "OPEN" : timeRange) {
            case "ONE_YEAR" -> LocalDate.now().plusYears(1);
            case "THREE_YEAR" -> LocalDate.now().plusYears(3);
            case "FIVE_YEAR" -> LocalDate.now().plusYears(5);
            case "CUSTOM" -> customDate;
            default -> null;
        };
    }

    // ---------- 详情 ----------

    @GetMapping("/goals/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         Model model) {
        Goal goal = goalService.require(me.getFamilyId(), id);
        GoalProgress progress = progressService.compute(me.getFamilyId(), goal);
        if (progress.isNewRetirement()) return "redirect:/goals/" + id + "/retirement";
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goal", goal);
        model.addAttribute("progress", progress);
        // 孩子 displayName(EDUCATION 用)
        if (goal.getGoalType() == GoalType.EDUCATION && progress.params().getChildMemberId() != null) {
            memberMapper.findById(me.getFamilyId(), progress.params().getChildMemberId())
                .ifPresent(m -> model.addAttribute("childMember", m));
        }
        /* v1.24 FR-652 · 应急金目标额按【两种月均】各算一次,并排显示。
         * 【不替用户选】—— 两个数都给,他自己决定按哪个准备。
         * 一次性支出(装修、旅行)进不进应急金的分母,是个价值判断不是技术问题:
         * 有人觉得"那种事还会再来,得备着",有人觉得"那是可选的,不该抬高门槛"。
         * 我们没有立场替他定,所以两个都摆出来。
         * 支出分析关掉 / 没有逐笔数据 → empty → 这一块整个不出现。 */
        if (goal.getGoalType() == GoalType.EMERGENCY) {
            normalExpenseService.normal(me.getFamilyId()).ifPresent(n -> {
                int months = configService.getInt(me.getFamilyId(),
                    com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,
                    com.family.finance.calc.LiquiditySurplus.DEFAULT_EMERGENCY_MONTHS);
                model.addAttribute("normalExpense", n);
                model.addAttribute("emergencyMonthsCfg", months);
                model.addAttribute("targetByAverage",
                    n.averageBase().multiply(BigDecimal.valueOf(months)));
                model.addAttribute("targetByNormal",
                    n.normalBase().multiply(BigDecimal.valueOf(months)));
            });
        }
        // v0.3 FR-53b/c · AI 月报 + 偏离预警(null safe)
        aiReportMapper.findLatestByGoalAndType(me.getFamilyId(), id, "MONTHLY")
            .ifPresent(r -> {
                model.addAttribute("aiMonthlyReport", r);
                model.addAttribute("reportPeekSrc", promptPeek.of(me.getFamilyId(), r.getPromptRecordId(),
                        com.family.finance.service.llmtrace.PromptSurface.GOAL_REPORT, r.getGeneratedAt(), null));
            });
        aiReportMapper.findLatestByGoalAndType(me.getFamilyId(), id, "ALERT")
            .filter(r -> r.getDismissedAt() == null)
            .ifPresent(r -> {
                model.addAttribute("aiAlert", r);
                model.addAttribute("alertPeekSrc", promptPeek.of(me.getFamilyId(), r.getPromptRecordId(),
                        com.family.finance.service.llmtrace.PromptSurface.GOAL_ALERT, r.getGeneratedAt(), null));
            });
        return "goals/detail";
    }

    /**
     * FR-53c · 用户 dismiss 偏离预警 · 30 天内不再弹同类(简化:永久 dismiss · 下次新预警重写覆盖)。
     */
    @PostMapping("/goals/{id}/alert/dismiss")
    public String dismissAlert(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long id) {
        goalService.require(me.getFamilyId(), id);
        aiReportMapper.findLatestByGoalAndType(me.getFamilyId(), id, "ALERT")
            .ifPresent(r -> aiReportMapper.dismiss(me.getFamilyId(), r.getId()));
        return "redirect:/goals/" + id;
    }

    // ---------- 编辑 ----------

    @GetMapping("/goals/{id}/edit")
    public String editForm(@AuthenticationPrincipal MemberPrincipal me,
                           @PathVariable long id,
                           Model model) {
        Goal goal = goalService.require(me.getFamilyId(), id);
        if (goal.getGoalType() == GoalType.RETIREMENT && progressService.compute(me.getFamilyId(), goal).isNewRetirement())
            return "redirect:/goals/" + id + "/retirement?tab=plan";
        if (goal.getGoalType() == GoalType.CUSTOM) {
            model.addAttribute("nav", navService.load(me));
            model.addAttribute("goal", goal);
            model.addAttribute("accounts", accountMapper.findActiveByFamily(me.getFamilyId()));
            model.addAttribute("metrics", GoalMetric.values());
            model.addAttribute("boundIds", goalService.boundAccountIds(me.getFamilyId(), id));
            model.addAttribute("editing", true);
            return "goals/new-custom";
        }
        GoalParams params = goalService.parseParams(goal);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("goal", goal);
        model.addAttribute("params", params);
        model.addAttribute("goalType", goal.getGoalType());
        model.addAttribute("currentYear", java.time.Year.now().getValue());
        if (goal.getGoalType() == GoalType.EDUCATION) {
            // v1.15 FR-382 · 候选 = 活跃 ∪ 当前这个孩子。孩子被归档后如果从下拉里消失,
            // 家长改个目标金额就会把这个教育目标静默改派给另一个孩子。
            model.addAttribute("childCandidates",
                    memberDirectory.selectableWith(me.getFamilyId(), params.getChildMemberId()));
        }
        return "goals/edit";
    }

    @PostMapping("/goals/{id}/edit")
    public String updateGoal(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long id,
                             @RequestParam(required = false) String name,
                             @RequestParam(required = false) Integer currentAge,
                             @RequestParam(required = false) Integer retireAge,
                             @RequestParam(required = false) BigDecimal monthlyExpense,
                             @RequestParam(required = false) BigDecimal inflationRate,
                             @RequestParam(required = false) BigDecimal withdrawalRate,
                             @RequestParam(required = false) Long childMemberId,
                             @RequestParam(required = false) Integer childBirthYear,
                             @RequestParam(required = false) Integer targetYearOffset,
                             @RequestParam(required = false) BigDecimal targetAmount,
                             @RequestParam(required = false) Integer monthsTarget,
                             @RequestParam(required = false) Boolean autoBaseline,
                             @RequestParam(required = false) BigDecimal fixedBaseline,
                             @RequestParam(required = false) String expenseMode,
                             @RequestParam(required = false) Integer expenseWindowMonths,
                             @RequestParam(required = false) String expenseSmoothing) {
        GoalParams params = GoalParams.builder()
            .currentAge(currentAge)
            .retireAge(retireAge)
            .monthlyExpense(monthlyExpense)
            .inflationRate(inflationRate)
            .withdrawalRate(withdrawalRate)
            .childMemberId(childMemberId)
            .childBirthYear(childBirthYear)
            .targetYearOffset(targetYearOffset)
            .targetAmount(targetAmount)
            .monthsTarget(monthsTarget)
            .autoBaseline(autoBaseline)
            .fixedBaseline(fixedBaseline)
            .expenseMode(expenseMode)
            .expenseWindowMonths(expenseWindowMonths)
            .expenseSmoothing(expenseSmoothing)
            .build();
        goalService.update(me.getFamilyId(), id, name, params);
        if ("AUTO_MONTHLY".equalsIgnoreCase(expenseMode)) {
            goalService.recomputeAutoExpenseGoals(me.getFamilyId());
        }
        return "redirect:/goals/" + id;
    }

    // ---------- AI 推荐(FR-53a)----------

    /**
     * AI 推荐目标参数 · 用户在 /goals/new/{type} 表单点 [🤖 AI 推荐] 时调用。
     * 返回 JSON · 前端 JS 把字段填入对应 input。
     */
    @PostMapping("/goals/advise/{type}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> adviseParams(@AuthenticationPrincipal MemberPrincipal me,
                                                            @PathVariable String type) {
        GoalType goalType = parseType(type);
        if (goalType == GoalType.RETIREMENT)
            return ResponseEntity.ok(Map.of("ok", false, "error", "退休计划的预算、预留和回报假设由家庭确认,不交给 AI 代填。"));
        GoalLlmService.AiResult<com.family.finance.domain.goal.GoalParams> r =
            llmService.recommendParams(me.getFamilyId(), goalType);
        if (!r.ok()) {
            return ResponseEntity.ok(Map.of("ok", false, "error", r.error() == null ? "AI 暂不可用" : r.error()));
        }
        com.family.finance.domain.goal.GoalParams p = r.value();
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("ok", true);
        // v1.28 · 这一次推荐当时发给 AI 的是什么(开关关了 → null)
        resp.put("peek", promptPeek.of(me.getFamilyId(), r.promptRecordId()));
        resp.put("rationale", r.rationale());
        // 序列化字段(返回所有非空,前端 JS 选填)
        if (p.getRetireAge() != null) resp.put("retireAge", p.getRetireAge());
        if (p.getCurrentAge() != null) resp.put("currentAge", p.getCurrentAge());
        if (p.getMonthlyExpense() != null) resp.put("monthlyExpense", p.getMonthlyExpense());
        if (p.getInflationRate() != null) resp.put("inflationRate", p.getInflationRate());
        if (p.getWithdrawalRate() != null) resp.put("withdrawalRate", p.getWithdrawalRate());
        if (p.getChildBirthYear() != null) resp.put("childBirthYear", p.getChildBirthYear());
        if (p.getTargetYearOffset() != null) resp.put("targetYearOffset", p.getTargetYearOffset());
        if (p.getTargetAmount() != null) resp.put("targetAmount", p.getTargetAmount());
        if (p.getMonthsTarget() != null) resp.put("monthsTarget", p.getMonthsTarget());
        if (p.getAutoBaseline() != null) resp.put("autoBaseline", p.getAutoBaseline());
        return ResponseEntity.ok(resp);
    }

    // ---------- 手动触发月报 ----------

    /**
     * FR-53b · 手动触发单目标 AI 月报生成 · UI "立即生成月报"按钮调用。
     * 同步生成(period_id=0 · on-demand) · 生成完毕跳回详情页。
     */
    @PostMapping("/goals/{id}/report/generate")
    public String generateReport(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable long id,
                                 org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        goalService.require(me.getFamilyId(), id); // 权限校验
        var result = goalReportService.generateNowResult(me.getFamilyId(), id);
        ra.addFlashAttribute(result.ok() ? "reportNote" : "reportError", result.ok()
                ? "目标月报已更新。" : result.error());
        return "redirect:/goals/" + id;
    }

    // ---------- 软删 ----------

    @PostMapping("/goals/{id}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long id) {
        goalService.archive(me.getFamilyId(), id);
        return "redirect:/goals";
    }

    // ---------- helpers ----------

    private static GoalType parseType(String raw) {
        try {
            return GoalType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法目标类型: " + raw);
        }
    }
}
