package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.common.MetricDisplay;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.DecompositionPoint;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.TrendPoint;
import com.family.finance.factview.WaterfallSegment;
import com.family.finance.calc.BenchmarkAggregator;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FxMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.NavService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.allocation.AllocationService;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.FamilyDiagnoseService;
import com.family.finance.service.HouseholdCashflowService;
import com.family.finance.service.goal.GoalProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ReportsController {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

    private final FactViewService factViewService;
    private final com.family.finance.service.group.AccountRowGrouper accountRowGrouper;              // v1.20 账户组折叠
    private final com.family.finance.service.group.AccountGroupingResolver groupingResolver;        // v1.20
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final FxMapper fxMapper;
    private final FxService fxService;
    private final NavService navService;
    private final FamilyDiagnoseService familyDiagnoseService;
    private final GoalProgressService goalProgressService;
    private final HouseholdCashflowService householdCashflowService;
    /** v1.8 · 家庭支出唯一口径入口 —— 本页 KPI / 折线 / tooltip 必须同源 */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger;
    private final com.family.finance.repository.CashFlowMapper cashFlowMapper;
    private final com.family.finance.repository.FamilyMapper familyMapper;
    // v0.4 新依赖
    private final ProductCategoryService productCategoryService;
    private final AllocationService allocationService;
    private final com.family.finance.repository.AllocationAnchorMapper allocationAnchorMapper;
    private final com.family.finance.repository.RebalanceAdviceCacheMapper rebalanceAdviceCacheMapper;
    private final com.family.finance.service.review.RebalancePlanService rebalancePlanService;   // v1.2 计划卡
    // v0.5 FR-72/73/74 · 财富水位
    private final com.family.finance.service.macro.WaterLevelService waterLevelService;
    private final com.family.finance.service.report.SealedPeriodService sealedPeriodService;
    private final com.family.finance.service.macro.MacroBenchmarkService macroBenchmarkService;
    private final com.family.finance.service.explain.MetricExplainService metricExplain; // v0.5.3 口径真实数值
    private final com.family.finance.service.MetricPrefsService metricPrefsService; // v0.11.4 账户表复用管理页指标配置
    private final com.family.finance.repository.PeriodAccountAttrMapper periodAccountAttrMapper; // v1.12 FR-350 锚期定格属性
    private final com.fasterxml.jackson.databind.ObjectMapper jacksonMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(defaultValue = "1Y") String range,
                          @RequestParam(name = "accounts", required = false) List<Long> accounts,
                          @RequestParam(required = false) String currency,
                          @RequestParam(required = false) String asof, // v0.11.5 · 观察账期(只在已关账期里选)
                          // v1.8 FR-272 · 支出构成的维度与窗口(白名单解析 · 脏值兜底)
                          @RequestParam(required = false) String mix,
                          @RequestParam(name = "mixWin", required = false) Integer mixWin,
                          @RequestHeader(value = "HX-Request", required = false) String htmx,
                          @RequestHeader(value = "HX-Target", required = false) String hxTarget,
                          Model model) {
        // v0.16.x 兜底:全新部署(零周期)→ 回引导页并提示先开周期,
        // 而不是 ReportsAnchorResolver 抛「尚未创建周期」IllegalStateException → 500。
        if (periodMapper.countByFamily(me.getFamilyId()) == 0) {
            return "redirect:/?needs=period";
        }
        String accountsCsv = accounts == null || accounts.isEmpty()
                ? null
                : accounts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        populateModel(me, range, accountsCsv, currency, asof, model);
        // v1.11.1 · 窗口不再独立 —— 与三区的时间范围统一(维护者第 5 条:
        //   「后面几个带时间范围的组件没有统一时间筛选组件」)。mixWin 仍接受(老链接不 404),
        //   但没显式传时用 range 推出来的期数。
        populateExpenseComposition(me, mix, mixWin != null ? mixWin : savingsWindowPeriods(range), model);
        // v1.11.2 · **必须看 HX-Target,不能只看 HX-Request**。
        //
        //   这里原来是「只要带 HX-Request 就回 `_region :: region` 片段」—— 那是给
        //   `_region.html` 里的账户/币种筛选器用的,它们 hx-target="#reports-region",
        //   片段里正好有这个 id,能换上去。
        //
        //   但 v1.11 把趋势 chips 和支出构成维度改成了 `hx-select="#sec-trend"` /
        //   `hx-select="#sec-expense-mix"` —— 这两个 id **都不在** `_region :: region` 片段里
        //   (`#sec-trend` 在 index.html 上,`#sec-expense-mix` 在 _expense-mix.html 上)。
        //   于是 HTMX 拿到片段、按 id 挑不到东西 → 换进去一个**空**内容 →
        //   `hx-swap="outerHTML"` 把整个 section **从页面上删掉**。
        //   表现就是维护者报的「切成按账户,对应模块直接没了」—— 后端 200、日志干净,
        //   纯前端选择器落空,最难查的那种。
        //
        //   所以:只有目标确实是 `reports-region` 时才回片段,其余一律回整页,让 hx-select 有东西可挑。
        //   (整页响应大一些,但服务端渲染耗时相同 —— 省的本来就是滚动位置,不是后端。)
        boolean regionSwap = hxTarget == null || hxTarget.isBlank() || "reports-region".equals(hxTarget);
        if ("true".equalsIgnoreCase(htmx) && regionSwap) {
            return "reports/_region :: region";
        }
        return "reports/index";
    }

    /**
     * v1.8 FR-272 · 支出构成。只在**逐笔模式**下渲染 —— 总额模式没有构成可言,
     * 硬塞一个空图比不显示更糟。窗口 1/6/12 期,取「近 N 个已关账期 + 当前进行中期」。
     */
    /**
     * 支出构成的「本期」锚点:**优先取进行中(OPEN)的那一期**,没有再退回「不晚于今天的最近一期」。
     *
     * <p>不能只用 today:家庭可以提前开下一期(比如 8 月就把 9 月开出来开始录),
     * 那时用户心里的「本期」是那个 OPEN 期,而 {@code period_start <= today} 会把它排除,
     * 构成直接空图。反过来也不能只用 OPEN 期 —— 有的家庭关账后迟迟不开新期,那时得回落到今天。</p>
     */
    private java.time.LocalDate compositionAsOf(long familyId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return periodMapper.findCurrentOpen(familyId)
                .map(com.family.finance.domain.period.Period::getPeriodStart)
                .filter(start -> start.isAfter(today))
                .orElse(today);
    }

    private void populateExpenseComposition(MemberPrincipal me, String mix, Integer mixWin, Model model) {
        var mode = expenseLedger.modeOf(me.getFamilyId());
        model.addAttribute("mixEnabled", mode == com.family.finance.domain.family.ExpenseEntryMode.ITEMIZED);
        if (mode != com.family.finance.domain.family.ExpenseEntryMode.ITEMIZED) {
            return;
        }
        // v1.11.1 · 不再只认 1/6/12 —— 现在期数由统一的时间范围推出(1/3/6/YTD/12/240),
        //   任意正整数都合法;非法值回落 1 期(本期)。
        int win = (mixWin != null && mixWin > 0) ? Math.min(mixWin, 240) : 1;
        var dim = com.family.finance.service.expense.ExpenseLedgerService.Dim.fromCode(mix);
        var periods = periodMapper.findRecentAsOf(me.getFamilyId(), compositionAsOf(me.getFamilyId()), win);
        var periodIds = periods.stream().map(com.family.finance.domain.period.Period::getId).toList();
        var comp = expenseLedger.composition(me.getFamilyId(), periodIds, dim);

        model.addAttribute("mixDims", com.family.finance.service.expense.ExpenseLedgerService.Dim.values());
        model.addAttribute("mixDim", dim);
        model.addAttribute("mixWinValue", win);
        model.addAttribute("mixComposition", comp);
        model.addAttribute("mixPeriodIds", periodIds);
        model.addAttribute("mixWindowLabel", win == 1 ? "本期" : ("近 " + win + " 期"));
        // 只填了总数的账期 → 如实列出月份,不隐藏
        // 归档账户上的历史逐笔:不进构成(全站统计都排归档),但如实说清有多少笔,
        // 否则用户看到「录了 6 笔、构成只有 3 笔」会以为程序丢数据。
        var arch = periodIds.isEmpty() ? null : cashFlowMapper.sumArchivedExpense(me.getFamilyId(), periodIds);
        model.addAttribute("mixArchivedCount", arch == null ? 0 : arch.itemCount());
        model.addAttribute("mixArchivedAmount", arch == null ? java.math.BigDecimal.ZERO : arch.amount());
        model.addAttribute("mixTotalOnlyLabels", comp.totalOnlyPeriodIds().stream()
                .map(pid -> periods.stream().filter(pp -> pp.getId().equals(pid)).findFirst()
                        .map(pp -> pp.getPeriodStart().toString().substring(0, 7)).orElse(String.valueOf(pid)))
                .toList());
    }

    /** v1.8 FR-272 · 某一格的逐笔明细(HTMX 抽屉)。 */
    @GetMapping("/reports/expense-mix/detail")
    public String expenseMixDetail(@AuthenticationPrincipal MemberPrincipal me,
                                   @RequestParam String dim,
                                   @RequestParam String groupKey,
                                   @RequestParam(name = "mixWin", required = false) Integer mixWin,
                                   Model model) {
        int win = (mixWin != null && (mixWin == 1 || mixWin == 6 || mixWin == 12)) ? mixWin : 1;
        var d = com.family.finance.service.expense.ExpenseLedgerService.Dim.fromCode(dim);
        var periodIds = periodMapper.findRecentAsOf(me.getFamilyId(), compositionAsOf(me.getFamilyId()), win).stream()
                .map(com.family.finance.domain.period.Period::getId).toList();
        model.addAttribute("mixDetailRows", periodIds.isEmpty() ? java.util.List.of()
                : cashFlowMapper.expenseBreakdownDetail(me.getFamilyId(), periodIds, d.code(), groupKey));
        model.addAttribute("mixDetailLabel", d.displayName());
        model.addAttribute("baseCurrency", familyMapper.findById(me.getFamilyId())
                .map(f -> f.getBaseCurrency()).orElse("CNY"));
        return "reports/_expense-mix :: detail";
    }

    @GetMapping("/reports/period/{periodId}")
    public String periodDrilldown(@AuthenticationPrincipal MemberPrincipal me,
                                  @PathVariable long periodId,
                                  Model model) {
        Family family = familyService.require(me.getFamilyId());
        Period period = periodMapper.findById(periodId)
                .filter(p -> p.getFamilyId() == me.getFamilyId())
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        FactSlice slice = factViewService.load(new FactFilter(
                me.getFamilyId(), period.getPeriodType(), period.getPeriodStart(), period.getPeriodStart(),
                false, null, family.getBaseCurrency()));
        List<AccountPeriodFact> rows = slice.rows().stream()
                .filter(row -> row.periodId().equals(periodId))
                .toList();
        model.addAttribute("period", period);
        model.addAttribute("rows", rows);
        model.addAttribute("currency", family.getBaseCurrency());
        return "reports/_drilldown :: modal";
    }

    private void populateModel(MemberPrincipal me, String range, String accountsCsv, String currency, String asof, Model model) {
        Family family = familyService.require(me.getFamilyId());
        // v0.5.5 FR-94 · 报表锚定「最近已关账(≤今天)账期」快照;无则退外壳锚 + closedSnapshot=false
        // v0.11.5 · 观察账期:报表是每月快照,可在「已关账账期」里回看任一期(asof 命中则锚它,否则默认最近已关账)
        ReportsAnchorResolver.AnchorChoice defaultChoice = resolveAnchor(me.getFamilyId());
        // v0.11.5 · 可回看的已关账期 = CLOSED 且 ≤ 默认锚(最近已关账)。以「默认锚」作上界(而非 LocalDate.now())——
        //   resolveAnchor 走 DB 日期挑锚,若 JVM 与 DB 日期有偏差,用 now 作上界会把默认锚挤出下拉;用锚作界则默认锚必在列。
        List<Period> closedPeriods = defaultChoice.closedSnapshot()
                ? periodMapper.findAllByFamily(me.getFamilyId()).stream()
                    .filter(p -> p.getStatus() == com.family.finance.domain.period.PeriodStatus.CLOSED
                            && p.getPeriodStart() != null
                            && !p.getPeriodStart().isAfter(defaultChoice.anchor().getPeriodStart()))
                    .sorted(java.util.Comparator.comparing(Period::getPeriodStart).reversed())
                    .toList()
                : java.util.List.of();
        // asof 命中某已关账期 → 锚它(closedSnapshot=true);否则用默认锚
        ReportsAnchorResolver.AnchorChoice anchorChoice = defaultChoice;
        if (asof != null && !asof.isBlank()) {
            for (Period p : closedPeriods) {
                if (asof.equals(p.getPeriodStart().toString())) {
                    anchorChoice = new ReportsAnchorResolver.AnchorChoice(p, true);
                    break;
                }
            }
        }
        Period anchor = anchorChoice.anchor();
        boolean closedSnapshot = anchorChoice.closedSnapshot();
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        // BUG-FIX(2026-05-11 · critical):非 base 账户币种 → 当期 fx_rate 必须存在,不然 SQL 走 1.0 兜底
        // v0.8 BUG-FIX(v08-CCY-INV-2):报表趋势/TWR/同比也吃多期 endBalanceBase,ensure 扩到 ≤anchor 全期
        List<Long> ensurePeriodIds = periodMapper.findAllByFamily(me.getFamilyId()).stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .map(Period::getId)
                .toList();
        fxService.ensureForAccountCurrencies(me.getFamilyId(), family.getBaseCurrency(), ensurePeriodIds);

        // BUG-FIX(2026-05-10):同 dashboard,缺 fx_rate 时即时拉 frankfurter,失败再回退 + toast 提示
        String requestedCurrency = viewCurrency;
        boolean fxFallback = false;
        if (!viewCurrency.equalsIgnoreCase(family.getBaseCurrency())) {
            boolean hasRate = fxService.getOrFetchRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, anchor.getId()).isPresent();
            if (!hasRate) {
                viewCurrency = family.getBaseCurrency();
                fxFallback = true;
            } else {
                // v0.8 BUG-FIX(v08-CCY-INV-2):视图币种(可能无账户)全窗口补 base→view,三角换算不漏期
                fxService.ensureRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, ensurePeriodIds);
            }
        }
        // v1.10 · 一区/二区(封板快照)· **只吃 asof,不吃 range** —— 切片由 SealedPeriodService 自备。
        //   放在主切片之前算,是为了让「前两区与 range 无关」这件事在代码顺序上也看得出来。
        model.addAttribute("sealed",
                sealedPeriodService.load(me.getFamilyId(), anchor, closedSnapshot, viewCurrency));
        model.addAttribute("currencySymbol",
                "USD".equals(viewCurrency) ? "$" : ("HKD".equals(viewCurrency) ? "HK$" : "¥"));

        FactSlice slice = factViewService.load(new FactFilter(
                me.getFamilyId(),
                family.getPeriodType(),
                rangeStart(range, anchor.getPeriodStart()),
                anchor.getPeriodStart(),
                false,
                accountIds,
                viewCurrency
        ));
        // v0.4 FR-60b · waterfall + sankey 数据准备移除(流水视角)
        // 但 decomposition labels 仍需(给本金 vs 收益分解图用 · 复用 waterfall.label 输出)
        List<WaterfallSegment> waterfall = factViewService.incomeExpenseWaterfall(slice);
        List<DecompositionPoint> decomposition = factViewService.principalVsReturnDecomposition(slice);
        List<TrendPoint> debtTrend = factViewService.debtTrend(slice);
        // v1.12 FR-350 · 报表页 = 封板视图,「预实」列的预期年化 % 取锚期定格值(仪表盘仍走实时重载)。
        // 见 FactViewService.accountPerformance(slice, sealedAttrs) 的注释:两个页面在这一列上刻意不同。
        List<AccountPerformance> accountRows = factViewService.accountPerformance(slice, true);
        DecompositionPoint lastDecomposition = decomposition.isEmpty() ? null : decomposition.getLast();
        List<Account> allAccounts = accountMapper.findActiveByFamily(me.getFamilyId());
        List<FxRate> fxRates = fxMapper.findLatestByFamily(me.getFamilyId(), 36);

        // v0.4 FR-61b · 账户级 vs 基准对照
        // ────────────────────────────────────────────────────────────────────────────
        // v1.12 FR-350 · 这三张表(类目 code / 基准 % / 类目中文名)原来一律读**当前**的
        // account 行和 product_category 行 —— 于是给某账户换个类目、或改一下类目的 benchmarkPct,
        // 「实际 vs 基准」这一列在所有历史 range 下当场跟着变:同一个 range=1Y 昨天今天两个数,
        // 事后没人复现得出来当初为什么显示跑输。
        //
        // 改成读**锚期**(= slice.returnAnchorPeriodId() = 窗口内最新已关账期)的定格属性。
        // 为什么是这个锚而不是 lastPeriodId:账户 xirr / 家庭 xirr 本身就锚在它上面(v1.6.30),
        // 基准必须与实际同一时点,否则「实际 − 基准」两边不是同一天的口径。
        //
        // 定格行缺失 → 逐字段回落当前值,与旧行为完全一致。合法缺失有三种:
        //   ① 锚期还没关账(外壳态 / 全新家庭)· ② 账户建在该期之后 · ③ v1.12 之前且回填没覆盖到。
        // 所以这里不能写成「查不到就报错」——护栏 v112-ATTR-BENCH-ANCHOR 只钉住「读的是锚期」。
        // ────────────────────────────────────────────────────────────────────────────
        Long benchAnchorPeriodId = slice.returnAnchorPeriodId();
        java.util.Map<Long, com.family.finance.domain.period.PeriodAccountAttr> frozenAttr = new java.util.HashMap<>();
        if (benchAnchorPeriodId != null) {
            for (var attr : periodAccountAttrMapper.findByPeriod(benchAnchorPeriodId)) {
                frozenAttr.put(attr.accountId(), attr);
            }
        }
        java.util.Map<String, BigDecimal> benchmarkPctByPcCode = new java.util.HashMap<>();
        java.util.Map<String, String> pcNameByCode = new java.util.HashMap<>();
        for (var pc : productCategoryService.listAll()) {
            if (pc.getBenchmarkPct() != null) benchmarkPctByPcCode.put(pc.getCode(), pc.getBenchmarkPct());
            pcNameByCode.put(pc.getCode(), pc.getDisplayName());
        }
        java.util.Map<Long, String> pcCodeByAccountId = new java.util.HashMap<>();
        java.util.Map<Long, BigDecimal> benchPctByAccountId = new java.util.HashMap<>();
        // v0.14.1 · 类目列显示中文名(不再裸露 GOLD/US_STOCK/PRECIOUS_METAL 等 code);无映射时兜底 code
        java.util.Map<Long, String> pcNameByAccountId = new java.util.HashMap<>();
        for (Account a : allAccounts) {
            var frozen = frozenAttr.get(a.getId());
            String code = frozen != null && frozen.productCategoryCode() != null
                    ? frozen.productCategoryCode() : a.getProductCategoryCode();
            if (code == null) continue;
            pcCodeByAccountId.put(a.getId(), code);
            // 基准 % 与中文名同样优先取定格值:类目被改 benchmarkPct / 被删改名后,
            // 历史窗口显示的仍是当初那一版(只存 code 挡不住类目自身被改)。
            BigDecimal bench = frozen != null && frozen.benchmarkPct() != null
                    ? frozen.benchmarkPct() : benchmarkPctByPcCode.get(code);
            if (bench != null) benchPctByAccountId.put(a.getId(), bench);
            String name = frozen != null && frozen.productCategoryName() != null
                    ? frozen.productCategoryName() : pcNameByCode.getOrDefault(code, code);
            pcNameByAccountId.put(a.getId(), name);
        }
        // v0.11.4 · 账户表改为「复用管理页指标配置」渲染:直接迭代全字段 accountRows(AccountPerformance),
        //   基准对照数据按 accountId 建索引 map 供模板 zip;不再压成精简的 AccountBenchmarkRow 列表。
        java.util.Map<Long, AccountBenchmarkRow> benchmarkByAccount = new java.util.HashMap<>();
        for (AccountPerformance ap : accountRows) {
            String pcCode = pcCodeByAccountId.get(ap.accountId());
            BigDecimal pcBench = benchPctByAccountId.get(ap.accountId());   // v1.12 · 锚期定格值(缺则已回落当前值)
            BigDecimal benchmark = BenchmarkAggregator.benchmarkForAccount(
                ap.xirr(), pcBench, ap.accountType().name());
            // v0.11.4:实际 = 卡片显示的那个 xirr(<12 期累计 / ≥12 期年化)− 同基基准 → pp;
            //   修 v0.10.5「cumPnl/净投入 当实际」的爆值(净投入极小→+19497pp)+ 与显示脱节。
            int months = ap.monthsHeld() == null ? 0 : ap.monthsHeld();
            BigDecimal diffPct = BenchmarkAggregator.displayedDiffPercentPoints(ap.xirr(), benchmark, months);
            BenchmarkAggregator.BeatStatus beat = BenchmarkAggregator.beatStatusDisplayed(diffPct, months);
            benchmarkByAccount.put(ap.accountId(), new AccountBenchmarkRow(
                ap.accountName(), ap.accountType().name(), pcCode,
                null, benchmark, diffPct, beat.name(), null)); // xirrLabel/valueLabel 模板内实时格式化,置 null
        }
        java.util.Set<String> acctMetrics = metricPrefsService.enabled(family.getMetricPrefs(), "account");

        // v0.4 FR-61c · 家庭加权基准
        java.util.List<BenchmarkAggregator.BenchmarkInput> bmInputs = slice.rows().stream()
            .filter(r -> java.util.Objects.equals(r.periodId(), slice.lastPeriodId()))
            .filter(r -> r.endBalanceBase() != null && r.endBalanceBase().signum() > 0)
            .map(r -> {
                // v1.12 · 基准值取锚期定格(与账户行同源);**加权的是哪些行**仍按 lastPeriodId 过滤 ——
                // 「家庭加权按 lastPeriodId、账户 xirr 按 returnAnchorPeriodId」是 v1.12 之前就有的
                // 口径不一致,已记在 docs/metric-audit-v1.11.md,本版刻意不动(动了分水岭比对就无法归因)。
                BigDecimal pcBench = benchPctByAccountId.get(r.accountId());
                BigDecimal benchmark = BenchmarkAggregator.benchmarkForAccount(
                    null, pcBench, r.accountType().name());
                return new BenchmarkAggregator.BenchmarkInput(r.endBalanceBase(), benchmark);
            })
            .toList();
        BigDecimal familyBenchmarkPct = BenchmarkAggregator.weightedFamilyBenchmark(bmInputs);
        BigDecimal familyXirrDecimal = factViewService.familyXirr(slice);
        // v0.11.4:家庭 pill 实际 = 卡片头条显示的那个「家庭 XIRR」本身(<12 期累计 / ≥12 期年化)− 加权基准 → pp。
        //   修 v0.10.5「累计PnL/累计净投入 当实际」的爆值 + 与头条 XIRR 脱节(头条 8.3% 却显示跑输 -243%)。
        // v1.6.30 · 期数改用已关账期:familyXirr/familyTwr 已只走已关账期,这里的月数必须同源,
        //   否则「按 N 期求解」的 N、年化/累计 判定、基准按窗口折算的分母 都会比实际参与计算的期数多一期。
        int familyMonths = slice.returnPeriodIds().size();
        BigDecimal familyDiffPct = BenchmarkAggregator.displayedDiffPercentPoints(familyXirrDecimal, familyBenchmarkPct, familyMonths);
        BenchmarkAggregator.BeatStatus familyBeat = BenchmarkAggregator.beatStatusDisplayed(familyDiffPct, familyMonths);

        // v0.4 FR-62a · 配置 diff
        AllocationService.DiffResult allocationDiff = allocationService.compute(me.getFamilyId(), slice);
        java.util.List<com.family.finance.domain.allocation.AllocationAnchor> allocationAnchors = allocationAnchorMapper.findAll();

        // v0.4 FR-62b · 调仓建议缓存渲染(若有)
        var f4cache = rebalanceAdviceCacheMapper.findByFamilyAndAnchor(me.getFamilyId(), family.getAllocationAnchor());
        RebalanceAdviceView rebalanceAdvice = null;
        if (f4cache.isPresent()) {
            var cache = f4cache.get();
            // 30 天 TTL 检查
            long days = java.time.Duration.between(cache.getGeneratedAt(), java.time.LocalDateTime.now()).toDays();
            if (days <= 30) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> raw = jacksonMapper.readValue(cache.getContentJson(), java.util.Map.class);
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> actions =
                        (java.util.List<java.util.Map<String, Object>>) raw.getOrDefault("actions", java.util.List.of());
                    rebalanceAdvice = new RebalanceAdviceView(
                        (String) raw.get("narrative"),
                        actions,
                        cache.getGeneratedAt());
                } catch (Exception ignored) { /* 解析失败不渲染 */ }
            }
        }

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("range", normalizeRange(range));
        model.addAttribute("currency", viewCurrency);
        model.addAttribute("ranges", List.of("1M", "3M", "6M", "YTD", "1Y", "ALL"));
        model.addAttribute("currencies", List.of("CNY", "USD", "HKD"));
        model.addAttribute("accountsCsv", accountsCsv == null ? "" : accountsCsv);
        model.addAttribute("selectedAccountCount", accountIds == null ? allAccounts.size() : accountIds.size());
        model.addAttribute("allAccounts", allAccounts);
        model.addAttribute("anchorPeriod", anchor);
        // v0.11.5 · 观察账期下拉:只列已关账账期;asof=当前锚(仅快照态非空,外壳态留空 → 下拉不选中)
        model.addAttribute("periods", closedPeriods);
        model.addAttribute("asof", closedSnapshot ? anchor.getPeriodStart().toString() : "");

        BigDecimal familyTwrDecimal = factViewService.familyTwr(slice);
        // v0.5.5 FR-95 · 四指标需 ≥2 个已关账账期才有意义(要上一期做基准);不足 → 显「—」不显误导性 0
        boolean reportsHasMetrics = closedSnapshot && slice.returnPeriodIds().size() >= 2;
        model.addAttribute("closedSnapshot", closedSnapshot);
        model.addAttribute("reportsHasMetrics", reportsHasMetrics);
        // v0.10.5 · 资产年化 仅满 12 期才是真年化(12月滚动几何);不足为累计 → 动态标签「资产累计」
        model.addAttribute("familyReturnAnnualized", familyMonths >= 12);
        if (reportsHasMetrics) {
            model.addAttribute("familyXirr", percent(familyXirrDecimal));
            model.addAttribute("familyTwr", percent(familyTwrDecimal));
            model.addAttribute("cumulativeNetInflow", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow()));
            model.addAttribute("cumulativePnl", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl()));
        } else {
            model.addAttribute("familyXirr", "—");
            model.addAttribute("familyTwr", "—");
            model.addAttribute("cumulativeNetInflow", "—");
            model.addAttribute("cumulativePnl", "—");
        }

        // v0.5 FR-72/73/74 · 财富水位(并入 reports section)· 用净资产趋势 + CPI/M2 基准
        // v0.13 · 用「剔除累计开账基线」的趋势 → 补录存量账户不假装跑赢通胀
        List<TrendPoint> trend = factViewService.netWorthTrendExOpening(slice);
        var waterLevel = waterLevelService.compute(trend);
        model.addAttribute("waterLevel", waterLevel);
        model.addAttribute("cpiAverages", macroBenchmarkService.cpiAverages());
        model.addAttribute("m2Averages", macroBenchmarkService.m2Averages());
        model.addAttribute("macroLatest", macroBenchmarkService.latest());
        // 人赚/钱赚原始 BigDecimal(给水位分解诊断用 · 复用 FR-84 修复后的口径)
        model.addAttribute("netInflowRaw", lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow());
        model.addAttribute("pnlRaw", lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl());

        // v0.4 FR-60b · 砍 waterfall / sankey 数据(不再注入)
        model.addAttribute("decomposition", decomposition);
        model.addAttribute("debtTrend", debtTrend);
        // v0.11.4 · 账户表复用管理页指标配置:注入全字段 accountRows + 指标启用集 + 基准索引 + 类目索引
        /* v1.20 FR-483 · 与 dashboard 同一份折叠(共用 AccountRowGrouper,不是各算各的)。
         * benchmarkByAccount 仍按【真账户 id】建索引 —— 组行 accountId=null 取不到基准,
         * 模板渲染「—」。这是对的:组行的 xirr 本来就是 null,拿什么去和基准比? */
        var foldedRows = accountRowGrouper.fold(me.getFamilyId(), anchor.getId(), null, accountRows);
        model.addAttribute("accountRows", foldedRows);                              // 顶层:图 + 计数
        model.addAttribute("accountRowsFlat", accountRowGrouper.flatten(foldedRows)); // 表:组行 + 隐藏成员行
        model.addAttribute("hasGroups", groupingResolver.hasAnyGroup(me.getFamilyId()));
        model.addAttribute("acctMetrics", acctMetrics);
        model.addAttribute("benchmarkByAccount", benchmarkByAccount);
        model.addAttribute("pcCodeByAccount", pcCodeByAccountId);
        model.addAttribute("pcNameByAccount", pcNameByAccountId);
        model.addAttribute("fxRates", fxRates);
        model.addAttribute("fxFallback", fxFallback);
        model.addAttribute("requestedCurrency", requestedCurrency);

        // v0.4 FR-61c · 家庭 vs 基准 · v0.5.5:无快照指标时置 null → 隐藏 vs 基准 pill(不在「—」旁显比较)
        model.addAttribute("familyBenchmarkPct", reportsHasMetrics ? familyBenchmarkPct : null);
        model.addAttribute("familyBenchmarkDiff", familyDiffPct);
        model.addAttribute("familyBeatStatus", familyBeat.name());

        // v0.4 FR-62a · 配置 diff
        model.addAttribute("allocationDiff", allocationDiff);
        model.addAttribute("allocationAnchors", allocationAnchors);
        // v0.4 FR-62b · 调仓建议
        model.addAttribute("rebalanceAdvice", rebalanceAdvice);
        // v1.2 · 本期再平衡计划(FR-7/8)
        model.addAttribute("rebalancePlanView", rebalancePlanService.activePlan(me.getFamilyId()));
        model.addAttribute("planAccounts", accountMapper.findActiveByFamily(me.getFamilyId()));

        // labels = 全部账期标签(N 期)· 修 bug:原来错接成 decomposition 标签(N−1 期)导致
        //   负债曲线(用 data.labels + N 个 debtValues)少一个标签 → 只画 N−1 点;
        //   分解图(用 data.labels.slice(1) 对齐 N−1 个分解点)再少一个 → N−2 柱(2 期时 0 柱)。
        //   改用全期标签后:负债曲线 N 点、分解图 slice(1) 正好对齐 N−1 个分解点。
        model.addAttribute("labels", debtTrend.stream().map(TrendPoint::label).toList());
        model.addAttribute("decompPrincipal", decomposition.stream().map(DecompositionPoint::cumulativeNetInflow).toList());
        model.addAttribute("decompPnl", decomposition.stream().map(DecompositionPoint::cumulativePnl).toList());
        model.addAttribute("debtValues", debtTrend.stream().map(TrendPoint::value).toList());

        // v0.2 FR-40e · 风险等级分布(用于 reports 风险敞口环形图)
        FamilyDiagnose familyDiagnose = familyDiagnoseService.diagnose(me.getFamilyId());
        model.addAttribute("riskDistribution", familyDiagnose.riskDistribution());
        model.addAttribute("riskLabels", familyDiagnose.riskDistribution().stream()
                .map(FamilyDiagnose.RiskBucket::label).toList());
        model.addAttribute("riskValues", familyDiagnose.riskDistribution().stream()
                .map(FamilyDiagnose.RiskBucket::amount).toList());
        model.addAttribute("riskRatios", familyDiagnose.riskDistribution().stream()
                .map(b -> b.ratio() == null ? BigDecimal.ZERO
                        : b.ratio().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_EVEN))
                .toList());

        // v0.3 FR-51a/b · 储蓄能力 · 月度双柱(2026-05-13 修订:成员级 SUM 聚合)
        // v0.5.3 · 同时把中间量 stash 到 local,供下方 tooltip 真实数值(储蓄区按本位币 ¥)
        boolean savAvail = false;
        int savFilled = 0, savTotal = 0;
        BigDecimal savSumInc = BigDecimal.ZERO, savSumExp = BigDecimal.ZERO,
                   savAvgInc = null, savAvgExp = null, savLatestInc = null, savLatestExp = null,
                   savRateDec = null, savMedian = null;
        try {
            // v1.8 · 折线与本段 KPI 必须同口径同期集合(见 HouseholdCashflowService.recentSeries 注释:
            // 直读 PMC 会造成「KPI 有值、折线空白」——正是 v1.6.29 那类被用户报障的同屏矛盾)。
            // v1.11 · 期数跟随时间范围。原来写死 12 期 —— 而这一节已经归入「三区 · 趋势」,
            //   同区的其他图都跟 range 走,只有它不跟 → 用户切 3M 却看到 12 个月的收支柱,
            //   两个图放在一起读会得出错误结论(维护者第 6 条)。
            //   ALL 用 240 期(20 年)当上界:recentSeries 是「近 N 期」语义,给个足够大的数即可。
            var series = householdCashflowService.recentSeries(me.getFamilyId(), savingsWindowPeriods(range));
            List<String> savLabels = series.stream()
                .map(pt -> pt.periodStart() == null ? String.valueOf(pt.periodId())
                                                    : pt.periodStart().toString().substring(2, 7)).toList();
            List<BigDecimal> savIncome = series.stream().map(pt -> pt.income()).toList();
            List<BigDecimal> savExpense = series.stream().map(pt -> pt.expense()).toList();
            int[] ratio = householdCashflowService.filledMonthRatio(me.getFamilyId());
            savAvgInc = householdCashflowService.avgMonthlyIncome(me.getFamilyId());
            savAvgExp = householdCashflowService.avgMonthlyExpense(me.getFamilyId());
            savMedian = householdCashflowService.medianMonthlySavings(me.getFamilyId());
            savRateDec = householdCashflowService.currentSavingsRate(me.getFamilyId());
            model.addAttribute("savingsLabels", savLabels);
            model.addAttribute("savingsIncome", savIncome);
            model.addAttribute("savingsExpense", savExpense);
            model.addAttribute("savingsMonthlyMedian", savMedian);
            model.addAttribute("savingsRate", savRateDec);
            // v1.12 FR-353 · |储蓄率| > 500% = 收入分母太小(prod 见过 −2383%)→ 显示层降级 + 补录入口。
            //   值本身照旧进 model(tooltip 里还要说原值),只是 KPI 位不再摆那个数字。
            model.addAttribute("savingsRateInsufficient", MetricDisplay.ratioAbsurd(savRateDec));
            model.addAttribute("avgMonthlyExpense", savAvgExp);
            model.addAttribute("avgMonthlyIncome", savAvgInc);
            model.addAttribute("savingsFilledMonths", ratio[0]);
            model.addAttribute("savingsTotalMonths", ratio[1]);
            model.addAttribute("savingsAvailable", ratio[0] > 0);
            model.addAttribute("goalsProgress", goalProgressService.computeAll(me.getFamilyId()));
            // stash for tooltip
            savAvail = ratio[0] > 0;
            savFilled = ratio[0];
            savTotal = ratio[1];
            for (BigDecimal x : savIncome) if (x != null) savSumInc = savSumInc.add(x);
            for (BigDecimal x : savExpense) if (x != null) savSumExp = savSumExp.add(x);
            if (!series.isEmpty()) {
                var latest = series.get(series.size() - 1);
                savLatestInc = latest.income();
                savLatestExp = latest.expense();
            }
        } catch (Exception e) {
            model.addAttribute("savingsAvailable", false);
            model.addAttribute("savingsFilledMonths", 0);
            model.addAttribute("savingsTotalMonths", 0);
            model.addAttribute("savingsLabels", List.of());
            model.addAttribute("savingsIncome", List.of());
            model.addAttribute("savingsExpense", List.of());
            model.addAttribute("goalsProgress", List.of());
            // v1.12 FR-353 · 异常分支也要把这个 flag 落上:模板里它和 savingsRate 成对使用,
            //   缺了会变成「模板取到 null 再做布尔判断」——那是另一种 500。
            model.addAttribute("savingsRateInsufficient", false);
            savAvail = false;
        }

        // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· KPI 区 viewCurrency · 储蓄区本位币
        // v1.6.29 修 · 这里原先取 `trend`(= netWorthTrendExOpening,剔除累计开账基线,给财富水位用),
        //   而 XIRR 用的是真实 netWorth → tooltip 显示的端点与指标实际输入是两套数,
        //   且该序列首点按构造恒为 0(首期全部账户都算"首次出现")→ 长年显示「期初净资产 −¥0」。
        //   改取 netWorthTrend(与 familyXirr 同源);两者之差就是累计开账基线,单列进 tooltip 说清。
        // v1.6.30 · 只取 XIRR 真正用到的期(已关账)· 趋势图本身仍画全部期(含填报中),
        //   但 tooltip 里的「期初 → 期末」必须是参与求解的首末两期,否则口径串台。
        java.util.Set<Long> retIds = new java.util.HashSet<>(slice.returnPeriodIds());
        List<TrendPoint> nwTrend = factViewService.netWorthTrend(slice).stream()
                .filter(tp -> retIds.contains(tp.periodId()))
                .toList();
        BigDecimal firstNW = nwTrend.isEmpty() ? null : nwTrend.get(0).value();
        BigDecimal lastNW = nwTrend.isEmpty() ? null : nwTrend.get(nwTrend.size() - 1).value();
        String firstLabel = nwTrend.isEmpty() ? null : nwTrend.get(0).label();
        String lastLabel = nwTrend.isEmpty() ? null : nwTrend.get(nwTrend.size() - 1).label();
        // v1.6.30 · lastNW 已改成「最后一个已关账期」,这里的被减项必须取同一期,
        //   否则拿"末期净资产 − 另一期的剔基线净资产"相减,累计开账基线会算错一期的量。
        BigDecimal lastExOpening = trend.stream()
                .filter(tp -> retIds.contains(tp.periodId()))
                .map(TrendPoint::value)
                .reduce((a, b) -> b)
                .orElse(null);
        BigDecimal cumOpeningBaseline = (lastNW == null || lastExOpening == null)
                ? BigDecimal.ZERO
                : lastNW.subtract(lastExOpening);
        BigDecimal bmTotalBal = bmInputs.stream()
                .map(BenchmarkAggregator.BenchmarkInput::balanceBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumNetInflow = lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow();
        BigDecimal cumPnl = lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl();
        var reportsInputs = new com.family.finance.service.explain.MetricExplainService.ReportsMetricInputs(
                viewCurrency, family.getBaseCurrency(),
                firstNW, lastNW, firstLabel, lastLabel,
                slice.returnPeriodIds().size(), decomposition.size(),
                familyXirrDecimal, familyTwrDecimal,
                cumNetInflow, cumPnl,
                cumOpeningBaseline,
                familyBenchmarkPct, bmInputs.size(), bmTotalBal,
                savAvail, savFilled, savTotal,
                savSumInc, savSumExp, savAvgInc, savAvgExp,
                savLatestInc, savLatestExp, savRateDec, savMedian);
        model.addAttribute("calc", metricExplain.reports(reportsInputs));
    }

    // v0.4 FR-60b · 砍 sankeyNodes / sankeyLinks(收入流向桑基图已删)

    /**
     * v0.4 FR-62b · AI 调仓建议视图(嵌入 model.rebalanceAdvice)。
     */
    public record RebalanceAdviceView(
        String narrative,
        java.util.List<java.util.Map<String, Object>> actions,
        java.time.LocalDateTime generatedAt
    ) {}

    /**
     * Reports 锚点 = 最新一期(无论 OPEN/CLOSED)。
     * <p>2026-05-10 与 dashboard 同步修复:旧逻辑优先取最新 CLOSED,会让用户在 OPEN 新月时
     * 看到的是上个月报表,与"实时汇总"产品定位冲突。
     */
    /**
     * 报表锚定期 · v0.5.5 FR-94 改:报表 = <b>已关账账期快照</b>。
     * 锚「最近已关账(≤今天)账期」;无则退 currentOpen / 最新一期仅渲染外壳(closedSnapshot=false)。
     * <p>v0.5.1 曾改 findCurrentOpen 优先(为绕未来测试期),代价是锚到月中半填的 OPEN 期 ——
     * 导致收益/人赚被进行中空账期拖成 0、XIRR/TWR 用半填净值失真。现回归快照语义,
     * 并用 {@code findLatestClosedAsOf(≤今天)} 干净挡掉未来期,不必再靠 OPEN 兜底。</p>
     */
    private ReportsAnchorResolver.AnchorChoice resolveAnchor(long familyId) {
        return ReportsAnchorResolver.resolve(
                periodMapper.findLatestClosedAsOf(familyId, LocalDate.now()),
                periodMapper.findCurrentOpen(familyId),
                periodMapper.findLatest(familyId, 1));
    }

    private LocalDate rangeStart(String range, LocalDate anchor) {
        return switch (normalizeRange(range)) {
            case "1M" -> anchor;
            case "3M" -> anchor.minusMonths(2);
            case "6M" -> anchor.minusMonths(5);
            case "YTD" -> anchor.withDayOfYear(1);
            case "ALL" -> LocalDate.of(1970, 1, 1);
            default -> anchor.minusMonths(11);
        };
    }

    /**
     * v1.11 · 「月度收支 + 反推目标月供」的期数 —— 与时间范围一致。
     *
     * <p>这一节归在三区(趋势),而三区的口径就是 range。原来写死 12 期,导致切 3M 时
     * 上面的趋势图是 3 期、下面的收支柱是 12 期,并排读会读出错误结论(维护者第 6 条)。</p>
     */
    private int savingsWindowPeriods(String range) {
        return switch (normalizeRange(range)) {
            case "1M" -> 1;
            case "3M" -> 3;
            case "6M" -> 6;
            case "YTD" -> java.time.LocalDate.now().getMonthValue();
            case "ALL" -> 240;
            default -> 12;
        };
    }

    private String normalizeRange(String range) {
        if (range == null) {
            return "1Y";
        }
        return switch (range.toUpperCase()) {
            case "1M", "3M", "6M", "YTD", "ALL" -> range.toUpperCase();
            default -> "1Y";
        };
    }

    private List<Long> parseAccountIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<Long> ids = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .distinct()
                .toList();
        return ids.isEmpty() ? null : ids;
    }

    private String parseCurrency(String currency, String fallback) {
        if (currency == null || currency.isBlank()) {
            return fallback;
        }
        return switch (currency.toUpperCase()) {
            case "CNY", "USD", "HKD" -> currency.toUpperCase();
            default -> fallback;
        };
    }

    private String money(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        String symbol = switch (currency) {
            case "USD" -> "$";
            case "HKD" -> "HK$";
            default -> "¥";
        };
        return symbol + MONEY.format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    private String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "—";
        }
        return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
