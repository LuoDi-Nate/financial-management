package com.family.finance.web.account;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.NavService;
import com.family.finance.service.backup.BackupNowService;
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
 * v1.33 · 删除账户(PRD FR-1200 ~ 1226)。
 *
 * <p>GET 出命令行形态的面板(内容来自 {@link AccountDeletePlanner#plan});POST 时<b>重算</b>计划、
 * 逐字比确认语(中间数据变了就对不上 → 不删,面板按新数据重来)→ 先备份(失败不删)→ 移进回收站。</p>
 */
@Controller
@RequiredArgsConstructor
public class AccountDeleteController {

    private final AccountDeletePlanner planner;
    private final AccountTrashService trashService;
    private final BackupNowService backupNowService;
    private final NavService navService;

    @GetMapping("/accounts/{id}/delete")
    public String panel(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, Model model) {
        var plan = planner.plan(me.getFamilyId(), id);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("plan", plan);
        model.addAttribute("backupDirWritable", backupNowService.dirWritable());
        model.addAttribute("backupDir", backupNowService.dir().toString());
        return "accounts/delete";
    }

    @PostMapping("/accounts/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id,
                         @RequestParam(defaultValue = "") String phrase,
                         @RequestParam(defaultValue = "") String skipBackup, RedirectAttributes ra) {
        var plan = planner.plan(me.getFamilyId(), id);
        if (!AccountDeletePlanner.phraseMatches(plan.phrase(), phrase)) {
            ra.addFlashAttribute("deleteError", "确认语和面板上的不一致 —— 可能是刚才数据变了(面板已按最新数据重算),请重新核对后再输一次。没有删除任何东西。");
            return "redirect:/accounts/" + id + "/delete";
        }
        // 默认先备份;用户点「不备份,直接删除」(skipBackup=1)才跳过 —— 维护者 2026-10-13:删掉的还在回收站里,备份不该是硬门槛
        String backupFile = null;
        if (!"1".equals(skipBackup)) {
            BackupNowService.Result backup;
            try {
                backup = backupNowService.ensureFresh(me.getFamilyId(), me.getMemberId());
            } catch (IllegalStateException e) {
                ra.addFlashAttribute("deleteError", "删除前的备份没做成:" + e.getMessage() + " —— 没有删除任何东西。不需要备份的话,可以点「不备份,直接删除」。");
                return "redirect:/accounts/" + id + "/delete";
            }
            if (!backup.ok()) {
                ra.addFlashAttribute("deleteError", "删除前的备份失败:" + backup.error() + " —— 没有删除任何东西。不需要备份的话,可以点「不备份,直接删除」。");
                return "redirect:/accounts/" + id + "/delete";
            }
            backupFile = backup.file();
        }
        try {
            trashService.delete(me.getFamilyId(), me.getMemberId(), plan, backupFile);
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("deleteError", e.getMessage());
            return "redirect:/accounts/" + id + "/delete";
        }
        ra.addFlashAttribute("deletedNote", "已删除「" + plan.account().getDisplayName() + "」· 可在回收站恢复 · "
                + (backupFile == null ? "这次没有备份" : "删除前的备份在 " + backupFile));
        return "redirect:/accounts";
    }
}
