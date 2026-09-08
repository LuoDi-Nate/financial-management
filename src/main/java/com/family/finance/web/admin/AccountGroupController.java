package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.group.AccountGroup;
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
 * v1.20 · 账户组。
 *
 * <p><b>它挂在「账户」域下而不是「管理」域下</b>(维护者验收后改的)。理由:
 * 分组回答的是「这些账户合起来看」,和账户列表是同一件事的两个方向 ——
 * 「从账户看它属于哪个组」和「从组看它含哪些账户」。
 * 放在管理页等于让用户先想到「这是一项配置」才找得到它,而实际上他是在看账户的时候想起来的。</p>
 *
 * <p>所以入口有三个,都指向这里:账户列表页顶部、账户详情页、以及管理页保留的一张卡片。</p>
 */
@Controller
@RequiredArgsConstructor
public class AccountGroupController {

    private final AccountGroupService groupService;
    private final AccountMapper accountMapper;
    private final NavService navService;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.member.MemberDirectory memberDirectory;   // v1.20 · 勾选列表要显示主理人

    @GetMapping("/accounts/groups")
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(required = false) String q,
                       Model model) {
        long fam = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));

        List<Account> accounts = accountMapper.findActiveByFamily(fam);
        model.addAttribute("accounts", accounts);
        Map<Long, String> accountName = new LinkedHashMap<>();
        accounts.forEach(a -> accountName.put(a.getId(), a.getDisplayName()));
        model.addAttribute("accountName", accountName);
        /* v1.20 验收补 · 勾选列表只有账户名不够辨别 —— 家里两张卡都叫「招行」是常事,
         * 而「哪张是我的、哪张是老婆的」恰恰是决定要不要放进同一个组的依据。
         * 主理人用【含已归档成员】的目录:账户不会因为主理人被归档就换主人,
         * 用仅活跃列表会让名字变成「成员#7」(v1.15 FR-382 同一个坑)。 */
        java.util.Map<Long, String> memberName = memberDirectory.nameMap(fam);
        java.util.Map<Long, String> ownerOf = new java.util.LinkedHashMap<>();
        accounts.forEach(a -> ownerOf.put(a.getId(),
                a.getPrimaryOwnerMemberId() == null ? "共同"
                        : memberName.getOrDefault(a.getPrimaryOwnerMemberId(), "成员#" + a.getPrimaryOwnerMemberId())));
        model.addAttribute("ownerOf", ownerOf);

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
        // 已被别的组占用的账户 → 占用者组名。界面据此置灰并标出「已在『X』里」,
        // 免得用户勾了之后才发现原组被悄悄改了(第一版就是这么干的)。
        model.addAttribute("occupied", groupService.occupiedBy(fam, null));
        model.addAttribute("q", q == null ? "" : q.trim());
        return "accounts/groups";
    }

    @PostMapping("/accounts/groups/create")
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam String name,
                         @RequestParam(name = "accountIds", required = false) List<Long> accountIds,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        if (accountIds == null || accountIds.isEmpty()) {
            ra.addFlashAttribute("groupError", "至少要选一个账户 —— 空组没有任何意义。");
            return "redirect:/accounts/groups";
        }
        // 代价先算出来:不拦,但要如实说(FR-470)
        String warning = groupService.costWarning(fam, accountIds);
        AccountGroup g;
        try {
            g = groupService.create(fam, me.getMemberId(), name, null, accountIds);
        } catch (AccountGroupService.GroupConflictException e) {
            // 组名重复 / 账户已属别的组 —— 这些是用户自己能纠正的,给人话,别让约束名冒成 500 白页
            ra.addFlashAttribute("groupError", e.getMessage());
            return "redirect:/accounts/groups";
        }
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", g.getId(),
                "新建账户组「" + g.getName() + "」(" + accountIds.size() + " 个账户)");
        ra.addFlashAttribute("groupNote", "已建「" + g.getName() + "」,含 " + accountIds.size()
                + " 个账户。统计与归因里它们从现在起并成一行,记账完全不变 —— 你仍然按账户填余额、记收支、导截图。"
                + (warning == null ? "" : " " + warning));
        return "redirect:/accounts/groups";
    }

    @PostMapping("/accounts/groups/{id}/update")
    public String update(@AuthenticationPrincipal MemberPrincipal me,
                         @PathVariable long id,
                         @RequestParam(required = false) String name,
                         @RequestParam(name = "accountIds", required = false) List<Long> accountIds,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        try {
            groupService.updateMembers(fam, id, name, accountIds == null ? List.of() : accountIds);
        } catch (AccountGroupService.GroupConflictException e) {
            ra.addFlashAttribute("groupError", e.getMessage());
            return "redirect:/accounts/groups";
        }
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", id,
                "调整账户组成员(" + (accountIds == null ? 0 : accountIds.size()) + " 个账户)");
        String warning = groupService.costWarning(fam, accountIds);
        ra.addFlashAttribute("groupNote", "已更新。已关账月份的历史数字不会变 ——"
                + "只有重开那一期再关账,才会按新分组重算。"
                + (warning == null ? "" : " " + warning));
        return "redirect:/accounts/groups";
    }

    @PostMapping("/accounts/groups/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                         RedirectAttributes ra) {
        long fam = me.getFamilyId();
        groupService.delete(fam, id);
        auditLogService.record(fam, me.getMemberId(), AuditLogType.SYSTEM, "account_group", id, "删除账户组");
        ra.addFlashAttribute("groupNote", "已删。这些账户回到「未分组」,数据一行没动,页面回到没建组的样子。");
        return "redirect:/accounts/groups";
    }
}
