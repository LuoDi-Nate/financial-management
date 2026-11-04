package com.family.finance.web.goal;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.goal.*;
import com.family.finance.repository.*;
import com.family.finance.service.NavService;
import com.family.finance.service.goal.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class RetirementController {
    private final RetirementPlanService plans;
    private final RetirementDataService data;
    private final GoalService goals;
    private final GoalProgressService progress;
    private final FamilyMapper families;
    private final ExpenseCategoryMapper categories;
    private final GoalAiReportMapper reports;
    private final NavService nav;
    private final com.family.finance.service.llmtrace.PromptPeekLinks promptPeek;

    @InitBinder("plan")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("name", "targetMonth", "monthlyExpense", "monthlyIncome", "monthlyContribution",
                "childReserve", "medicalReserve", "housingReserve", "otherReserve", "educationGoalId", "autoExpense",
                "excludeOneOff", "excludedCategoryIds", "accountIds", "realizableAmounts[*]", "directRealRate",
                "nominalReturnPct", "inflationPct", "realReturnPct", "lowReturnPct", "middleReturnPct", "highReturnPct",
                "contributionFollowsInflation", "budgetConfirmed", "reservesConfirmed", "noOverlapConfirmed", "assetsConfirmed",
                "incomeConfirmed", "contributionConfirmed", "afterDebtConfirmed", "educationConfirmed");
    }

    @GetMapping("/goals/retirement/new")
    String createForm(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        return page(me, null, plans.draft(me.getFamilyId(), null), "plan", "CNY", model);
    }

    @GetMapping("/goals/{id:[0-9]+}/retirement")
    String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                  @RequestParam(defaultValue = "overview") String tab,
                  @RequestParam(required = false) String ccy, Model model) {
        var goal = retirementGoal(me, id);
        return page(me, goal, plans.draft(me.getFamilyId(), goal), tab, ccy, model);
    }

    @PostMapping({"/goals/retirement/new/preview", "/goals/{id:[0-9]+}/retirement/preview"})
    String preview(@AuthenticationPrincipal MemberPrincipal me, @PathVariable(required = false) Long id,
                   @ModelAttribute("plan") RetirementPlan plan, org.springframework.validation.BindingResult binding,
                   @RequestParam(required = false) String ccy, Model model) {
        Goal goal = id == null ? null : retirementGoal(me, id);
        trustedBasis(me, plan);
        if (binding.hasErrors()) {
            model.addAttribute("error", "金额或选择格式无效,请检查后重试。");
            return page(me, goal, plans.draft(me.getFamilyId(), goal), "plan", ccy, model);
        }
        model.addAttribute("previewing", true);
        return page(me, goal, plan, "overview", ccy, model);
    }

    @PostMapping({"/goals/retirement/new", "/goals/{id:[0-9]+}/retirement"})
    String save(@AuthenticationPrincipal MemberPrincipal me, @PathVariable(required = false) Long id,
                @ModelAttribute("plan") RetirementPlan plan, org.springframework.validation.BindingResult binding,
                @RequestParam(defaultValue = "false") boolean adoptionConfirmed, Model model, RedirectAttributes ra) {
        Goal goal = id == null ? null : retirementGoal(me, id);
        trustedBasis(me, plan);
        try {
            if (binding.hasErrors()) throw new IllegalArgumentException("金额或选择格式无效,请检查后重试");
            Goal saved = plans.save(me.getFamilyId(), id, plan, adoptionConfirmed, me.getMemberId());
            ra.addFlashAttribute("note", "退休计划已保存,未修改账户、流水或旧算法参数。");
            return "redirect:/goals/" + saved.getId() + "/retirement";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return page(me, goal, plan, "plan", plan.getBaseCurrency(), model);
        }
    }

    @PostMapping("/goals/{id:[0-9]+}/retirement/revert")
    String revert(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        plans.revert(me.getFamilyId(), id, me.getMemberId());
        ra.addFlashAttribute("reportNote", "已恢复原来的退休算法与参数,新方案草稿仍保留。");
        return "redirect:/goals/" + id;
    }

    @GetMapping("/goals/{id:[0-9]+}/retirement/export")
    @ResponseBody
    ResponseEntity<String> export(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id) {
        var goal = retirementGoal(me, id);
        var view = plans.active(me.getFamilyId(), goal).orElseThrow(() -> new IllegalArgumentException("请先采用新方案"));
        String text = "退休生活账 v1\n计划币种:" + view.plan().getBaseCurrency() + "\n计划金额基准日:" + view.plan().getPriceDate()
                + "\n本次购买力日期:" + view.result().asOf() + "\n资金账期:" + view.data().balancePeriod()
                + "\n" + view.result().aiSummary() + "\n来源月份:" + view.data().months()
                + "\n输入参数:\n" + plans.encode(view.plan())
                + "\n原节目:https://www.bilibili.com/video/BV1yUHP6rEYD/\n480=12/2.5%;名义模式实际回报=(1+R)/(1+通胀)-1。\n";
        return ResponseEntity.ok().header("Content-Type", "text/plain; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=retirement-" + id + ".txt")
                .header("Cache-Control", "no-store").body(text);
    }

    private Goal retirementGoal(MemberPrincipal me, long id) {
        Goal goal = goals.require(me.getFamilyId(), id);
        if (goal.getGoalType() != GoalType.RETIREMENT || goal.getArchivedAt() != null) throw new IllegalArgumentException("退休目标不存在或已归档");
        return goal;
    }

    private void trustedBasis(MemberPrincipal me, RetirementPlan p) {
        p.setBaseCurrency(families.findById(me.getFamilyId()).orElseThrow().getBaseCurrency());
        p.setPriceDate(LocalDate.now());
    }

    private String page(MemberPrincipal me, Goal goal, RetirementPlan p, String tab, String ccy, Model model) {
        long familyId = me.getFamilyId();
        if (p.getBaseCurrency() == null) trustedBasis(me, p);
        RetirementPlanService.View view;
        try { view = plans.evaluate(familyId, p, null, goal == null, false); }
        catch (IllegalArgumentException | ArithmeticException e) {
            model.addAttribute("error", "假设或金额无效,请检查回报、通胀与年月。");
            p = plans.draft(familyId, goal); view = plans.evaluate(familyId, p, null, goal == null, false); tab = "plan";
        }
        var active = goal == null ? java.util.Optional.<RetirementPlanService.View>empty() : plans.active(familyId, goal);
        model.addAttribute("adopting", goal != null && active.isEmpty());
        model.addAttribute("activePlan", active.isPresent());
        model.addAttribute("nativePlan", active.map(RetirementPlanService.View::createdForPlan).orElse(goal == null));
        model.addAttribute("nav", nav.load(me)); model.addAttribute("goal", goal); model.addAttribute("plan", p);
        model.addAttribute("view", view); model.addAttribute("r", view.result()); model.addAttribute("tab", tab);
        model.addAttribute("previewing", Boolean.TRUE.equals(model.getAttribute("previewing")));
        if (goal != null && active.isEmpty()) model.addAttribute("legacy", progress.compute(familyId, goal));
        String requested = ccy == null ? p.getBaseCurrency() : ccy.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("CNY", "USD", "HKD").contains(requested)) requested = p.getBaseCurrency();
        BigDecimal factor = data.viewFactor(familyId, p.getBaseCurrency(), requested, LocalDate.now());
        if (factor == null) { requested = p.getBaseCurrency(); factor = BigDecimal.ONE; model.addAttribute("fxNote", "显示币种的汇率缺失,金额继续使用本位币。"); }
        model.addAttribute("money", new Money(requested, factor));
        model.addAttribute("formAction", goal == null ? "/goals/retirement/new" : "/goals/" + goal.getId() + "/retirement");
        model.addAttribute("categoryChoices", categories.findByFamily(familyId));
        model.addAttribute("educationChoices", goals.findActiveByFamily(familyId).stream().filter(g -> g.getGoalType() == GoalType.EDUCATION).toList());
        if (goal != null && active.isPresent()) {
            var snapshot = active.get();
            reports.findLatestByGoalAndType(familyId, goal.getId(), "MONTHLY").ifPresent(report -> {
                model.addAttribute("report", report);
                model.addAttribute("reportStale", !snapshot.fingerprint().equals(report.getContextFingerprint()));
                model.addAttribute("reportPeekSrc", promptPeek.of(familyId, report.getPromptRecordId(),
                        com.family.finance.service.llmtrace.PromptSurface.GOAL_REPORT, report.getGeneratedAt(), null));
            });
            model.addAttribute("budgetChanged", p.getBudgetFingerprint() != null && !p.getBudgetFingerprint().equals(view.data().budgetFingerprint()));
        }
        return "goals/retirement";
    }

    public record Money(String currency, BigDecimal factor) {
        public String amount(BigDecimal value) {
            return com.family.finance.service.MoneyFormat.format2(currency, value == null ? null : value.multiply(factor));
        }
    }
}
