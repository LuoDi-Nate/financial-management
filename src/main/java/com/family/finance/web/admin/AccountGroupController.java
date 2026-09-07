package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.group.AccountGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.20 · 管理页「账户组」。
 *
 * <p>这一页是账户组的<b>唯一入口</b>。没建组时页面上什么都不会变 ——
 * 所以它必须自己解释清楚「建了会怎样、代价是什么」,而不是只给一个建组按钮。</p>
 */
@Controller
@RequiredArgsConstructor
public class AccountGroupController {

    private final AccountGroupService groupService;
    private final AccountMapper accountMapper;
    private final NavService navService;
    private final AuditLogService auditLogService;

    @GetMapping("/admin/account-groups")
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fam = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));

        List<Account> accounts = accountMapper.findActiveByFamily(fam);
        model.addAttribute("accounts", accounts);
        Map<Long, String> accountName = new LinkedHashMap<>();
        accounts.forEach(a -> accountName.put(a.getId(), a.getDisplayName()));
        model.addAttribute("accountName", accountName);

        var groups = groupService.list(fam);
        var members = groupService.membersByGroup(fam);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            List<Long> ids = members.getOrDefault(g.getId(), List.of());
            m.put("accountIds", ids);
            m.put("names", ids.stream().map(i -> accountName.getOrDefault(i, "账户#" + i)).toList());
            rows.add(m);
        }
        model.addAttribute("groups", rows);
        model.addAttribute("grouped", members.values().stream().flatMap(List::stream).toList());
        model.addAttribute("suggestions", groupService.suggestions(fam));
        return "admin/account-groups";
    }

    @PostMapping("/admin/account-groups/create")
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam String name,
                         @RequestParam(name = "accountIds", required = false) List<Long> accountIds,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        if (accountIds == null || accountIds.isEmpty()) {
            ra.addFlashAttribute("groupError", "至少要选一个账户 —— 空组没有任何意义。");
            return "redirect:/admin/account-groups";
        }
        // 代价先算出来:不拦,但要如实说(FR-470)
        String warning = groupService.costWarning(fam, accountIds);
        var g = groupService.create(fam, me.getMemberId(), name, null, accountIds);
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", g.getId(),
                "新建账户组「" + g.getName() + "」(" + accountIds.size() + " 个账户)");
        ra.addFlashAttribute("groupNote", "已建「" + g.getName() + "」,含 " + accountIds.size()
                + " 个账户。统计与归因里它们从现在起并成一行,记账完全不变 —— 你仍然按账户填余额、记收支、导截图。"
                + (warning == null ? "" : " " + warning));
        return "redirect:/admin/account-groups";
    }

    @PostMapping("/admin/account-groups/{id}/update")
    public String update(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(name = "accountIds", required = false) List<Long> accountIds,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        groupService.updateMembers(fam, id, name, accountIds == null ? List.of() : accountIds);
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", id,
                "调整账户组成员(" + (accountIds == null ? 0 : accountIds.size()) + " 个账户)");
        String warning = groupService.costWarning(fam, accountIds);
        ra.addFlashAttribute("groupNote", "已更新。已关账月份的历史数字不会变 ——"
                + "只有重开那一期再关账,才会按新分组重算。"
                + (warning == null ? "" : " " + warning));
        return "redirect:/admin/account-groups";
    }

    @PostMapping("/admin/account-groups/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        groupService.delete(fam, id);
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", id, "删除账户组");
        ra.addFlashAttribute("groupNote", "已删。这些账户回到「未分组」,数据一行没动,页面回到没建组的样子。");
        return "redirect:/admin/account-groups";
    }
}
