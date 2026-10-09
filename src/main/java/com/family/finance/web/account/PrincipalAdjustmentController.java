package com.family.finance.web.account;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.ledger.PrincipalAdjustmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * v1.30 · 账户详情页的「补录本金」(PRD FR-973 / 974)。
 *
 * <p>任何已有历史的资产账户都能记 —— 股票 / 手填持仓 / 普通余额账户补录以前就有的钱都走这里;
 * 添加场外基金时的「以前就有、现在才补录」是同一件事的快捷入口。</p>
 */
@Controller
@RequiredArgsConstructor
public class PrincipalAdjustmentController {

    private final PrincipalAdjustmentService principalService;

    @PostMapping("/accounts/{accountId}/principal")
    public String record(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                         @RequestParam(required = false) BigDecimal amount,
                         @RequestParam(required = false) Long periodId,
                         @RequestParam(required = false) String note,
                         RedirectAttributes ra) {
        try {
            principalService.record(me.getFamilyId(), me.getMemberId(), accountId, periodId, amount, null, note);
            ra.addFlashAttribute("flashOk", "已记一笔补录本金 —— 这期不会把它算成收益,也不算收入");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("flashErr", e.getMessage());
        }
        return "redirect:/accounts/" + accountId;
    }

    @PostMapping("/accounts/{accountId}/principal/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                         @PathVariable long id, RedirectAttributes ra) {
        try {
            principalService.delete(me.getFamilyId(), accountId, id);
            ra.addFlashAttribute("flashOk", "已删除这笔补录本金 —— 这期余额里的这部分照常算回收益");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashErr", e.getMessage());
        }
        return "redirect:/accounts/" + accountId;
    }
}
