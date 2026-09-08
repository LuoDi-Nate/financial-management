package com.family.finance.web.dashboard;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.common.MetricDisplay;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.factview.TrendPoint;
import com.family.finance.factview.WaterfallSegment;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.EntryService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.HouseholdCashflowService;
import com.family.finance.service.NavService;
import com.family.finance.service.goal.GoalProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class DashboardController {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

    private final FactViewService factViewService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    /** 仅活跃:填报完成率的分母 —— 归档的人不该再被算作"欠填报的" */
    private final MemberMapper memberMapper;
    /** v1.15 FR-382 · 含已归档:成员维度资产配置里的人名 */
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final EntryService entryService;
    private final com.family.finance.repository.SnapshotTodoMapper snapshotTodoMapper;   // v1.2 F 未填计数轻查询
    private final NavService navService;
    private final FxService fxService;
    private final com.family.finance.service.MetricPrefsService metricPrefsService;   // v0.8 可配置指标
    private final com.family.finance.service.macro.MacroBenchmarkService macroBenchmarkService; // v0.5 FR-75
    private final GoalProgressService goalProgressService;
    private final HouseholdCashflowService householdCashflowService;
    private final com.family.finance.service.config.FamilyConfigService configService;
    private final com.family.finance.service.explain.MetricExplainService metricExplain; // v0.5.3 口径真实数值
    private final com.family.finance.service.review.AttributionService attributionService; // v1.2 归因复盘
    private final com.family.finance.service.group.AccountGroupingResolver groupingResolver; // v1.20 账户维值
    private final com.family.finance.service.review.RebalancePlanService rebalancePlanService; // v1.2 计划进度 pill
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.family.finance.service.insight.AssetInsightService assetInsightService; // v0.6 资产洞察速览
    private final com.family.finance.service.group.AccountRowGrouper accountRowGrouper;       // v1.20 账户组折叠
    private final com.family.finance.service.group.AccountGroupService accountGroupService;   // v1.20 FR-486 筛选器快选
    private final com.family.finance.service.lens.LensMetaService lensMetaService; // v1.1 资产透视内嵌

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam(defaultValue = "1Y") String range,
                            @RequestParam(required = false) String asof,
                            @RequestParam(name = "accounts", required = false) List<Long> accounts,
                            @RequestParam(required = false) String currency,
                            @RequestHeader(value = "HX-Request", required = false) String htmx,
                            Model model) {
        // v0.7 FR-133 兜底:零周期(全新部署)→ 回首页引导,避免 anchorPeriod() 抛异常 500
        if (periodMapper.countByFamily(me.getFamilyId()) == 0) {
            return "redirect:/";
        }
        String accountsCsv = accounts == null || accounts.isEmpty()
                ? null
                : accounts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        populateModel(me, range, asof, accountsCsv, currency, model);
        if ("true".equalsIgnoreCase(htmx)) {
            return "dashboard/_region :: region";
        }
        lensMetaService.addMeta(me.getFamilyId(), model);   // v1.1 · 透视主体内嵌(region 刷新不重载)
        return "dashboard/index";
    }

    /** v1.2 · 归因复盘 fragment(HTMX 懒加载 · tech-design v1.2 §1.3):瀑布 + 贡献榜 + 12 期趋势 */
    @org.springframework.web.bind.annotation.GetMapping("/dashboard/attribution")
    public String attribution(@org.springframework.security.core.annotation.AuthenticationPrincipal MemberPrincipal me,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) String asof,
                              @org.springframework.web.bind.annotation.RequestParam(defaultValue = "acct") String dim,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) String currency,
                              @org.springframework.web.bind.annotation.RequestParam(value = "accounts", required = false) String accountsCsv,
                              // v1.20 FR-465 · 下钻:把这个组展开成成员账户。null = 全部折叠(默认)
                              @org.springframework.web.bind.annotation.RequestParam(value = "expand", required = false) Long expandGroupId,
                              Model model) throws Exception {
        Family family = familyService.require(me.getFamilyId());
        List<Period> allPeriods = periodMapper.findAllByFamily(me.getFamilyId());
        Period anchor = resolveAsOf(asof, allPeriods, periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null));
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        if (!attributionService.DIMS.containsKey(dim)) dim = "acct";
        FactFilter filter = new FactFilter(me.getFamilyId(), family.getPeriodType(),
                anchor.getPeriodStart().minusMonths(12), anchor.getPeriodStart(), false, accountIds, viewCurrency);
        FactSlice slice = factViewService.load(filter);
        KpiSnapshot kpis = factViewService.kpis(slice);
        // v1.18.3 · 归因锚回【用户正在看的这一期】(默认当前月),即「上月关账时点 → 当月实时」。
        //
        //   v1.18.1 曾把它挪到「最新已关账期」,那是因为进行中的期会出现
        //   「转账已登记、余额没涨」→ pnl = Δ余额(0) − 转入 = 负的转入额,
        //   排行榜把只收到一笔转入的账户列成「亏得最多」。
        //   但那是【权宜之计】—— 真正的病根是 v1.18.1 后半段修的丢钱 bug:
        //   钱没落进现金行,被估值按「持仓合计」重算时抹掉了。修完之后流水会立刻
        //   同步进余额,假亏损的根已经没了(e2e 主线 16 就是钉这条)。
        //
        //   仪表盘的分工本来就是「当月实时」(v1.10 FR-327 已定),报表页才是已关账封板。
        //   把归因留在上个月,会让同一屏出现两个月份 —— 上面的卡是本月、下面的瀑布是上月,
        //   用户拿本月的印象去对上月的数,必然看成 bug(维护者 2026-08-21 实际撞上)。
        //
        //   代价照实说:进行中的期收支常常还没录齐,未录的收入会被算进「钱赚」→ 偏高。
        //   所以页面必须给出「本月已录收入/支出」让人判断可信度(v1.10 定的做法:
        //   显示真实值 + 说明口径,而不是藏起来)。
        Long attrPeriodId = slice.lastPeriodId();
        com.family.finance.factview.CashflowBreakdown cf = factViewService.cashflowBreakdown(slice, attrPeriodId);
        java.math.BigDecimal human = (cf == null ? java.math.BigDecimal.ZERO
                : nz(cf.income()).subtract(nz(cf.expense())));
        // 四项(ΔNW / 人赚 / 钱赚 / 开账基线)必须【同一期】,否则差额全被「未归因」吸收 ——
        // 数字看着平了,错误其实藏进了兜底项。这条不因锚点变化而改变。
        var result = attributionService.attribute(me.getFamilyId(),
                slice.byPeriod().getOrDefault(attrPeriodId, List.of()),
                kpis.netWorthDelta(), human, kpis.openingBaselineLast(),
                attrPeriodId, expandGroupId);
        var grouped = com.family.finance.calc.review.AttributionEngine.groupBy(result, dim);   // v1.20 · acct 也走标签
        var trend = attributionService.trend(me.getFamilyId(), slice, dim, 12, expandGroupId);
        model.addAttribute("attr", result);
        model.addAttribute("attrGroupedJson", objectMapper.writeValueAsString(grouped));
        model.addAttribute("attrTrendJson", objectMapper.writeValueAsString(
                trend.stream().map(t -> java.util.Map.of("p", t.periodLabel(), "g", t.byGroup())).toList()));
        model.addAttribute("attrDims", com.family.finance.service.review.AttributionService.DIMS);
        model.addAttribute("attrDim", dim);
        model.addAttribute("attrAsof", anchor.getPeriodStart().toString());
        // v1.18.3 · 页面要说清「看的是哪一期 + 这个数有多可信」。
        //   锚回当月之后,风险从「月份看错」变成「收支没录齐导致钱赚偏高」,
        //   所以除了月份,还要把【本月已录收入/支出】给出来 —— 录得越少,钱赚越虚高。
        model.addAttribute("attrAnchorMonth", slice.periodStartOf(attrPeriodId));
        model.addAttribute("attrFilingInProgress", slice.filingInProgress());
        model.addAttribute("attrLiveIncome", kpis.liveIncome());
        model.addAttribute("attrLiveExpense", kpis.liveExpense());
        model.addAttribute("attrCurrency", viewCurrency);
        model.addAttribute("attrAccountsCsv", accountsCsv == null ? "" : accountsCsv);
        // v1.20 · 页面要知道:这一期有哪些维值是「组」(可以点开)、当前点开的是谁
        model.addAttribute("attrGroups", groupingResolver.labelsFor(me.getFamilyId(), attrPeriodId, expandGroupId)
                .values().stream().filter(com.family.finance.service.group.AccountGroupingResolver.Label::grouped)
                .collect(java.util.stream.Collectors.toMap(
                        com.family.finance.service.group.AccountGroupingResolver.Label::value,
                        com.family.finance.service.group.AccountGroupingResolver.Label::groupId,
                        (x, y) -> x)));
        model.addAttribute("attrExpand", expandGroupId);
        model.addAttribute("anchorPeriod", anchor);
        return "dashboard/_attribution :: section";
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) { return v == null ? java.math.BigDecimal.ZERO : v; }

    private void populateModel(MemberPrincipal me, String range, String asof, String accountsCsv, String currency, Model model) {
        Family family = familyService.require(me.getFamilyId());
        // v0.8 FR-144:观察账期 as-of(默认最新,可选历史月)→ 作 rangeEnd,点状 KPI 随之
        List<Period> allPeriods = periodMapper.findAllByFamily(me.getFamilyId());
        Period anchor = resolveAsOf(asof, allPeriods, periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null));
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        // v1.2 F · momYoy 复用条件:显示窗口已覆盖 asof−12 月(默认 1Y/ALL 命中)→ 免第二次 load;
        //   短窗口(1M/3M/6M/YTD)保持独立 load,主 slice 范围不动 → 趋势等显示零回归
        java.time.LocalDate displayStart = rangeStart(range, anchor.getPeriodStart());
        java.time.LocalDate momStart = anchor.getPeriodStart().minusMonths(12);
        boolean momReuse = displayStart == null || !displayStart.isAfter(momStart);
        FactFilter filter = new FactFilter(
                me.getFamilyId(),
                family.getPeriodType(),
                displayStart,
                anchor.getPeriodStart(),
                false,
                accountIds,
                viewCurrency
        );
        // BUG-FIX(2026-05-11 · critical):FactMapper.queryBase SQL 算 fx_to_base 时,
        // 任一非 base 账户币种 + 当期没 fx_rate 行 → 落 ELSE 1.0 兜底 → USD 余额被当 CNY 直接累加。
        // v0.8 BUG-FIX(v08-CCY-INV-2):MoM/YoY/趋势/TWR/本月资产收益率吃多期 endBalanceBase,
        //   只 ensure anchor 一期 → 上期/窗口期缺汇率未换算 → 切币种比值乱漂。改 ensure ≤anchor 全期。
        List<Long> ensurePeriodIds = allPeriods.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .map(Period::getId)
                .toList();
        fxService.ensureForAccountCurrencies(me.getFamilyId(), family.getBaseCurrency(), ensurePeriodIds);

        // BUG-FIX(2026-05-10):viewCurrency 切换 → fx_rate 缺则即时拉 / 兜底 toast
        String requestedCurrency = viewCurrency;
        boolean fxFallback = false;
        if (!viewCurrency.equalsIgnoreCase(family.getBaseCurrency())) {
            boolean hasRate = fxService.getOrFetchRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, anchor.getId()).isPresent();
            if (!hasRate) {
                viewCurrency = family.getBaseCurrency();
                fxFallback = true;
            } else {
                // v0.8 BUG-FIX(v08-CCY-INV-2):视图币种可能无账户(纯展示,如 HKD)→ ensureForAccountCurrencies 不覆盖。
                //   三角换算需每期都有 base→view 行,否则历史期落 1.0 → 切币种比值漂移。全窗口补 base→view。
                fxService.ensureRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, ensurePeriodIds);
            }
        }
        FactFilter effectiveFilter = filter.viewCurrency().equals(viewCurrency)
                ? filter
                : new FactFilter(filter.familyId(), filter.periodType(), filter.rangeStart(),
                                 filter.rangeEnd(), filter.includeArchived(), filter.accountIds(), viewCurrency);

        FactSlice slice = factViewService.load(effectiveFilter);
        KpiSnapshot kpis = factViewService.kpis(slice);
        List<TrendPoint> trend = factViewService.netWorthTrend(slice);
        List<WaterfallSegment> waterfall = factViewService.incomeExpenseWaterfall(slice);
        List<AllocationSlice> allocation = factViewService.allocationByType(slice, slice.lastPeriodId());
        List<AccountPerformance> accountRows = factViewService.accountPerformance(slice);
        List<Account> allAccounts = accountMapper.findActiveByFamily(me.getFamilyId());
        Period currentOpen = periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null);
        // v0.10 FR-165/166/167 · 本期「人赚 vs 钱赚」拆解 + 实时收支趋势(view 币种 · 含进行中本月)
        com.family.finance.factview.CashflowBreakdown cfBreak =
                factViewService.cashflowBreakdown(slice, slice.lastPeriodId());
        int cfFilled = householdCashflowService.filledMembersForPeriod(slice.lastPeriodId());
        int cfTotal = memberMapper.countActiveByFamily(me.getFamilyId());
        CashflowSplitView cashflowSplit = CashflowSplitView.of(kpis.netWorthDelta(), cfBreak, cfFilled, cfTotal,
                kpis.openingBaselineLast());   // v0.13 · 开账基线单列第三项
        List<com.family.finance.factview.CashflowPoint> cashflowSeries =
                factViewService.cashflowSeries(slice, 6, currentOpen == null ? null : currentOpen.getId());
        // v0.8 FR-145:MoM/YoY 用 [as-of−12, as-of] 最小窗口算,与显示窗口解耦,缺对比期显数据不足
        com.family.finance.factview.MomYoy momYoy = momReuse
                ? factViewService.momYoy(slice)   // v1.2 F · 复用主 slice(窗口已覆盖 12 月)
                : factViewService.momYoy(new FactFilter(me.getFamilyId(), family.getPeriodType(),
                        momStart, anchor.getPeriodStart(), false, accountIds, viewCurrency));

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("range", normalizeRange(range));
        model.addAttribute("currency", viewCurrency);
        model.addAttribute("ranges", List.of("1M", "3M", "6M", "YTD", "1Y", "ALL"));
        // v0.8:as-of 账期选择(点状 KPI 随之)+ MoM/YoY
        model.addAttribute("asof", anchor.getPeriodStart().toString());
        model.addAttribute("periods", allPeriods);
        model.addAttribute("momYoy", momYoy);
        // v0.8 FR-149:账户列表按勾选的指标显示(必选项恒在)
        model.addAttribute("acctMetrics", metricPrefsService.enabled(family.getMetricPrefs(), "account"));
        model.addAttribute("famMetrics", metricPrefsService.enabled(family.getMetricPrefs(), "family"));
        model.addAttribute("currencies", List.of("CNY", "USD", "HKD"));
        model.addAttribute("accountsCsv", accountsCsv == null ? "" : accountsCsv);
        model.addAttribute("selectedAccountIds", accountIds == null ? java.util.Collections.<Long>emptyList() : accountIds);
        model.addAttribute("selectedAccountCount", accountIds == null ? allAccounts.size() : accountIds.size());
        model.addAttribute("allAccounts", allAccounts);
        model.addAttribute("anchorPeriod", anchor);
        model.addAttribute("currentOpen", currentOpen);
        model.addAttribute("rebalancePlanView", rebalancePlanService.activePlan(me.getFamilyId()));   // v1.2 洞察条 pill
        // v1.2 F · 模板只消费"未填个数" → 由全行装配(listRows 含流水,~百ms)换成 todo 计数轻查询
        model.addAttribute("pendingCount", currentOpen == null ? 0
                : snapshotTodoMapper.countPendingByPeriod(currentOpen.getId()));

        // v0.3 FR-50d · 目标进度条带数据(失败容忍 · 不阻塞 dashboard 渲染)
        //
        // v1.18.7 · 这一条**刻意不跟随页面视图**,与洞察条相反 —— 原因是正确性,不是省事:
        //   目标的目标值(FIRE 所需本金 / 教育金 / 应急储备)是**以家庭本位币存的绝对金额**,
        //   进度 = 家庭净资产 ÷ 目标值。把仪表盘的切片传进来会同时踩两个坑:
        //     · 切到 USD → 分子变成 USD 金额,分母还是本位币目标 → 进度凭空翻几倍
        //     · 筛掉一半账户 → 分子腰斩,而目标本来就是全家庭的 → 进度腰斩
        //   而且目标是**朝前看**的(还差几年到 FIRE),按历史 as-of 去算也没有意义。
        //   所以这里保持「全家庭 · 本位币 · 此刻」,并由页面把这个口径**明说出来**
        //   —— 与其让它悄悄不一致,不如让它公开地不一致。
        try {
            model.addAttribute("goalsProgress", goalProgressService.computeAll(me.getFamilyId()));
        } catch (Exception e) {
            model.addAttribute("goalsProgress", List.of());
        }
        // 只有在「用户确实改了视图」时才提示,否则是噪音
        model.addAttribute("goalsViewIndependent",
                !viewCurrency.equalsIgnoreCase(family.getBaseCurrency())
                        || accountIds != null
                        || (currentOpen != null && !currentOpen.getId().equals(anchor.getId())));

        model.addAttribute("kpis", kpis);
        // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· viewCurrency 口径
        model.addAttribute("calc", metricExplain.dashboard(kpis, allocation, accountRows, viewCurrency));
        model.addAttribute("kpiNetWorth", money(viewCurrency, kpis.netWorth()));
        model.addAttribute("kpiAssets", money(viewCurrency, kpis.totalAssets()));
        model.addAttribute("kpiLiabilities", money(viewCurrency, kpis.totalLiabilities()));
        // v1.6 UED review A7 · 与 checkup 同口径的异常值兜底 + 小数位收敛(此前裸 toPlainString 会甩一长串小数)
        model.addAttribute("kpiEmergency", emergencyLabel(kpis.emergencyFundMonths()));
        model.addAttribute("kpiDebtRatio", percent(kpis.debtToAssetRatio()));
        model.addAttribute("kpiDelta", moneyDelta(viewCurrency, kpis.netWorthDelta()));
        // v0.3:优先用 period.total_*_input(用户在 /entry 第一步填的家庭口径)· fallback v0.2 cash_flow
        // v1.12 FR-353:|储蓄率| > 500% = 收入分母太小(prod 见过 −2383%)· 显示层降级成一句话 + 补录入口,
        //   计算侧一个字不动(见 MetricDisplay 的注释)。正常范围的值走原来的 percent(),渲染逐字不变。
        // v1.18.7 · 储蓄率必须连【哪一期】一起显示。它此前叫「本期储蓄率」,而实际取的是
        //   「最近一个有 PMC 记录的期」或(兜底时)「最新已关账期」—— 两者都可能不是本期,
        //   却和实时的净资产/环比挤在同一句话里。维护者定:不改口径,把账期标出来。
        var savings = householdCashflowService.savingsRateView(me.getFamilyId());
        BigDecimal savingsRateRaw = savings.rate();
        boolean savingsRateInsufficient = MetricDisplay.ratioAbsurd(savingsRateRaw);
        model.addAttribute("savingsRateInsufficient", savingsRateInsufficient);
        model.addAttribute("savingsRate",
                savingsRateInsufficient ? MetricDisplay.INSUFFICIENT : percent(savingsRateRaw));
        model.addAttribute("savingsRatePeriod", savings.periodStart());
        // 是不是「本期」——是就说本期,不是就点名是哪个账期,不含糊
        model.addAttribute("savingsRateIsCurrent",
                currentOpen != null && savings.periodId() != null
                        && currentOpen.getId().equals(savings.periodId()));
        // v0.3 新增:月均支出 / 月均收入 KPI(per 用户反馈 2026-05-13 · 用最新口径)
        model.addAttribute("avgMonthlyExpense", money(viewCurrency, householdCashflowService.avgMonthlyExpense(me.getFamilyId())));
        model.addAttribute("avgMonthlyIncome", money(viewCurrency, householdCashflowService.avgMonthlyIncome(me.getFamilyId())));
        int[] filled = householdCashflowService.filledMonthRatio(me.getFamilyId());
        model.addAttribute("cashflowFilled", filled[0]);
        model.addAttribute("cashflowTotal", filled[1]);
        // v0.10 · 本期拆解卡 + 实时收支趋势(金额按 view 币种符号预格式化,结构/符号/宽度走视图模型)
        model.addAttribute("cashflowSplit", cashflowSplit);
        model.addAttribute("cashflowSeries", cashflowSeries);
        // v0.11.6 · 收支趋势仅在「有非零收支」时出图;近月全零(未填 PMC)→ 前端显空态细条,不留空白大卡
        boolean cashflowSeriesHasData = cashflowSeries != null && cashflowSeries.stream().anyMatch(p ->
                (p.income() != null && p.income().signum() != 0) || (p.expense() != null && p.expense().signum() != 0));
        model.addAttribute("cashflowSeriesHasData", cashflowSeriesHasData);
        model.addAttribute("cfDeltaLabel", moneyDelta(viewCurrency, cashflowSplit.deltaNetWorth()));
        model.addAttribute("cfRenLabel", moneyDelta(viewCurrency, cashflowSplit.renZhuan()));
        model.addAttribute("cfQianLabel", moneyDelta(viewCurrency, cashflowSplit.qianZhuan()));
        model.addAttribute("cfOpeningLabel", moneyDelta(viewCurrency, cashflowSplit.openingBaseline()));  // v0.13
        model.addAttribute("cfIncomeLabel", money(viewCurrency, cashflowSplit.income()));
        model.addAttribute("cfExpenseLabel", money(viewCurrency, cashflowSplit.expense()));

        model.addAttribute("trend", trend);
        model.addAttribute("allocation", allocation);
        model.addAttribute("waterfall", waterfall);
        // v0.6 · 资产洞察速览(仅硬数据 · 不调 LLM · 保持 dashboard 轻快)· compute 永不抛
        // v1.18.7 · 洞察条改吃【这一页的切片】—— 此前它自己 loadDefault(本位币/全账户/按今天),
        //   于是切币种、筛账户、选历史 as-of 时,上面 KPI 变了、洞察条一动不动。
        model.addAttribute("insight", assetInsightService.compute(me.getFamilyId(), slice));
        /* v1.20 FR-482/483 · 账户级指标行按组折叠。
         * 这一处同时修好三个组件:「按账户分布」横条图(它吃同一份 JSON,一行 JS 都不用动)、
         * dashboard 账户列表、reports 账户级收益表。
         * 注意【原始 accountRows 仍要留着】给上面两个调用方:
         *   · metricExplain —— 解释的是账户级怎么算出来的
         *   · computeMemberAllocation —— 按主理人聚合,而组行的 accountId 是 null,
         *     传折叠后的进去会让组里的钱【从成员饼图里整片消失】。「按成员分布」本就不该跟组走。 */
        var foldedRows = accountRowGrouper.fold(me.getFamilyId(), anchor.getId(), null, accountRows);
        model.addAttribute("accountRows", foldedRows);                              // 顶层:图 + 计数
        model.addAttribute("accountRowsFlat", accountRowGrouper.flatten(foldedRows)); // 表:组行 + 隐藏成员行
        model.addAttribute("hasGroups", groupingResolver.hasAnyGroup(me.getFamilyId()));
        /* v1.20 FR-486 · 账户多选筛选器的「按分组快选」。
         * 复查组件清单时发现漏了这个组件 —— 它不在长文目录里,所以第一遍整个没看见。
         * 建了「日常周转」之后,想「只看这一组」得手动勾 5 个,账户一多就会勾错。
         * 【刻意只做前端快选,不改筛选语义】:点一下把该组成员勾上,URL 仍是 accounts=1,2,3…
         * 组不是账户,把它塞进「哪些账户参与计算」的语义里只会让口径变糊。 */
        var groupPicks = new java.util.ArrayList<java.util.Map<String, Object>>();
        var gMembers = accountGroupService.membersByGroup(me.getFamilyId());
        for (var g : accountGroupService.list(me.getFamilyId())) {
            java.util.List<Long> ids = gMembers.getOrDefault(g.getId(), java.util.List.of());
            if (ids.isEmpty()) continue;
            groupPicks.add(java.util.Map.of("name", g.getName(), "ids", ids));
        }
        model.addAttribute("groupPicks", groupPicks);
        model.addAttribute("fxFallback", fxFallback);
        model.addAttribute("requestedCurrency", requestedCurrency);

        // v0.4 FR-61a / v0.5 FR-75 · CPI 假设默认改用真实剔极端值几何均值(替代硬编码 2%)
        // 用户在 dashboard 切换器显式选过 → 用其值;否则用宏观真实 CPI;宏观缺失再退 2%。
        BigDecimal realCpi = macroBenchmarkService.cpiAverages().defaultValue();
        model.addAttribute("cpiAssumption", family.getCpiAssumption() != null
            ? family.getCpiAssumption()
            : (realCpi != null && realCpi.signum() > 0 ? realCpi : new BigDecimal("2.00")));
        // v0.5 FR-75 · M2 地位线(剔极端值几何均值)· 前端加一条参考线 · 详看 /reports 财富水位
        BigDecimal realM2 = macroBenchmarkService.m2Averages().defaultValue();
        model.addAttribute("m2Assumption", realM2 != null && realM2.signum() > 0 ? realM2 : new BigDecimal("8.00"));

        // v0.4 FR-60a · 月储蓄能力(从 v0.3 储蓄区拉)+ 收 KPI
        model.addAttribute("monthlySavingsCapacity",
            money(viewCurrency, householdCashflowService.medianMonthlySavings(me.getFamilyId())));

        // v0.4.2 · 「钱赚的」二分指标 · 本月资产收益(剔除外部现金流)
        // 顶替"月储蓄能力"为第 5 KPI · 储蓄能力保留在 /reports 储蓄区
        // v1.10 FR-327 · 仪表盘按两页分工代表「当月实时」→ 这一格改用 live 口径(锚当前期)。
        //   v1.6.30 起它锚的是「最新已关账期」,那是为了躲开一个 P0:进行中期收支未录齐时,
        //   (期末−期初−净流入) 会把还没录的工资整块算成投资收益。维护者拍板:显示实时真实值 +
        //   把口径和偏差方向讲清楚,而不是藏起来(tech-design v1.10 §6.2)。
        //   **报表页封板快照仍用 monthlyPnl*(锚已关账期),两套并存互不影响。**
        java.math.BigDecimal shownPnl = kpis.liveMonthlyPnlAmount() != null
            ? kpis.liveMonthlyPnlAmount() : kpis.monthlyPnlAmount();
        java.math.BigDecimal shownPct = kpis.liveMonthlyInvestReturnPct() != null
            ? kpis.liveMonthlyInvestReturnPct() : kpis.monthlyInvestReturnPct();
        model.addAttribute("monthlyPnlMoney",
            shownPnl == null ? "—"
                : (shownPnl.signum() >= 0 ? "+" : "−")
                  + money(viewCurrency, shownPnl.abs()).replaceFirst("^[+−]", ""));
        model.addAttribute("monthlyPnlPctLabel",
            shownPct == null ? "—" : String.format("%+.2f%%", shownPct.doubleValue() * 100));
        // 这个实时值有多可信,取决于本期收支录了多少 —— 一笔没录时偏差最大(全部 ΔNW 落进"钱赚")
        boolean noFlowYet = nzz(kpis.liveIncome()).signum() == 0 && nzz(kpis.liveExpense()).signum() == 0;
        model.addAttribute("liveReturnNoFlow", noFlowYet);
        model.addAttribute("liveReturnFlowLabel",
            noFlowYet ? "尚未录入任何收支"
                : ("已录收入 " + money(viewCurrency, nzz(kpis.liveIncome())).replaceFirst("^[+−]", "")
                   + " · 支出 " + money(viewCurrency, nzz(kpis.liveExpense())).replaceFirst("^[+−]", "")));

        // v0.4 FR-62c · 应急金不闲置评估(用 LIQUID 类账户合计 vs 应急需求 × 1.5x)
        Long lastPid = slice.lastPeriodId();
        BigDecimal liquidAssets = slice.rows().stream()
            .filter(r -> java.util.Objects.equals(r.periodId(), lastPid))
            .filter(r -> r.accountLiquidity() == com.family.finance.domain.account.AccountLiquidity.LIQUID)
            .map(r -> r.endBalanceBase() == null ? BigDecimal.ZERO : r.endBalanceBase())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgExpense = householdCashflowService.avgMonthlyExpense(me.getFamilyId());
        // v0.4.18 · 应急月数 + buffer 倍率改读 ConfigService(默认 6 月 / 1.5x)
        int emergencyMonths = configService.getInt(me.getFamilyId(),
            com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,
            com.family.finance.calc.LiquiditySurplus.DEFAULT_EMERGENCY_MONTHS);
        BigDecimal liquidMultiplier = BigDecimal.valueOf(configService.getDouble(me.getFamilyId(),
            com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
        var liquidSurplus = com.family.finance.calc.LiquiditySurplus.evaluate(
            liquidAssets, avgExpense, emergencyMonths, liquidMultiplier);
        model.addAttribute("liquidSurplus", liquidSurplus);
        model.addAttribute("liquidSurplusMoney", money(viewCurrency, liquidSurplus.surplus()));

        // v1.18.7 · 进行中的那一期在图上点名 —— 与收支趋势的「· 进行中」同一套做法
        model.addAttribute("trendLabels",
                trend.stream().map(t -> t.live() ? t.label() + " · 进行中" : t.label()).toList());
        model.addAttribute("trendValues", trend.stream().map(TrendPoint::value).toList());
        model.addAttribute("incomeValues", waterfall.stream().map(WaterfallSegment::income).toList());
        model.addAttribute("expenseValues", waterfall.stream().map(WaterfallSegment::expense).toList());
        model.addAttribute("savingsValues", waterfall.stream().map(this::savingsRatePoint).toList());
        model.addAttribute("allocationLabels", allocation.stream().map(AllocationSlice::label).toList());
        model.addAttribute("allocationValues", allocation.stream().map(AllocationSlice::value).toList());

        // v0.2.1(2026-05-11)· 按成员维度的资产分布饼图(LOAN 不计,跟资产配置一致)
        var memberAlloc = computeMemberAllocation(
                memberDirectory.nameMap(me.getFamilyId()), allAccounts, accountRows);
        model.addAttribute("memberAllocationLabels", memberAlloc.keySet().stream().toList());
        model.addAttribute("memberAllocationValues", memberAlloc.values().stream().toList());
    }

    /**
     * 按 account.primary_owner_member_id 聚合资产(LOAN 排除)。
     * NULL → "共同";有值 → member.display_name(查不到的 fallback "成员#{id}")。
     *
     * <p>v1.15 FR-382 · {@code nameById} 由调用方从 {@link com.family.finance.service.member.MemberDirectory}
     * 取(<b>含已归档</b>):账户不会因为主理人被归档就换主人,用仅活跃列表的话这块饼会从「张三」
     * 变成「成员#7」—— 钱没动,名字却丢了。
     *
     * <p>做成 static + 显式传映射,不是为了好看:这样
     * {@code MemberArchiveMoneyInvarianceTest} 才能不装配 20 个依赖就把
     * 「归档不动钱、也不丢名字」这条不变量钉住。
     */
    static java.util.LinkedHashMap<String, java.math.BigDecimal> computeMemberAllocation(
            java.util.Map<Long, String> nameById, List<Account> allAccounts,
            List<AccountPerformance> accountRows) {
        java.util.Map<Long, Account> accById = allAccounts.stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, java.util.function.Function.identity()));
        java.util.LinkedHashMap<String, java.math.BigDecimal> byOwner = new java.util.LinkedHashMap<>();
        for (AccountPerformance ap : accountRows) {
            if (ap.accountType() == null) continue;
            if (com.family.finance.factview.FactProjector.classOf(ap.accountType()) == AccountClass.LIABILITY) continue;
            if (ap.currentValue() == null) continue;
            Account acc = accById.get(ap.accountId());
            if (acc == null) continue;
            String label = acc.getPrimaryOwnerMemberId() == null
                    ? "共同"
                    : nameById.getOrDefault(acc.getPrimaryOwnerMemberId(), "成员#" + acc.getPrimaryOwnerMemberId());
            byOwner.merge(label, ap.currentValue(), java.math.BigDecimal::add);
        }
        return byOwner;
    }


    /**
     * Dashboard 锚点 = 最新一期(无论 OPEN/CLOSED)。
     * <p>v0.1 的旧实现优先取「最新 CLOSED」,导致开了新月后 dashboard 始终显示上个月数据 ——
     * 这与"实时汇总"产品定位冲突,2026-05-10 改为永远取最新一期。
     */
    private Period anchorPeriod(long familyId) {
        return periodMapper.findLatest(familyId, 1).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("尚未创建周期"));
    }

    /**
     * v0.8:解析观察账期 as-of。命中 asof 串则用之;否则默认 = **当前账期**(与主页/填报一致):
     * 优先当前 OPEN 期,否则取最近一个已开始(period_start ≤ 今天)的期,
     * 都没有再退回 max —— 避免锚到 dev/未来的 stray 期(如 beta 的 2034-01)。
     */
    /**
     * v1.6 UED review A7 · 紧急储备月数展示。
     * 分母(月均支出)接近 0 时会算出荒谬值,收敛为语义值;正常值保留 1 位小数。
     * 阈值与 {@code FamilyDiagnose.EMERGENCY_OUTLIER_MONTHS} 保持一致(36 月)。
     */
    /** v1.10 · 规则移到 SealedSnapshot.emergencyLabel,两页共用一份(报表页也要显示这个 KPI)。 */
    private static java.math.BigDecimal nzz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    private static String emergencyLabel(java.math.BigDecimal months) {
        return com.family.finance.service.report.SealedSnapshot.emergencyLabel(months);
    }

    private Period resolveAsOf(String asof, List<Period> all, Period openPeriod) {
        if (asof != null && !asof.isBlank()) {
            for (Period p : all) {
                if (p.getPeriodStart() != null && p.getPeriodStart().toString().equals(asof)) return p;
            }
        }
        if (openPeriod != null) return openPeriod;
        java.time.LocalDate today = java.time.LocalDate.now();
        return all.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(today))
                .max(java.util.Comparator.comparing(Period::getPeriodStart))
                .orElseGet(() -> all.stream().max(java.util.Comparator.comparing(Period::getPeriodStart))
                        .orElseThrow(() -> new IllegalStateException("尚未创建周期")));
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

    private BigDecimal savingsRatePoint(WaterfallSegment segment) {
        if (segment.income().signum() == 0) {
            return null;
        }
        return segment.income().subtract(segment.expense())
                .divide(segment.income(), 6, RoundingMode.HALF_EVEN)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_EVEN);
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

    private String moneyDelta(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        return (amount.signum() >= 0 ? "+" : "") + money(currency, amount);
    }

    private String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "—";
        }
        return ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
