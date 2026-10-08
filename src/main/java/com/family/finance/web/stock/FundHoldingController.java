package com.family.finance.web.stock;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.fund.FundCatalog;
import com.family.finance.service.fund.FundHoldingService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * v1.30 · 场外基金 / 货币基金的页面动作(PRD FR-959~961 / 965 / 967 / 970)。
 *
 * <p>入口在持仓页「添加持仓」里(§13 ③ 裁定);只对人民币的基金 / 证券 / 理财 / 现金账户出现(PRD §3.1),
 * 别的账户直接 404 —— 不告诉对方「这里有个功能你用不了」。</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class FundHoldingController {

    private final FundCatalog catalog;
    private final FundHoldingService fundHoldingService;
    private final StockHoldingService stockHoldingService;
    private final AccountMapper accountMapper;
    private final NavService navService;

    // ---------- 添加 ----------

    @GetMapping("/accounts/{accountId}/holdings/new-fund")
    public String newFund(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId, Model model) {
        Account account = requireFundAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        return "stock/fund-new";
    }

    /** HTMX:搜索结果(≤ 20 条);只在内存代码表里匹配 */
    @GetMapping("/funds/search")
    public String search(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(value = "q", required = false) String q,
                         @RequestParam long accountId, Model model) {
        requireFundAccount(me.getFamilyId(), accountId);
        List<FundCatalog.Classified> hits = catalog.search(me.getFamilyId(), q);
        model.addAttribute("hits", hits);
        model.addAttribute("q", q == null ? "" : q.trim());
        model.addAttribute("accountId", accountId);
        return "stock/_fund-parts :: results";
    }

    /** HTMX:选中一只后的报价卡 + 填份额 / 市值的表单 */
    @GetMapping("/funds/{code}/quote")
    public String quote(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String code,
                        @RequestParam long accountId, Model model) {
        Account account = requireFundAccount(me.getFamilyId(), accountId);
        model.addAttribute("account", account);
        model.addAttribute("q", fundHoldingService.quote(me.getFamilyId(), code));
        model.addAttribute("hasCashRow", hasCashRow(me.getFamilyId(), accountId));
        return "stock/_fund-parts :: quote";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-fund")
    public String create(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                         @RequestParam String code, @RequestParam(defaultValue = "SHARES") String by,
                         @RequestParam BigDecimal amount,
                         @RequestParam(value = "cashLinked", defaultValue = "false") boolean cashLinked,
                         RedirectAttributes ra) {
        requireFundAccount(me.getFamilyId(), accountId);
        try {
            FundHoldingService.By mode = "VALUE".equalsIgnoreCase(by) ? FundHoldingService.By.VALUE : FundHoldingService.By.SHARES;
            var h = fundHoldingService.create(me.getFamilyId(), me.getMemberId(), accountId, code, mode, amount, cashLinked);
            ra.addFlashAttribute("flashOk", "已添加「" + h.getDisplayName() + "」—— 以后跟着刷新自动更新");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashErr", e.getMessage());
            return "redirect:/accounts/" + accountId + "/holdings/new-fund";
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- 手填 → 自动 / 改回手填 / 继续自动更新 ----------

    @GetMapping("/accounts/{accountId}/holdings/{hid}/to-nav")
    public String convertForm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                              @PathVariable long hid, Model model) {
        Account account = requireFundAccount(me.getFamilyId(), accountId);
        var h = requireHoldingOf(me.getFamilyId(), accountId, hid);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("h", h);
        model.addAttribute("plan", fundHoldingService.planConvert(me.getFamilyId(), hid));
        return "stock/fund-convert";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/to-nav")
    public String convert(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                          @PathVariable long hid, RedirectAttributes ra) {
        requireHoldingOf(me.getFamilyId(), accountId, hid);
        try {
            fundHoldingService.convert(me.getFamilyId(), me.getMemberId(), hid);
            ra.addFlashAttribute("flashOk", "已改为按净值自动估值");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("flashErr", e.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/nav-off")
    public String navOff(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                         @PathVariable long hid, RedirectAttributes ra) {
        requireHoldingOf(me.getFamilyId(), accountId, hid);
        fundHoldingService.navOff(me.getFamilyId(), hid);
        ra.addFlashAttribute("flashOk", "已改回手填 —— 单价停在最后一次的净值,余额不变");
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/nav-resume")
    public String resume(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                         @PathVariable long hid, RedirectAttributes ra) {
        requireHoldingOf(me.getFamilyId(), accountId, hid);
        fundHoldingService.resume(me.getFamilyId(), hid);
        ra.addFlashAttribute("flashOk", "已继续自动更新");
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- 改份额 / 改金额(含现金联动)----------

    @PostMapping("/accounts/{accountId}/holdings/{hid}/fund-shares")
    public String editShares(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long accountId,
                             @PathVariable long hid, @RequestParam BigDecimal shares,
                             @RequestParam(value = "cashLinked", defaultValue = "false") boolean cashLinked,
                             RedirectAttributes ra) {
        requireHoldingOf(me.getFamilyId(), accountId, hid);
        try {
            fundHoldingService.editShares(me.getFamilyId(), me.getMemberId(), hid, shares, cashLinked);
            ra.addFlashAttribute("flashOk", cashLinked ? "已更新 —— 钱从账户现金行里挪,余额不变" : "已更新");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashErr", e.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- helpers ----------

    /** 账户要属于这个家、并且 §3.1 允许加场外基金;否则 404 */
    private Account requireFundAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
                .filter(a -> a.getFamilyId() == familyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!FundHoldingService.accountAllowsFunds(acc)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return acc;
    }

    private com.family.finance.domain.stock.StockHolding requireHoldingOf(long familyId, long accountId, long hid) {
        var h = stockHoldingService.require(familyId, hid);
        if (!h.getAccountId().equals(accountId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return h;
    }

    private boolean hasCashRow(long familyId, long accountId) {
        return stockHoldingService.findActiveByAccount(familyId, accountId).stream()
                .anyMatch(x -> x.getValuationMode() == ValuationMode.CASH);
    }
}
