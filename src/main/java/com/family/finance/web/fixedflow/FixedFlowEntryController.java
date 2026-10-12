package com.family.finance.web.fixedflow;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.EntryRow;
import com.family.finance.service.EntryService;
import com.family.finance.service.fixedflow.FixedFlowConfirmService;
import com.family.finance.service.fixedflow.FixedFlowSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.32 · 填报页「固定收支」那一行上的按钮(PRD FR-1111 ~ 1121)。
 *
 * <p>都是普通表单 POST,做完回到同一期的填报页、停在 {@code #fixed-flows}(L16:显式选期 ——
 * 请求里带的 {@code periodId} 就是摘要所在的那一期,服务端校验它开着)。
 * 结果用 {@code fixedNote} / {@code fixedError} 写在摘要那一块里。</p>
 */
@Controller
@RequiredArgsConstructor
public class FixedFlowEntryController {

    private final FixedFlowConfirmService confirmService;
    private final FixedFlowSummaryService summaryService;
    private final EntryService entryService;
    private final PeriodMapper periodMapper;

    @PostMapping("/entry/fixed/{flowId}/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long flowId,
                          @RequestParam long periodId,
                          @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(defaultValue = "false") boolean remember,
                          @RequestParam(defaultValue = "false") boolean mine,
                          RedirectAttributes ra) {
        try {
            var r = confirmService.confirm(me.getFamilyId(), me.getMemberId(), periodId, flowId, amount, remember);
            ra.addFlashAttribute("fixedNote", r.message());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    /** 「全部确认」:只确认到了日子、算得出来的(FR-1112);每笔各自一个事务,一笔失败不连累别的 */
    @PostMapping("/entry/fixed/confirm-all")
    public String confirmAll(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam(defaultValue = "false") boolean mine,
                             RedirectAttributes ra) {
        Period period = periodMapper.findById(me.getFamilyId(), periodId).orElse(null);
        if (period == null) return "redirect:/entry";
        var summary = summaryService.build(me.getFamilyId(), me.getMemberId(), period, List.<EntryRow>of(), mine,
                "CNY", LocalDate.now());
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (var item : summary.getDue()) {
            if (!item.isConfirmable() || item.getFlow() == null) continue;
            try {
                confirmService.confirm(me.getFamilyId(), me.getMemberId(), periodId, item.getFlow().getId(), null, false);
                ok++;
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.add(e.getMessage());
            }
        }
        if (ok > 0) ra.addFlashAttribute("fixedNote", "已确认 " + ok + " 笔,写进去的和手动记的一样");
        if (!errors.isEmpty()) ra.addFlashAttribute("fixedError", String.join(";", errors));
        return back(periodId, mine);
    }

    @PostMapping("/entry/fixed/{flowId}/skip")
    public String skip(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long flowId,
                       @RequestParam long periodId, @RequestParam(defaultValue = "false") boolean mine,
                       RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("fixedNote", confirmService.skip(me.getFamilyId(), me.getMemberId(), periodId, flowId).message());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    /** FR-1151 · 「就是它」:导入的那一笔就是这条固定支出 */
    @PostMapping("/entry/fixed/{flowId}/link")
    public String link(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long flowId,
                       @RequestParam long periodId, @RequestParam long cashFlowId,
                       @RequestParam(defaultValue = "false") boolean mine, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("fixedNote",
                    confirmService.link(me.getFamilyId(), me.getMemberId(), periodId, flowId, cashFlowId).message());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    @PostMapping("/entry/fixed/occ/{occId}/undo")
    public String undo(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long occId,
                       @RequestParam long periodId, @RequestParam(defaultValue = "false") boolean mine,
                       RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("fixedNote", confirmService.undo(me.getFamilyId(), me.getMemberId(), occId).message());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    /**
     * FR-1120 · 没设固定还款的贷款:「接受」= 今天贷款行那个按钮调的同一个方法,结果逐字相同。
     */
    @PostMapping("/entry/fixed/loan/{accountId}/accept")
    public String acceptLoan(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                             @RequestParam long periodId, @RequestParam(defaultValue = "false") boolean mine,
                             RedirectAttributes ra) {
        try {
            entryService.acceptLoanPrediction(me.getFamilyId(), me.getMemberId(), periodId, accountId);
            ra.addFlashAttribute("fixedNote", "已按上两月趋势更新贷款余额");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    /** FR-1120 · 「保持上月」= 今天那个按钮:按上月末余额提交本期余额(同一个 submitBalance) */
    @PostMapping("/entry/fixed/loan/{accountId}/keep")
    public String keepLoan(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                           @RequestParam long periodId, @RequestParam BigDecimal newBalance,
                           @RequestParam(defaultValue = "false") boolean mine, RedirectAttributes ra) {
        try {
            entryService.submitBalance(me.getFamilyId(), me.getMemberId(), periodId, accountId, newBalance,
                    List.of(), List.of(), null);
            ra.addFlashAttribute("fixedNote", "这笔贷款本期余额保持上月");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("fixedError", e.getMessage());
        }
        return back(periodId, mine);
    }

    private static String back(long periodId, boolean mine) {
        return "redirect:/entry?period=" + periodId + (mine ? "&mine=true" : "") + "#fixed-flows";
    }
}
