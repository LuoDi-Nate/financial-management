package com.family.finance.web.trash;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.repository.AccountTrashMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.service.trash.AccountDeletePlanner;
import com.family.finance.service.trash.AccountTrashService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * v1.33 · 管理 → 系统 →「回收站」(PRD FR-1230 ~ 1234)。
 *
 * <p>恢复是轻量确认(列出会放回什么,不要求打字);彻底清除要打 {@code 彻底清除「X」}。</p>
 */
@Controller
@RequiredArgsConstructor
public class TrashController {

    private final AccountTrashMapper trashMapper;
    private final AccountTrashService trashService;
    private final MemberDirectory memberDirectory;
    private final NavService navService;

    @GetMapping("/admin/trash")
    public String list(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("items", trashMapper.list(me.getFamilyId()));
        model.addAttribute("names", memberDirectory.nameMap(me.getFamilyId()));
        return "admin/trash";
    }

    @PostMapping("/admin/trash/{id}/restore")
    public String restore(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, RedirectAttributes ra) {
        try {
            var r = trashService.restore(me.getFamilyId(), me.getMemberId(), id);
            ra.addFlashAttribute("trashNote", "已恢复「" + r.accountName() + "」· 放回 " + r.rows() + " 行 · 报表与仪表盘按恢复后的数据重算");
            if (!r.notes().isEmpty()) ra.addFlashAttribute("trashNotes", r.notes());
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("trashError", e.getMessage());
        }
        return "redirect:/admin/trash";
    }

    @PostMapping("/admin/trash/{id}/purge")
    public String purge(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                        @RequestParam(defaultValue = "") String phrase, RedirectAttributes ra) {
        var item = trashMapper.list(me.getFamilyId()).stream().filter(t -> t.getId() == id).findFirst().orElse(null);
        if (item == null) {
            ra.addFlashAttribute("trashError", "回收站里没有这一项");
            return "redirect:/admin/trash";
        }
        if (!AccountDeletePlanner.phraseMatches(AccountTrashService.purgePhrase(item.getAccountName()), phrase)) {
            ra.addFlashAttribute("trashError", "没有清除:要原样输入「" + AccountTrashService.purgePhrase(item.getAccountName()) + "」");
            return "redirect:/admin/trash";
        }
        try {
            String name = trashService.purge(me.getFamilyId(), me.getMemberId(), id);
            ra.addFlashAttribute("trashNote", "已彻底清除「" + name + "」—— 之后只能从备份恢复");
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("trashError", e.getMessage());
        }
        return "redirect:/admin/trash";
    }
}
