package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowCategoryMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.expense.ExpenseCategoryService;
import com.family.finance.service.fixedflow.FixedFlowSchedule;
import com.family.finance.service.fixedflow.FixedFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v1.32 · 管理页「固定收支」(PRD FR-1132):按种类分组列出全家的固定收支,新增 / 修改 / 停用。
 *
 * <p>入口(FR-1130 / 1132 / 1133):填报页「本月收支」标题行「固定收支」· 摘要行「管理」·
 * 管理总览「家庭基础」· 管理侧栏 · 贷款 / 保险账户详情。从填报页进来的带 {@code back},存完回去。</p>
 */
@Controller
@RequestMapping("/admin/fixed-flows")
@RequiredArgsConstructor
public class FixedFlowAdminController {

    private final FixedFlowService fixedFlowService;
    private final AccountMapper accountMapper;
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final CashFlowCategoryMapper categoryMapper;
    private final ExpenseCategoryService expenseCategoryService;
    private final NavService navService;

    /** 分组展示用的一行 */
    public record Row(FixedFlow flow, String accountName, String targetName, String amountLabel,
                      String cycleLabel, String ownerName) {
    }

    @GetMapping
    public String list(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(required = false) String back, Model model) {
        long fam = me.getFamilyId();
        Map<Long, Account> accounts = accountsById(fam);
        Map<Long, String> members = membersById(fam);
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (String k : List.of(FixedFlow.INCOME, FixedFlow.EXPENSE, FixedFlow.REPAY, FixedFlow.PREMIUM)) groups.put(k, new ArrayList<>());
        for (FixedFlow f : fixedFlowService.listAll(fam)) {
            Account a = accounts.get(f.getAccountId());
            Account t = f.getTargetAccountId() == null ? null : accounts.get(f.getTargetAccountId());
            groups.computeIfAbsent(f.getKind(), k -> new ArrayList<>()).add(new Row(f,
                    a == null ? "?" : a.getDisplayName(), t == null ? null : t.getDisplayName(),
                    FixedFlowService.amountLabel(f, a == null ? "CNY" : a.getCurrency()),
                    cycleLabel(f), f.getOwnerMemberId() == null ? "共同" : members.getOrDefault(f.getOwnerMemberId(), "共同")));
        }
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("groups", groups);
        model.addAttribute("total", groups.values().stream().mapToInt(List::size).sum());
        model.addAttribute("back", safeBack(back));
        return "admin/fixed-flows";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(required = false) String kind,
                          @RequestParam(required = false) Long loan,
                          @RequestParam(required = false) Long policy,
                          @RequestParam(required = false) String back, Model model) {
        FixedFlowService.Form form;
        if (loan != null) {
            form = fixedFlowService.prefillForLoan(me.getFamilyId(), loan, LocalDate.now());
        } else if (policy != null) {
            form = fixedFlowService.prefillForPolicy(me.getFamilyId(), policy, LocalDate.now());
        } else {
            form = new FixedFlowService.Form();
            form.setKind(kind == null ? FixedFlow.EXPENSE : kind);
            form.setAmountMode(FixedFlow.FIXED);
            form.setCycle(FixedFlowSchedule.MONTHLY);
            form.setDayOfMonth(LocalDate.now().getDayOfMonth());
            form.setOwnerMemberId(me.getMemberId());
            form.setCategoryCode(FixedFlow.INCOME.equals(form.getKind()) ? "salary" : "consumption");
        }
        return form(me, form, null, back, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                       @RequestParam(required = false) String back, Model model) {
        FixedFlow f = fixedFlowService.require(me.getFamilyId(), id);
        FixedFlowService.Form form = new FixedFlowService.Form();
        form.setId(f.getId());
        form.setName(f.getName());
        form.setKind(f.getKind());
        form.setRepayKind(f.getRepayKind());
        form.setAmountMode(f.getAmountMode());
        form.setAutoMethod(f.getAutoMethod());
        form.setAmount(f.getAmount());
        form.setAccountId(f.getAccountId());
        form.setTargetAccountId(f.getTargetAccountId());
        form.setCategoryCode(f.getCategoryCode());
        form.setExpenseCategoryId(f.getExpenseCategoryId());
        form.setCycle(f.getCycle());
        form.setMonths(new ArrayList<>(FixedFlowSchedule.parseMonths(f.getMonths())));
        form.setDayOfMonth(f.getDayOfMonth());
        form.setOwnerMemberId(f.getOwnerMemberId());
        form.setEndMonth(f.getEndMonth() == null ? null : f.getEndMonth().toString().substring(0, 7));
        form.setNote(f.getNote());
        model.addAttribute("flow", f);
        return form(me, form, null, back, model);
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal MemberPrincipal me, @ModelAttribute FixedFlowService.Form form,
                       @RequestParam(required = false) String back, Model model, RedirectAttributes ra) {
        try {
            fixedFlowService.save(me.getFamilyId(), me.getMemberId(), form);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return form(me, form, e.getMessage(), back, model);
        }
        ra.addFlashAttribute("ffNote", "已保存「" + (form.getName() == null || form.getName().isBlank() ? "固定收支" : form.getName().trim())
                + "」· 到日子在填报页「固定收支」那一行一键确认");
        String b = safeBack(back);
        if (b != null) {
            ra.addFlashAttribute("fixedNote", "已保存固定收支 —— 到了日子会出现在这一行");
            return "redirect:" + b + "#fixed-flows";
        }
        return "redirect:/admin/fixed-flows";
    }

    @PostMapping("/{id}/disable")
    public String disable(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        fixedFlowService.disable(me.getFamilyId(), me.getMemberId(), id);
        ra.addFlashAttribute("ffNote", "已停用 —— 已经记过的不受影响");
        return "redirect:/admin/fixed-flows";
    }

    @PostMapping("/{id}/enable")
    public String enable(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        try {
            fixedFlowService.enable(me.getFamilyId(), me.getMemberId(), id);
            ra.addFlashAttribute("ffNote", "已重新启用");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("ffError", e.getMessage());
        }
        return "redirect:/admin/fixed-flows";
    }

    // ── 表单 ──────────────────────────────────────────────────────────────

    private String form(MemberPrincipal me, FixedFlowService.Form form, String error, String back, Model model) {
        long fam = me.getFamilyId();
        List<Account> active = accountMapper.findActiveByFamily(fam);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("form", form);
        model.addAttribute("formError", error);
        model.addAttribute("back", safeBack(back));
        // 每种固定收支能选的账户(服务端 FixedFlowService.validate 还会再判一次)
        model.addAttribute("incomeAccounts", active.stream()
                .filter(a -> !a.getType().isLiability() && a.getType() != AccountType.STOCK).toList());
        model.addAttribute("expenseAccounts", active);
        model.addAttribute("payerAccounts", active.stream().filter(a -> !a.getType().isLiability()).toList());
        model.addAttribute("loanAccounts", active.stream().filter(a -> a.getType() == AccountType.LOAN).toList());
        model.addAttribute("insuranceAccounts", active.stream().filter(a -> a.getType() == AccountType.INSURANCE).toList());
        model.addAttribute("ratedLoanIds", active.stream()
                .filter(a -> a.getType() == AccountType.LOAN && a.getAnnualRatePct() != null && a.getAnnualRatePct().signum() > 0)
                .map(Account::getId).toList());
        model.addAttribute("incomeCategories", categoryMapper.listIncomeOrdered().stream()
                .filter(c -> c.getAccountType() == null || !"STOCK".equals(c.getAccountType())).toList());
        model.addAttribute("expenseNatures", categoryMapper.listExpenseOrdered().stream()
                .filter(c -> "consumption".equals(c.getCode()) || "to_relatives".equals(c.getCode())).toList());
        model.addAttribute("expenseCats", flatCats(fam));
        // 编辑候选 = 活跃成员 ∪ 当前负责人(负责人后来归档了也不会被静默改派)
        model.addAttribute("members", memberDirectory.selectableWith(fam, form.getOwnerMemberId()));
        return "admin/fixed-flow-form";
    }

    /** 消费分类摊平成「大类 › 小类」一列(管理页用下拉就够,不搬宫格) */
    private List<Map.Entry<Long, String>> flatCats(long fam) {
        List<Map.Entry<Long, String>> out = new ArrayList<>();
        expenseCategoryService.pickable(fam).forEach((top, subs) -> {
            out.add(Map.entry(top.getId(), top.getName()));
            subs.forEach(c -> out.add(Map.entry(c.getId(), top.getName() + " › " + c.getName())));
        });
        return out;
    }

    private Map<Long, Account> accountsById(long fam) {
        return accountMapper.findAllByFamily(fam).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, String> membersById(long fam) {
        return memberDirectory.nameMap(fam);
    }

    static String cycleLabel(FixedFlow f) {
        String base = FixedFlowSchedule.YEARLY.equals(f.getCycle())
                ? "每年 " + String.join("、", FixedFlowSchedule.parseMonths(f.getMonths()).stream().map(m -> m + " 月").toList())
                : "每月";
        if (f.getEndMonth() != null) {
            int left = FixedFlowSchedule.remainingCount(f.getCycle(), f.getMonths(), java.time.YearMonth.now(),
                    java.time.YearMonth.from(f.getEndMonth()));
            base += " · 到 " + f.getEndMonth().toString().substring(0, 7) + (left >= 0 ? "(还剩 " + left + " 期)" : "");
        }
        return base;
    }

    /** 只接受站内相对路径(防开放跳转) */
    static String safeBack(String back) {
        if (back == null || back.isBlank()) return null;
        String b = back.trim();
        if (!b.startsWith("/") || b.startsWith("//") || b.contains("\\") || b.contains("\n") || b.contains("\r")) return null;
        return b;
    }
}
