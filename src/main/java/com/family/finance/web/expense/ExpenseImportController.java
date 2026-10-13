package com.family.finance.web.expense;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.expense.ExpenseCategoryService;
import com.family.finance.service.expense.imports.BillAccountResolver;
import com.family.finance.service.expense.imports.BillCategoryResolver;
import com.family.finance.service.expense.imports.BillCommitService;
import com.family.finance.service.expense.imports.ExistingFlowUpdateService;
import com.family.finance.service.expense.imports.BillImportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21(第 2 稿)· 账单导入:上传 → <b>逐笔</b>归类 → 确认页核对 → 落成真流水。
 *
 * <h3>草稿存 session,不落盘</h3>
 *
 * <p>账单是<b>整月的消费流水</b>,本项目迄今最敏感的输入。文件字节只活在方法栈里;
 * 草稿(逐笔的日期/商户/金额/归类)放 session 等用户确认,确认或丢弃即清。
 * <b>任何时候都不写磁盘、不进日志</b> —— 失败时只记「哪个渠道 / 哪一步 / 什么原因」。</p>
 *
 * <h3>确认页是必经之路</h3>
 *
 * <p>渠道的自动分类不是每笔都准,AI 更是在猜。直接落账等于把猜的结果写进用户的账。
 * 所以必须有一步核对,并且每行标出<b>归类依据</b>(FR-564)—— 用户没时间看 428 笔,
 * 但可以只看「AI 猜的」和「兜底」那十几笔,而这一页把它们排在最前面(FR-565)。</p>
 *
 * <h3>回传的是差异,不是整个草稿</h3>
 *
 * <p>确认时页面只回传「哪几行被改到了哪个分类 / 哪几行被剔除」。草稿在 session 里是权威的。
 * 全量回传的话,一个被篡改的金额就能直接写进账。</p>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ExpenseImportController {

    private static final String DRAFT_KEY = "v121ImportDraft";
    private static final String DRAFT_PERIOD = "v121ImportPeriod";

    private final BillImportService importService;
    private final com.family.finance.service.fixedflow.FixedFlowImportDedupe fixedFlowImportDedupe;   // v1.32 FR-1150
    private final BillCommitService commitService;
    private final ExpenseCategoryService categoryService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final ExpenseImportBatchMapper batchMapper;
    private final NavService navService;
    private final com.family.finance.repository.ExpenseAccountRuleMapper acctRuleMapper;
    private final com.family.finance.repository.CashFlowCategoryMapper cashFlowCategoryMapper;
    private final com.family.finance.repository.ExpenseFlowMapper expenseFlowMapper;
    private final ExistingFlowUpdateService existingUpdateService;

    @GetMapping("/expense/import")
    public String page(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam(required = false) Long periodId,
                       Model model, HttpSession session) {
        long fam = me.getFamilyId();
        var period = resolvePeriod(fam, periodId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("period", period);
        // v1.23 FR-626 · 双活跃窗口:让用户能改「这批账单记到哪个月」,并显式看见当前选的是哪个
        java.time.LocalDate todayForImport = java.time.LocalDate.now();
        var openForImport = periodMapper.findRecordableOpen(me.getFamilyId());
        model.addAttribute("openPeriods", openForImport);
        model.addAttribute("dualActive", openForImport.size() >= 2);
        model.addAttribute("backfillPeriodId", openForImport.stream()
                .filter(p -> p.getPeriodEnd() != null && p.getPeriodEnd().isBefore(todayForImport))
                .findFirst().map(com.family.finance.domain.period.Period::getId).orElse(null));
        model.addAttribute("hasCategories", categoryService.hasAny(fam));
        if (period == null) return "expense/import";

        model.addAttribute("accounts", accountMapper.findActiveByFamily(fam));
        model.addAttribute("batches", batchMapper.findLiveByPeriod(fam, period.getId()));
        /* 「记住我的改动」攒下来的两类规则 —— 用户要能看见自己记了什么,也要能删。
         * 只写不给看的记忆,用户第一次发现它归错类时会无从下手。 */
        model.addAttribute("rules", importService.rules(fam));
        model.addAttribute("acctRules", acctRuleMapper.findByFamily(fam));
        java.util.Map<Long, String> catName = new LinkedHashMap<>();
        for (var e : categoryService.pickable(fam).entrySet()) {
            catName.put(e.getKey().getId(), e.getKey().getName());
            for (var k : e.getValue()) catName.put(k.getId(), e.getKey().getName() + " › " + k.getName());
        }
        model.addAttribute("catName", catName);
        java.util.Map<Long, String> acctName = new LinkedHashMap<>();
        for (var a : accountMapper.findActiveByFamily(fam)) acctName.put(a.getId(), a.getDisplayName());
        model.addAttribute("acctName", acctName);

        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        Object dp = session.getAttribute(DRAFT_PERIOD);
        if (draft != null && dp instanceof Long l && l.equals(period.getId())) {
            putDraft(model, fam, draft);
        }
        return "expense/import";
    }

    /**
     * 确认页要的所有派生数据。
     *
     * <p>集中在这里算,不在模板里算 —— Thymeleaf 表达式里做分组排序会长到没人读得懂,
     * 而且那类错误是<b>渲染期</b>才炸的(响应截断成半页),编译和单测都发现不了。</p>
     */
    private void putDraft(Model model, long familyId, BillCategoryResolver.Draft draft) {
        model.addAttribute("draft", draft);
        model.addAttribute("channelLabel", draft.channel().getLabel());

        var spend = draft.bucket(BillCategoryResolver.Bucket.SPEND);
        /* 排序:【需要核对的排最前】(FR-565)。
         * 打开这一页时最该看到的不是「餐饮美食 96 笔」,而是「没把握的 8 笔」。
         * 其余按分类分组,组间按金额倒序。 */
        List<BillCategoryResolver.Line> review =
                spend.stream().filter(BillCategoryResolver.Line::needsReview).toList();
        List<BillCategoryResolver.Line> settled =
                spend.stream().filter(l -> !l.needsReview()).toList();

        Map<String, List<BillCategoryResolver.Line>> groups = new LinkedHashMap<>();
        /* v1.22 FR-593 · 「不建议录入」的行【并进同一张表、排最前】。
         *
         * 原来它们在另一个桶里,心智也不同(那边叫「捞回来」,这边叫「剔除」)——
         * 两种相反的动作、两套全选,用户要核对两遍。
         * 现在只有一个动作:勾 = 录入。系统的判断只体现为【默认值 + 逐行理由】。
         *
         * 排最前是因为它们才是要人看的;默认勾上的那 180 行不看也行。 */
        var suggestSkip = draft.bucket(BillCategoryResolver.Bucket.SUGGEST_SKIP);
        if (!suggestSkip.isEmpty()) {
            groups.put("不建议录入 · 默认没勾,理由在每行右边", new ArrayList<>(suggestSkip));
        }
        if (!review.isEmpty()) groups.put("先看这些 · 没把握", new ArrayList<>(review));
        Map<String, List<BillCategoryResolver.Line>> byCat = new LinkedHashMap<>();
        for (var l : settled) {
            byCat.computeIfAbsent(l.categoryName() == null ? "其他" : l.categoryName(),
                    k -> new ArrayList<>()).add(l);
        }
        byCat.entrySet().stream()
                .sorted((a, b) -> sum(b.getValue()).compareTo(sum(a.getValue())))
                .forEach(e -> groups.put(e.getKey(), e.getValue()));
        model.addAttribute("groups", groups);
        model.addAttribute("groupSums", groups.entrySet().stream().collect(
                LinkedHashMap::new, (m, e) -> m.put(e.getKey(), sum(e.getValue())), Map::putAll));
        model.addAttribute("reviewCount", review.size());

        model.addAttribute("spendCount", spend.size());
        /* 表里到底有几行可勾 —— 空态与「批量条渲不渲染」都看它,不看 spendCount:
         * 「要导入 0 但有 20 行不建议录入」时,表格是有内容的。 */
        model.addAttribute("selectableCount", spend.size() + suggestSkip.size());
        model.addAttribute("defaultIncludedCount", spend.size());
        model.addAttribute("spendSum", draft.sum(BillCategoryResolver.Bucket.SPEND));
        model.addAttribute("natureCount", draft.count(BillCategoryResolver.Bucket.NATURE));
        model.addAttribute("natureSum", draft.sum(BillCategoryResolver.Bucket.NATURE));
        model.addAttribute("incomeCount", draft.count(BillCategoryResolver.Bucket.INCOME));
        model.addAttribute("suggestSkipCount", draft.count(BillCategoryResolver.Bucket.SUGGEST_SKIP));
        model.addAttribute("skippedCount", draft.count(BillCategoryResolver.Bucket.SKIPPED));
        model.addAttribute("noTxNo", draft.noTxNo());
        model.addAttribute("parsed", draft.parsed());

        // 下拉给整棵树,细类带父名(「餐饮美食 › 外卖」)—— 两个同名细类才分得清
        List<Map<String, Object>> opts = new ArrayList<>();
        for (var e : categoryService.pickable(familyId).entrySet()) {
            opts.add(Map.of("id", e.getKey().getId(), "label", e.getKey().getName()));
            for (var k : e.getValue()) {
                opts.add(Map.of("id", k.getId(), "label", e.getKey().getName() + " › " + k.getName()));
            }
        }
        model.addAttribute("catOptions", opts);

        /* 账户下拉 + 「这批用到了几个账户」—— 后者是给用户的信号:
         * 一份账单跨了 3 个账户时,他得知道这件事,否则不会想到去核对账户列。 */
        java.util.Set<Long> distinct = new java.util.LinkedHashSet<>();
        for (var l : draft.lines()) if (l.accountId() != null) distinct.add(l.accountId());
        model.addAttribute("acctSpan", distinct.size());
        model.addAttribute("aiAvailable", importService.aiAvailable());
        /* v1.21 FR-580 · 另外两个桶也要能看见。
         * 原来只给一个数字「已剔除 92」—— 用户没法核对我们剔得对不对,
         * 而「剔错了」的后果是支出少算,他不会立刻发现。
         *
         * v1.22 · 可动性:
         *   要导入     → 默认勾上 · 可改分类/账户 · 可取消勾选
         *   不建议录入 → 默认不勾 · 逐行给理由 · 【可以勾】(含 0 元与负数,V60 之后 DB 不再挡)
         *   已存在     → 【可改分类与账户】,改的是已经入账的那一笔,不是新增(FR-598)
         *   性质另算 / 收入 → 只读 */
        model.addAttribute("suggestSkipLines", draft.bucket(BillCategoryResolver.Bucket.SUGGEST_SKIP));
        var skipped = draft.bucket(BillCategoryResolver.Bucket.SKIPPED);
        model.addAttribute("skippedLines", skipped);
        /* FR-600 · 「已存在」的笔可能落在别的账期、甚至已关账的期(去重范围是整个家庭)。
         * 已关账的期只能改分类 —— 改账户要动用户已经核对过并封存的期末余额。
         * 这里把 txNo → 是否已关账 查出来给模板,前端据此 disable 账户下拉;
         * 服务端另有一道硬拦(ExistingFlowUpdateService),前端只防手滑。 */
        java.util.Map<String, Boolean> existingClosed = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> existingCat = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> existingAcct = new java.util.LinkedHashMap<>();
        var skTx = skipped.stream().map(BillCategoryResolver.Line::txNo)
                .filter(t -> t != null && !t.isBlank()).distinct().toList();
        if (!skTx.isEmpty()) {
            for (var r : expenseFlowMapper.findExistingByTxNos(familyId, skTx)) {
                existingClosed.putIfAbsent(r.txNo(), r.closed());
                if (r.expenseCategoryId() != null) existingCat.putIfAbsent(r.txNo(), r.expenseCategoryId());
                existingAcct.putIfAbsent(r.txNo(), r.accountId());
            }
        }
        model.addAttribute("existingClosed", existingClosed);
        model.addAttribute("existingCat", existingCat);
        model.addAttribute("existingAcct", existingAcct);
        model.addAttribute("natureLines", draft.bucket(BillCategoryResolver.Bucket.NATURE));
        /* NATURE 桶只带 natureCode(loan_payment / …),面向用户要显示中文名。
         * 名字的唯一真相在 cash_flow_category,别在模板里硬编码一份 —— 改了名会对不上。 */
        java.util.Map<String, String> natureNames = new java.util.LinkedHashMap<>();
        for (var c : cashFlowCategoryMapper.listExpenseOrdered()) natureNames.put(c.getCode(), c.getDisplayName());
        model.addAttribute("natureNames", natureNames);
        model.addAttribute("incomeLines", draft.bucket(BillCategoryResolver.Bucket.INCOME));
        model.addAttribute("acctReviewCount",
                draft.bucket(BillCategoryResolver.Bucket.SPEND).stream()
                        .filter(l -> l.accountHow() != null && l.accountHow().needsReview()).count());
    }

    /**
     * 把异常翻译成一句人话。
     *
     * <p>只翻译<b>已知能撞上的</b>几种;其余返回异常类型名 —— 那至少比「再试一次」有信息量,
     * 而且用户能把它贴给我们。<b>不给用户看堆栈</b>(那是 v0.14 的教训)。</p>
     */
    private static String humanCause(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("ck_cash_flow_amount")) {
            return "有一笔的金额是 0 或负数(账单里的冲正行),数据库不接受";
        }
        if (m.contains("Data too long")) return "某一栏的文字太长了(多半是商户名)";
        if (m.contains("foreign key") || m.contains("FOREIGN KEY")) return "引用了一个已经不存在的账户或类目,刷新一下再试";
        if (m.contains("Duplicate entry")) return "有重复数据撞上了唯一约束";
        return e.getClass().getSimpleName();
    }

    private boolean ownedAccount(long familyId, Long accountId) {
        if (accountId == null) return false;
        return accountMapper.findById(familyId, accountId)
                .filter(a -> a.getFamilyId() != null && a.getFamilyId() == familyId
                          && a.getArchivedAt() == null)
                .isPresent();
    }

    private String accountLabel(long familyId, long accountId) {
        return accountMapper.findById(familyId, accountId)
                .filter(a -> a.getFamilyId() != null && a.getFamilyId() == familyId)
                .map(a -> a.getDisplayName()).orElse("该账户");
    }

    private static BigDecimal sum(List<BillCategoryResolver.Line> ls) {
        return ls.stream().map(BillCategoryResolver.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * v1.23 FR-626 · 没显式传 periodId 时,默认落**补录期**而不是最新 OPEN 期。
     *
     * <p><b>这是 issue #20 的正中心。</b>批量导入月账单天然的使用时点就是次月月初
     * (月中导只有半个月的数据)。原来这里取 {@code findCurrentOpen} —— 9/1 导入 8 月账单,
     * <b>整批几百行全部落进 9 月期</b>,而且页面上不会有任何提示。</p>
     *
     * <p>服务层早就支持选期({@code BillCommitService.commit} 收 periodId 且校验 OPEN),
     * 缺的只是这里选对默认值 + 页面把选中的月份显式写出来。</p>
     */
    private com.family.finance.domain.period.Period resolvePeriod(long familyId, Long periodId) {
        if (periodId != null) {
            var p = periodMapper.findById(familyId, periodId).orElse(null);
            if (p != null && p.getFamilyId() != null && p.getFamilyId() == familyId) return p;
            return null;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        var open = periodMapper.findRecordableOpen(familyId);
        return open.stream()
                .filter(p -> p.getPeriodEnd() != null && p.getPeriodEnd().isBefore(today))
                .findFirst()
                .orElseGet(() -> open.isEmpty() ? null : open.getLast());
    }

    @PostMapping("/expense/import/file")
    public String uploadFile(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam long periodId,
                             @RequestParam String channel,
                             @RequestParam MultipartFile file,
                             @RequestParam(required = false) String zipPassword,
                             @RequestParam(required = false) Long defaultAccountId,
                             HttpSession session, RedirectAttributes ra) {
        try {
            ExpenseSource ch = ExpenseSource.valueOf(channel.toUpperCase(java.util.Locale.ROOT));
            var draft = importService.parseFile(me.getFamilyId(), ch, file.getBytes(),
                    file.getOriginalFilename(), zipPassword, defaultAccountId);
            // v1.32 FR-1150 · 先确认、后导入:和本期已确认的固定支出同账户同金额的那一行,默认不勾、写明原因
            draft = fixedFlowImportDedupe.markConfirmed(me.getFamilyId(), periodId, draft);
            session.setAttribute(DRAFT_KEY, draft);
            session.setAttribute(DRAFT_PERIOD, periodId);
        } catch (BillImportService.ImportException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", "先选一下这份账单是哪个渠道的。");
        } catch (Exception e) {
            // 只记形状,不记文件内容
            log.warn("账单上传失败 · channel={} · {}", channel, e.toString());
            ra.addFlashAttribute("flashError", "这个文件读不了 —— 确认一下是渠道导出的 csv 或加密 zip。");
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /**
     * v1.21 FR-579 · 「让 AI 猜这几笔」—— 确认页上的<b>显式动作</b>,不是上传时的隐藏步骤。
     *
     * <p>返回 JSON,由前端应用到还没被用户改过的那几行。<b>不碰 session 里的草稿</b> ——
     * 确认页本来就逐行提交分类值,所以没必要把建议写回服务端,
     * 也就没有「AI 回来时用户已经改过这一行」的竞态。</p>
     */
    @PostMapping("/expense/import/ai-guess")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> aiGuess(@AuthenticationPrincipal MemberPrincipal me, HttpSession session) {
        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        if (draft == null) return Map.of("ok", false, "reason", "草稿过期了,重新传一次文件。");
        if (!importService.aiAvailable()) {
            return Map.of("ok", false, "reason", "还没配 AI(或者 key 没余额)—— 去管理页配好再试。没有它也能用,认不出来的落「其他」等你改。");
        }
        try {
            var guess = importService.guessForFallback(me.getFamilyId(), draft);
            Map<String, Object> byName = new LinkedHashMap<>();
            guess.forEach((k, v) -> byName.put(k, Map.of(
                    "id", v, "label", categoryService.displayName(me.getFamilyId(), v))));
            return Map.of("ok", true, "guess", byName);
        } catch (Exception e) {
            // 只记形状,不记商户名(账单内容不进日志)
            log.warn("AI 归类失败 · {}", e.toString());
            return Map.of("ok", false, "reason", "AI 没答上来(" + e.getClass().getSimpleName() + ")—— 剩下的手动改一下就好。");
        }
    }

    @PostMapping("/expense/import/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam long periodId,
                          @RequestParam long accountId,
                          @RequestParam(required = false) List<Integer> idx,
                          @RequestParam(required = false) List<String> cat,
                          @RequestParam(required = false) List<String> acct,
                          @RequestParam(required = false) List<Integer> include,
                          /* v1.24 FR-613 · 勾了「这笔是一次性的」的行号(写入路 W2)。
                           * 与 include 同构:复选框只在勾上时提交,所以没勾的行不会出现在列表里。 */
                          @RequestParam(required = false) List<Integer> oneOff,
                          @RequestParam(required = false) List<String> exTx,
                          @RequestParam(required = false) List<String> exCat,
                          @RequestParam(required = false) List<String> exAcct,
                          @RequestParam(defaultValue = "false") boolean remember,
                          @RequestParam(defaultValue = "false") boolean affectsBalance,
                          HttpSession session, RedirectAttributes ra) {
        long fam = me.getFamilyId();

        /* v1.22 FR-598 · 「已存在」那一桶的修正【先做,且不依赖草稿】。
         *
         * 它改的是<b>上次已经入账</b>的行,和这次要落的新行没有任何关系。
         * 放在草稿检查之后的话,session 一过期,用户在那一桶里改了半天的东西
         * 会连同一句「草稿已经过期」一起消失 —— 而那部分工作本来是能保住的。
         *
         * 顺带这也让它可以被单独调用(e2e 直接打这个端点验余额挪动,不用先传文件)。 */
        ExistingFlowUpdateService.Result exResult;
        try {
            exResult = existingUpdateService.apply(fam, me.getMemberId(),
                    buildExistingEdits(fam, exTx, exCat, exAcct));
        } catch (ExistingFlowUpdateService.UpdateException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/expense/import?periodId=" + periodId;
        }

        var draft = (BillCategoryResolver.Draft) session.getAttribute(DRAFT_KEY);
        if (draft == null) {
            /* 草稿没了,但修正是实打实做完的 —— 别把它说成失败 */
            if (exResult.total() > 0 || exResult.refusedClosed() > 0) {
                ra.addFlashAttribute("flashOk",
                        "已经" + existingSummary(exResult).replaceFirst("^ · 另外", "").trim()
                        + "。本次没有新导入的笔(草稿已过期,要导新的请重新传一次文件)。");
            } else {
                ra.addFlashAttribute("flashError", "草稿已经过期了 —— 重新传一次文件。");
            }
            return "redirect:/expense/import?periodId=" + periodId;
        }
        try {
            Map<Integer, Long> changed = new LinkedHashMap<>();
            if (idx != null && cat != null) {
                for (int i = 0; i < Math.min(idx.size(), cat.size()); i++) {
                    String v = cat.get(i);
                    if (v == null || v.isBlank()) continue;
                    try { changed.put(idx.get(i), Long.parseLong(v.trim())); }
                    catch (NumberFormatException ignore) { /* 脏值忽略,保持原归类 */ }
                }
            }
            /* 账户也按 idx 对齐回传(与 cat 同一套机制)。
             * 一份账单里资金来源是变化的 —— 整批一个账户会让几个账户的余额一起错。 */
            Map<Integer, Long> acctOf = new LinkedHashMap<>();
            if (idx != null && acct != null) {
                for (int i = 0; i < Math.min(idx.size(), acct.size()); i++) {
                    String v = acct.get(i);
                    if (v == null || v.isBlank()) continue;
                    try { acctOf.put(idx.get(i), Long.parseLong(v.trim())); }
                    catch (NumberFormatException ignore) { /* 脏值忽略 */ }
                }
            }
            /* v1.22 FR-590 · 提交模型从「drop + restore」翻成【一个 include 列表】。
             *
             * 旧模型有两个方向相反的参数,因为它的心智是「系统已经删了一批,用户捞回几个」;
             * 而那个心智本身就是错的 —— 判据是启发式的、会错,不该由系统执行删除。
             * 现在只有一个事实:**这一行用户勾没勾**。没勾的不落库,也没有任何东西被删。
             *
             * 【include 为 null 时不能当成「全选」】—— HTML 里一个复选框都没勾时
             * 浏览器什么都不提交,和「字段不存在」无法区分。当成全选会把用户
             * 刚刚全部取消的选择原样录进去,那是最糟的一种静默。所以 null == 空集,
             * 前端另有一道拦截保证不会提交空集(见 bill-confirm.js)。 */
            java.util.Set<Integer> included =
                    include == null ? java.util.Set.of() : new java.util.HashSet<>(include);

            List<BillCategoryResolver.Line> finalLines = new ArrayList<>();
            int notIncluded = 0;
            for (var l : draft.lines()) {
                // 只有 SPEND / SUGGEST_SKIP 参与勾选;NATURE 照常导入,INCOME 与 SKIPPED 不导入
                if (l.selectable() && !included.contains(l.idx())) { notIncluded++; continue; }
                if (l.bucket() == BillCategoryResolver.Bucket.SUGGEST_SKIP) {
                    /* 用户勾上了一个「不建议录入」的行 —— 按消费录。
                     * 它本来就带着分类和账户推荐(v1.22 起归类不再跳过这个桶),
                     * 所以这里和 SPEND 走同一套「用户改过就用他的」逻辑,不需要特殊照顾。
                     * 【不再有金额 ≤ 0 的拦截】:V60 已经放开,0 和负数都是合法值。 */
                    Long rc = changed.get(l.idx());
                    Long cid = (rc != null && categoryService.isUsable(fam, rc)) ? rc : l.categoryId();
                    Long rAcct = acctOf.get(l.idx());
                    Long aid = (rAcct != null && ownedAccount(fam, rAcct)) ? rAcct : l.accountId();
                    if (remember && rc != null && cid != null && !cid.equals(l.categoryId())) {
                        importService.rememberRule(fam, BillImportService.merchantKeyword(l.merchant()), cid);
                    }
                    finalLines.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(),
                            l.amount(), cid, categoryService.displayName(fam, cid),
                            l.how(), BillCategoryResolver.Bucket.SPEND,
                            null, null, l.txNo(), l.payMethod(), aid, l.accountHow()));
                    continue;
                }

                // ── 账户:用户改过就用他的(校验归属),否则保留推荐值 ──
                Long newAcct = acctOf.get(l.idx());
                Long finalAcct = l.accountId();
                var accountHow = l.accountHow();
                if (newAcct != null && ownedAccount(fam, newAcct) && !newAcct.equals(l.accountId())) {
                    finalAcct = newAcct;
                    accountHow = BillAccountResolver.How.RULE;
                    if (remember && l.payMethod() != null && !l.payMethod().isBlank()) {
                        importService.rememberAccountRule(fam, l.payMethod(), newAcct);
                    }
                }

                Long newCat = changed.get(l.idx());
                boolean changeable = l.bucket() == BillCategoryResolver.Bucket.SPEND;
                boolean catChanged = newCat != null && changeable && !newCat.equals(l.categoryId())
                        && categoryService.isUsable(fam, newCat);
                if (!catChanged && finalAcct == l.accountId()) { finalLines.add(l); continue; }
                if (!catChanged) {
                    finalLines.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(),
                            l.amount(), l.categoryId(), l.categoryName(), l.how(), l.bucket(),
                            l.natureCode(), l.suggestReason(), l.txNo(), l.payMethod(), finalAcct, accountHow));
                    continue;
                }
                finalLines.add(new BillCategoryResolver.Line(l.idx(), l.occurredAt(), l.merchant(),
                        l.amount(), newCat, categoryService.displayName(fam, newCat),
                        BillCategoryResolver.How.RULE, l.bucket(), null, null, l.txNo(),
                        l.payMethod(), finalAcct, accountHow));
                /* 「记住我的改动」—— 越用越准是这个功能的核心价值(FR-567)。
                 * 关键字由 merchantKeyword 提取(剥掉门店名/编号/企业后缀),
                 * 不是简单截前 20 字 —— 那样「瑞幸咖啡(国贸店)」换家店就不命中。 */
                if (remember) {
                    importService.rememberRule(fam,
                            BillImportService.merchantKeyword(l.merchant()), newCat);
                }
            }

            var r = commitService.commit(fam, me.getMemberId(), periodId, accountId,
                    draft.channel(), finalLines, notIncluded,
                    draft.count(BillCategoryResolver.Bucket.SKIPPED), affectsBalance,
                    oneOff == null ? java.util.Set.of() : new java.util.HashSet<>(oneOff));
            session.removeAttribute(DRAFT_KEY);
            session.removeAttribute(DRAFT_PERIOD);
            /* v1.22 · 合计按【代数和】。录了退款冲正(负数)之后它会比账单总额小,
             * 甚至可能是负的 —— 不说一句的话用户会以为少导了几笔。 */
            boolean hasNegative = finalLines.stream()
                    .anyMatch(l -> l.amount() != null && l.amount().signum() < 0);
            ra.addFlashAttribute("flashOk", "录入了 " + r.rows() + " 笔 · 合计 ¥"
                    + r.amount().setScale(2, java.math.RoundingMode.HALF_UP)
                    + (hasNegative
                        ? "(含退款冲正,所以合计比账单总额小 —— 那是对的,退款本来就该抵扣当月支出)" : "")
                    /* v1.22 FR-597 · 「未录入」而不是「剔除」—— 没有任何东西被删除,
                     * 只是这次没勾。重新导同一份文件还能再选(去重保证不会重复记)。 */
                    + (r.notIncluded() > 0
                        ? " · 未录入 " + r.notIncluded() + " 笔(没有被删除,重新导同一份文件还能再选)" : "")
                    + (r.skipped() > 0 ? " · 跳过 " + r.skipped() + " 笔(上次已导)" : "")
                    + (affectsBalance
                        ? " · 已从「" + accountLabel(fam, accountId) + "」的余额里扣掉 —— 记得回填报页核对余额。"
                        : " · 没有动任何账户余额,只记了花在哪。")
                    + existingSummary(exResult));
        } catch (BillCommitService.CommitException | BillImportService.ImportException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        } catch (Exception e) {
            /* 【把原因带出来】—— 原来这里只说「落库失败,再试一次」,
             * 用户再试一百次也还是失败(真实账单里一行冲正记录撞了 CHECK(amount>0)),
             * 而我这边也只能靠翻服务器日志才知道是什么。
             * 堆栈仍然不给用户看,但要给一句【能指导下一步】的话。 */
            log.warn("账单落库失败 · period={} · {}", periodId, e.toString(), e);
            ra.addFlashAttribute("flashError",
                    "落库失败,什么都没写进去。原因:" + humanCause(e)
                    + " —— 如果看不懂,把这句话发给我们。");
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /** 把三组平行数组拧成 Edit 列表。脏值直接丢,不让一个坏值废掉整次提交 */
    private List<ExistingFlowUpdateService.Edit> buildExistingEdits(
            long fam, List<String> exTx, List<String> exCat, List<String> exAcct) {
        List<ExistingFlowUpdateService.Edit> out = new ArrayList<>();
        if (exTx == null) return out;
        for (int i = 0; i < exTx.size(); i++) {
            String tx = exTx.get(i);
            if (tx == null || tx.isBlank()) continue;
            Long cid = parseIdOrNull(exCat, i);
            Long aid = parseIdOrNull(exAcct, i);
            if (cid == null && aid == null) continue;      // 这一行什么都没改
            out.add(new ExistingFlowUpdateService.Edit(tx.trim(), cid, aid));
        }
        return out;
    }

    private static Long parseIdOrNull(List<String> xs, int i) {
        if (xs == null || i >= xs.size()) return null;
        String v = xs.get(i);
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 如实说改了什么 —— 包括被拒的那几笔和为什么 */
    private static String existingSummary(ExistingFlowUpdateService.Result r) {
        if (r == null || (r.total() == 0 && r.refusedClosed() == 0)) return "";
        StringBuilder sb = new StringBuilder(" · 另外修正了上次已导入的 ");
        if (r.categoryChanged() > 0) sb.append(r.categoryChanged()).append(" 笔分类");
        if (r.accountChanged() > 0) {
            if (r.categoryChanged() > 0) sb.append("、");
            sb.append(r.accountChanged()).append(" 笔账户(余额已同步挪过去)");
        }
        if (r.refusedClosed() > 0) {
            sb.append(" · 有 ").append(r.refusedClosed())
              .append(" 笔在已关账的账期,账户没改(分类改了)—— 改账户要动已封存的余额");
        }
        return sb.toString();
    }

    @PostMapping("/expense/import/discard")
    public String discard(@RequestParam long periodId, HttpSession session) {
        session.removeAttribute(DRAFT_KEY);
        session.removeAttribute(DRAFT_PERIOD);
        return "redirect:/expense/import?periodId=" + periodId;
    }

    @PostMapping("/expense/import/acct-rule/delete")
    public String deleteAcctRule(@AuthenticationPrincipal MemberPrincipal me,
                                 @RequestParam long periodId, @RequestParam long ruleId) {
        acctRuleMapper.delete(me.getFamilyId(), ruleId);
        return "redirect:/expense/import?periodId=" + periodId;
    }

    /** 整批撤销(FR-539)· 软删该批次落的流水 + 把钱加回账户余额 */
    @PostMapping("/expense/import/revoke")
    public String revoke(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam long periodId, @RequestParam long batchId,
                         RedirectAttributes ra) {
        try {
            int n = commitService.revoke(me.getFamilyId(), me.getMemberId(), batchId);
            ra.addFlashAttribute("flashOk", "撤销了这一批 · " + n + " 笔已从账上移除,余额已加回。");
        } catch (BillCommitService.CommitException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/expense/import?periodId=" + periodId;
    }
}
