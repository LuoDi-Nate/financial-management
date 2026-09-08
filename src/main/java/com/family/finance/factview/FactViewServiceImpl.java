package com.family.finance.factview;

import com.family.finance.calc.MaxDrawdownCalculator;
import com.family.finance.calc.NavSeriesBuilder;
import com.family.finance.calc.TwrCalculator;
import com.family.finance.calc.XirrCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FactViewServiceImpl implements FactViewService {
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FactMapper factMapper;
    private final FamilyMapper familyMapper;
    /** v0.4.3 B2 修复 · 月均支出/收入统一源 · PMC(成员级)优先 · cash_flow fallback */
    private final com.family.finance.repository.PeriodMemberCashflowMapper periodMemberCashflowMapper;
    /** v0.8 · 账户级预实分析:查账户预期收益 + 品类 benchmark(账户少,按需查)*/
    private final com.family.finance.repository.AccountMapper accountMapper;
    private final com.family.finance.service.ProductCategoryService productCategoryService;
    /** v0.13 · 开账基线检测(账户首次出现) */
    private final com.family.finance.repository.SnapshotMapper snapshotMapper;
    /** v1.12 FR-350 · 锚期定格属性(报表页的「预实」列按定格值算,仪表盘仍按当前值) */
    private final com.family.finance.repository.PeriodAccountAttrMapper periodAccountAttrMapper;
    /**
     * v1.8 · 家庭支出的唯一口径入口(逐笔 > 总额,不相加;排除现金调整)。
     * 只依赖 mapper,注入到这里不会成环。见 ExpenseLedgerService 类注释。
     */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger;

    @Override
    public FactSlice loadDefault(Long familyId) {
        Family family = familyOf(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));
        LocalDate end = LocalDate.now().withDayOfMonth(1);
        LocalDate start = end.minusMonths(11);
        return load(new FactFilter(familyId, family.getPeriodType(), start, end, false, null, family.getBaseCurrency()));
    }

    @Override
    public FactSlice load(FactFilter filter) {
        // v1.12 FR-352 · 同一 GET 请求内同一筛选只查一次(见 FactLoadCache 类注释)
        FactLoadCache cache = beginLoad();
        FactSlice memo = cache.slices.get(filter);
        if (memo != null) return memo;
        // v0.8 BUG-FIX(v08-CCY-INV-2):传家庭本位币给 SQL,fx_to_base 走「经本位币三角换算」
        // (acct→view = rate(base→view)/rate(base→acct)),支持「视图币种 ≠ 本位币 且账户为第三币种」。
        String baseCurrency = familyOf(filter.familyId())
                .map(com.family.finance.domain.family.Family::getBaseCurrency)
                .orElse(filter.viewCurrency());
        List<AccountPeriodFact> rows = factMapper.queryBase(filter, baseCurrency).stream()
                .map(FactProjector::project)
                .toList();
        Map<Long, LocalDate> periodStartById = rows.stream()
                .collect(Collectors.toMap(AccountPeriodFact::periodId, AccountPeriodFact::periodStart, (a, b) -> a));
        List<Long> periodIds = periodStartById.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        Long lastPeriodId = periodIds.isEmpty() ? null : periodIds.getLast();
        // v1.6.30 · 另查窗口内已关账期:queryBase 不过滤 status(存量指标要看进行中的期),
        // 收益类指标据此锚到最新 CLOSED 期。取交集并按 periodIds 顺序排,保证升序且不含窗口外的期。
        java.util.Set<Long> closed = new java.util.HashSet<>(factMapper.findClosedPeriodIds(filter));
        List<Long> closedPeriodIds = periodIds.stream().filter(closed::contains).toList();
        // v1.11 性能 · 一次查全「每个账户首次出现在哪一期」,替掉 per-period 的 N+1(见 firstAppearingIn 注释)
        // v1.12 FR-352 · 结果与查哪一期、哪个筛选都无关 → 同一请求内按家庭只查一次(原来一次请求查 10 次)
        cache.firstAppear.computeIfAbsent(filter.familyId(), fid -> {
            java.util.Map<Long, java.util.Set<Long>> byPeriod = new java.util.HashMap<>();
            for (var fa : snapshotMapper.firstAppearanceByAccount(fid)) {
                byPeriod.computeIfAbsent(fa.periodId(), k -> new java.util.HashSet<>()).add(fa.accountId());
            }
            return byPeriod;
        });
        FactSlice slice = new FactSlice(filter, rows, periodIds, lastPeriodId, closedPeriodIds);
        if (cache.memoSlices()) cache.slices.put(filter, slice);
        return slice;
    }

    @Override
    public KpiSnapshot kpis(FactSlice slice) {
        if (slice.lastPeriodId() == null) {
            return new KpiSnapshot(zero(), zero(), zero(), null, null, null, null);
        }
        Long last = slice.lastPeriodId();
        Long previous = previousPeriodId(slice, last);
        BigDecimal netWorth = netWorth(slice, last);
        BigDecimal previousNetWorth = previous == null ? null : netWorth(slice, previous);
        // v1.10 · 谓词收进 balanceAt(),逐期口径只有一份实现
        PeriodBalance bal = balanceAt(slice, last);
        BigDecimal totalAssets = bal.totalAssets();
        BigDecimal totalLiabilities = bal.totalLiabilities();
        // 流动资产 = liquidity == LIQUID 的账户期末合计。
        // v1.6.30 修注释 · 原注释写「仅 CASH;WEALTH 是 SEMI_LIQUID 不计入」,与实际行为不符 ——
        //   v0.3.3 起 FactProjector.liquidityOf 是 product_category.liquidity_class 优先、
        //   缺省才按 AccountType 兜底,所以货币基金(WEALTH + MONEY_FUND)**计入** LIQUID。
        //   prod 实测:流动资产 546,432.63 里 CASH 只占 164,924.63,其余来自被标 LIQUID 的理财账户。
        //   实际行为是对的(T+0 可赎回本就该算流动),错的是这条注释和 PRD 的两处旧措辞。
        BigDecimal liquidAssets = bal.liquidAssets();
        BigDecimal avgExpense = averageExpense(slice, 12);
        BigDecimal emergencyMonths = avgExpense.signum() == 0
                ? null
                : liquidAssets.divide(avgExpense, 1, RoundingMode.HALF_EVEN);
        BigDecimal debtRatio = totalAssets.signum() == 0
                ? null
                : totalLiabilities.divide(totalAssets, 6, RoundingMode.HALF_EVEN);
        BigDecimal delta = previousNetWorth == null ? null : netWorth.subtract(previousNetWorth).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deltaPct = previousNetWorth == null || previousNetWorth.signum() == 0
                ? null
                : delta.divide(previousNetWorth, 6, RoundingMode.HALF_EVEN);

        // v0.4.2 · "资产年化"二分(剔除外部现金流的纯投资视角)
        // v1.6.30 · 收益类锚点改成「最新已关账期」。原先锚 lastPeriodId,而进行中的期
        //   余额已填、收支未录 → (期末 − 期初 − 净流入) 里净流入=0,未录的收支被整个算成投资收益。
        //   prod 2026-08 实测:21 条余额全填 / 0 条收支 → 9.1 万变化 100% 记为投资收益。
        //   存量类(netWorth/totalAssets/totalLiabilities/liquidAssets/环比)仍锚最后一期:
        //   填报过程中就该看到最新余额,且缺快照会结转上期,不会凭空缺口。
        List<Long> returnIds = slice.returnPeriodIds();
        Long returnAnchor = slice.returnAnchorPeriodId();
        Long returnPrev = returnIds.size() >= 2 ? returnIds.get(returnIds.size() - 2) : null;
        BigDecimal returnAnchorNetWorth = returnAnchor == null ? netWorth : netWorth(slice, returnAnchor);
        BigDecimal returnPrevNetWorth = returnPrev == null ? null : netWorth(slice, returnPrev);
        // v0.5.3 · lastNetInflow 提到 if 外:无论上期是否存在都算出来,供 tooltip 展示真实净流入
        // v1.6.30 · 改锚收益期(唯一消费方是「本月资产收益」的 tooltip)
        BigDecimal lastNetInflow = returnAnchor == null ? zero() : netInflowForPeriod(slice, returnAnchor);
        // v0.13 · 本期开账基线(新纳入账户存量本金)· 卡片第三项
        // v1.6.30 · **保持锚最后一期不动**:dashboard/review 的「本期怎么变」卡靠
        //   ΔNW = 人赚 + 钱赚 + 开账基线 这个恒等式成立,而 ΔNW(netWorthDelta)与人赚(cashflowBreakdown)
        //   都取 lastPeriodId。把这项挪到收益锚点会让三者不同期、恒等式当场破掉。
        //   收益计算另用 returnAnchorOb,两者分开。
        BigDecimal openingBaselineLast = openingBaseline(slice, last);
        BigDecimal returnAnchorOb = returnAnchor == null ? zero() : openingBaseline(slice, returnAnchor);
        BigDecimal monthlyPnlAmount = null;
        BigDecimal monthlyInvestReturnPct = null;
        if (returnPrevNetWorth != null && returnPrevNetWorth.signum() > 0) {
            // 期末剔除开账基线(视作已在期初的资本)→ 本月资产收益不因补录存量账户虚高
            var monthly = com.family.finance.calc.InvestmentReturnCalculator.monthly(
                returnPrevNetWorth, returnAnchorNetWorth.subtract(returnAnchorOb), lastNetInflow);
            monthlyPnlAmount = monthly.pnlAmount();
            monthlyInvestReturnPct = monthly.pnlPct();
        }
        // 12 月年化 = 已有 familyTwr(slice 默认 1Y · 即 12 月窗口)· 直接 alias 共享算法
        BigDecimal annualizedInvestReturnPct = familyTwr(slice);
        // 本年(自然年)累计纯投资 PnL
        BigDecimal ytdInvestPnl = ytdInvestPnl(slice);

        // v1.10 FR-327 · 「实时本月」口径 —— 锚**当前期**(可能进行中),给仪表盘用。
        //   与上面那组锚「最新已关账期」的字段并列存在,互不影响(报表页封板仍用上面那组)。
        //   算法是同一个 InvestmentReturnCalculator.monthly(),只把锚从 returnAnchor 换成 last。
        //   注意:进行中期的收支典型是没录齐的,那时未录的收入会被公式归到投资损益名下 →
        //   数值会**虚高**。所以同时给出 liveIncome/liveExpense,让页面能把可信度说清楚
        //   (维护者拍板:显示真实值 + 说明口径,而不是藏起来 · tech-design v1.10 §6.2)。
        BigDecimal livePnlAmount = null;
        BigDecimal livePnlPct = null;
        BigDecimal livePrevNetWorth = previous == null ? null : netWorth(slice, previous);
        if (livePrevNetWorth != null && livePrevNetWorth.signum() > 0) {
            var live = com.family.finance.calc.InvestmentReturnCalculator.monthly(
                    livePrevNetWorth,
                    netWorth.subtract(openingBaselineLast),
                    pmcFirstNetInflow(slice, last));
            livePnlAmount = live.pnlAmount();
            livePnlPct = live.pnlPct();
        }
        CashflowBreakdown liveCf = cashflowBreakdown(slice, last);

        return new KpiSnapshot(netWorth, totalAssets, totalLiabilities, emergencyMonths, debtRatio, delta, deltaPct,
            monthlyPnlAmount, monthlyInvestReturnPct, annualizedInvestReturnPct, ytdInvestPnl,
            // v0.5.3 · 透明化中间量(viewCurrency 口径 · 与上面 KPI 同币种)
            // v1.6.30 · prevNetWorth 改成「收益锚点的上一期」(本月资产收益 tooltip 的期初),
            //   与 monthlyPnl* 同锚;净资产环比 delta/deltaPct 仍用存量口径的 previousNetWorth。
            liquidAssets, avgExpense, returnPrevNetWorth, lastNetInflow, openingBaselineLast,
            returnAnchorNetWorth, slice.periodStartOf(returnAnchor), slice.filingInProgress(), returnIds.size(),
            livePnlAmount, livePnlPct, liveCf.income(), liveCf.expense());
    }

    /**
     * v0.10 · 某期毛收入/毛支出/净流入(人赚)· viewCurrency。
     *
     * <p>与 {@link #pmcFirstNetInflow} <b>同源同分支</b>:PMC(成员两框收支 · 本位币存)有人填则
     * 各分量 ×{@code baseToViewFactor};否则回退 account cash_flow(incomeBase/expenseBase 已 view)。
     * 故 {@code income − expense == 净流入}、且与 KPI 的人赚(lastNetInflow)同口径。</p>
     */
    @Override
    public CashflowBreakdown cashflowBreakdown(FactSlice slice, Long periodId) {
        if (periodId == null) {
            return new CashflowBreakdown(zero(), zero(), zero());
        }
        // v0.12 FR-142 · 收入/支出各自决定来源(与 pmcFirstNetInflow 同源同分支):
        // 收入 = PMC 手填(历史)优先否则 cash_flow 汇总(新账期收入侧录入);支出 = PMC 优先否则 cash_flow。
        BigDecimal income = netInflowIncome(slice, periodId);
        BigDecimal expense = netInflowExpense(slice, periodId);
        BigDecimal net = income.subtract(expense).setScale(2, RoundingMode.HALF_EVEN);
        return new CashflowBreakdown(income, expense, net);
    }

    /**
     * v0.10 · 近 n 期收支序列(view 币种 · 含进行中 OPEN 期)。
     * livePeriodId 命中的点标 live(进行中);各点收支口径 == cashflowBreakdown(与卡片人赚同源)。
     */
    @Override
    public List<CashflowPoint> cashflowSeries(FactSlice slice, int n, Long livePeriodId) {
        List<Long> ids = slice.periodIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, ids.size() - n);
        List<CashflowPoint> out = new ArrayList<>();
        for (Long pid : ids.subList(from, ids.size())) {
            CashflowBreakdown b = cashflowBreakdown(slice, pid);
            out.add(new CashflowPoint(pid, label(slice, pid), b.income(), b.expense(), b.netInflow(),
                    Objects.equals(pid, livePeriodId)));
        }
        return out;
    }

    /**
     * v0.4.2 助手 · v0.5 FR-84 改:某 period 家庭净流入(人赚的)· 委托 PMC 优先口径。
     * (原只读 account cash_flow → 用户工资填 PMC 时净流入恒为 0 的 bug · 详 prd/v0.5.md FR-84)
     */
    private BigDecimal netInflowForPeriod(FactSlice slice, Long periodId) {
        return pmcFirstNetInflow(slice, periodId);
    }

    /**
     * v0.5 FR-84 · 某期家庭净流入(人赚的)· <b>PMC 优先 · 该期 PMC 空回退 account cash_flow</b>。
     *
     * <p>承 v0.4.3 B2 同纪律(月均支出 PMC 优先):用户工资填在 period_member_cashflow
     * (/entry 成员月度收支两框),不逐笔记 account cash_flow。只读 cash_flow 会让净流入恒为 0。</p>
     *
     * <p>家庭层 transfer 自然抵消(每笔一 in 一 out 同额),故 cash_flow 回退用 income − expense
     * 即可;PMC 本就是家庭级,无 transfer 概念。</p>
     */
    private BigDecimal pmcFirstNetInflow(FactSlice slice, Long periodId) {
        return netInflowIncome(slice, periodId).subtract(netInflowExpense(slice, periodId))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * v0.12 FR-142 · 收入侧口径:PMC 手填收入(历史账期两框)优先,否则取 cash_flow INCOME 汇总
     * (新账期由「收支填报·收入侧」逐笔录入 → 本就是 account cash_flow)。按期各取其一、不叠加(防双计):
     * 历史账期 PMC 收入>0 → 用 PMC(向后兼容,历史人赚/储蓄率不变);新账期不再填 PMC 收入(null)→ 用 cash_flow。
     * PMC 按本位币存 → ×baseToViewFactor 换到 view;cash_flow 的 incomeBase 已是 view 口径。
     */
    private BigDecimal netInflowIncome(FactSlice slice, Long periodId) {
        // v1.12 FR-352 · 改走 pmcAggregate:缓存里没有就一次批量取回整个切片的账期(取数口径不变)
        var pmc = pmcAggregate(slice, periodId);
        if (pmc != null && pmc.totalIncome() != null && pmc.totalIncome().signum() > 0) {
            return pmc.totalIncome().multiply(baseToViewFactor(slice)).setScale(2, RoundingMode.HALF_EVEN);
        }
        return periodIncome(slice, periodId);
    }

    /** v0.12 · 支出侧口径不变:PMC 手填支出优先,否则回退 cash_flow。 */
    /**
     * v1.8 · 改走统一口径 {@code ExpenseLedgerService}(逐笔 > 总额,不相加,排除现金调整)。
     *
     * <p>⚠ <b>注意与收入侧优先级相反</b>:收入是「PMC 手填 &gt; 0 则用之,否则 cash_flow 汇总」,
     * 支出是「逐笔 &gt; 0 则用之,否则 PMC」。方向反的原因是历史数据分布不同 ——
     * 收入的历史事实在 PMC 里,支出的更细事实在逐笔里。别「顺手统一」。</p>
     *
     * <p>口径服务返回本位币,这里乘 baseToViewFactor 换到视图币种(与原 PMC 分支同一手法)。
     * 都取不到时回落 {@code periodExpense}(cash_flow 汇总 · 含调整),保持原有兜底行为。</p>
     */
    private BigDecimal netInflowExpense(FactSlice slice, Long periodId) {
        // v1.12 FR-352 · 改走 ledgerExpense:同上,byPeriod → 一次 byPeriods(同一口径服务、同一方法族)
        var pe = ledgerExpense(slice, periodId);
        if (pe.filled()) {
            return pe.amountBase().multiply(baseToViewFactor(slice)).setScale(2, RoundingMode.HALF_EVEN);
        }
        return periodExpense(slice, periodId);
    }

    /**
     * v0.5 修 · 家庭本位币 → 当前 viewCurrency 的汇率因子。
     *
     * <p>PMC(period_member_cashflow.total_*_input)按家庭本位币存;而 fact_view 的
     * endBalanceBase 实为 viewCurrency 口径(FactMapper 把账户币种换到 view)。
     * 比值类指标(紧急储备 = 流动资产/月支出、资产收益% 含净流入)若一侧 view 一侧 base,
     * 切币种就会错算。这里把 PMC 也换到 view。</p>
     *
     * <p>从 slice 自取(无需额外依赖):本位币账户的 fxToBase 即 base→view;
     * view==base 则 1;找不到本位币账户则保守取 1(此时 base 视图本就正确)。</p>
     */
    private BigDecimal baseToViewFactor(FactSlice slice) {
        String view = slice.filter().viewCurrency();
        // v1.12 FR-352 · 家庭行走缓存(这个方法每期每指标都被调,原来一次报表页查了 194 遍)
        String base = familyOf(slice.filter().familyId())
                .map(f -> f.getBaseCurrency()).orElse(view);
        if (view == null || base == null || view.equalsIgnoreCase(base)) return BigDecimal.ONE;
        // BUG-FIX v0.8(v05-CCY-INV-1):原 findFirst 取任意期的 base 币行 fxToBase,窗口早期常缺当期汇率 →
        // 该行 fxToBase 落 1.0(未换算),而分子(流动资产)取末期(已 ensure 汇率)→ 比值随币种漂移。
        // 改:优先取 anchor(末)期的 base 币行,且跳过 fxToBase==1.0 的未换算脏行,与分子同期同口径。
        Long last = slice.lastPeriodId();
        return slice.rows().stream()
                .filter(r -> base.equalsIgnoreCase(r.accountCurrency())
                        && (last == null || java.util.Objects.equals(r.periodId(), last))
                        && validFx(r.fxToBase()))
                .map(AccountPeriodFact::fxToBase)
                .findFirst()
                .orElseGet(() -> slice.rows().stream()   // 兜底:任意期的有效(已换算)base 币行
                        .filter(r -> base.equalsIgnoreCase(r.accountCurrency()) && validFx(r.fxToBase()))
                        .map(AccountPeriodFact::fxToBase)
                        .findFirst()
                        .orElse(BigDecimal.ONE));
    }

    /** fxToBase 是否「真换算过」:非空、>0 且 ≠1.0(==1.0 多为当期缺汇率落 ELSE 兜底的脏值)。 */
    private static boolean validFx(BigDecimal fx) {
        return fx != null && fx.signum() > 0 && fx.compareTo(BigDecimal.ONE) != 0;
    }

    /**
     * v0.4.2 助手:本年(自然年)累计纯投资 PnL。
     *
     * <p>v0.4.3 B4 修复:**独立加载** Jan1-now 数据 · 不再依赖 caller slice 的 range
     * (避免 range=3M 时 YTD 只见 3 月的 bug)· 多加载 1 期获取期初 NetWorth。</p>
     */
    private BigDecimal ytdInvestPnl(FactSlice slice) {
        long familyId = slice.filter().familyId();
        java.time.LocalDate now = java.time.LocalDate.now();
        // 多回退 1 个月 · 拿到去年 12 月的 snapshot 作期初
        java.time.LocalDate ytdStart = java.time.LocalDate.of(now.getYear(), 1, 1).minusMonths(1);
        FactSlice ytdSlice;
        try {
            ytdSlice = load(new FactFilter(
                familyId,
                slice.filter().periodType(),
                ytdStart,
                now.withDayOfMonth(1),
                false,
                null,
                slice.filter().viewCurrency()
            ));
        } catch (Exception e) {
            return null;
        }
        int currentYear = now.getYear();
        List<com.family.finance.calc.TwrCalculator.TwrPoint> ytdPoints = new ArrayList<>();
        // v1.6.30 · 只累计已关账期:进行中的期收支未录,计入会把未录收支当成投资损益。
        for (Long periodId : ytdSlice.returnPeriodIds()) {
            java.time.LocalDate pStart = periodStart(ytdSlice, periodId);
            if (pStart == null || pStart.getYear() != currentYear) continue;
            Long prev = previousPeriodId(ytdSlice, periodId);
            BigDecimal start = prev == null ? null : netWorth(ytdSlice, prev);
            BigDecimal end = netWorth(ytdSlice, periodId);
            // v0.13 · YTD 每月投资 PnL 也把开账基线并入外部流入剔除
            BigDecimal inflow = netInflowForPeriod(ytdSlice, periodId).add(openingBaseline(ytdSlice, periodId));
            if (start != null && end != null && start.signum() > 0) {
                ytdPoints.add(new com.family.finance.calc.TwrCalculator.TwrPoint(start, end, inflow));
            }
        }
        return com.family.finance.calc.InvestmentReturnCalculator.ytdPnlAmount(ytdPoints);
    }

    @Override
    public List<TrendPoint> netWorthTrend(FactSlice slice) {
        // v1.18.7 · 标出「还在进行中」的那一期 —— 收支趋势早就这么做了,净资产趋势一直没有,
        //   于是最右那个还会变的点和已定格的点长得一样。
        java.util.Set<Long> closed = slice.closedPeriodIds() == null
                ? java.util.Set.of() : new java.util.HashSet<>(slice.closedPeriodIds());
        return slice.periodIds().stream()
                .map(periodId -> new TrendPoint(periodId, periodStart(slice, periodId), label(slice, periodId),
                        netWorth(slice, periodId), !closed.isEmpty() && !closed.contains(periodId)))
                .toList();
    }

    /**
     * v0.13 · 剔除累计开账基线的净资产趋势 · 给财富水位用(否则"补录存量账户"会假装跑赢通胀)。
     * 每期值 = netWorth(P) − Σ_{P'≤P} openingBaseline(P')(把沿途新纳入的存量本金从轨迹里扣掉)。
     */
    @Override
    public List<TrendPoint> netWorthTrendExOpening(FactSlice slice) {
        List<TrendPoint> out = new ArrayList<>();
        BigDecimal cumOpening = BigDecimal.ZERO;
        boolean first = true;
        for (Long periodId : slice.periodIds()) {
            // v1.9.4 · **窗口首期的开账基线不减**。
            //
            // 原来是每期都减,包括首期 —— 而首期的「首次出现账户」按定义就是全部账户,
            // 于是首点恒等于 0(ReportsController v1.6.29 的注释里已经写下过这个事实,
            // 但当时只把 tooltip 那个消费方改成 netWorthTrend,财富水位这个**主**消费方留在了坏序列上)。
            //
            // 后果:WaterLevelService 以首点为锚(anchor),anchor<=0 直接判 unavailable ——
            // 只要时间窗包含家庭首期,财富水位就永久显示「需要至少 2 期净资产数据」。
            // 新用户只有两三期、任何窗口都含首期 → 这一节对他们从来没出现过(prod 实报)。
            //
            // 语义上首期那笔存量本金**就是起跑线**,不是「注入」:购买力线要拿它当基数复利。
            // 第二期起新出现的账户仍然剔除(那才是「本来就有、现在才开始记」的外部资本纳入),
            // 所以对不含首期的窗口(3M/6M/YTD/1Y)本方法输出**逐点不变** —— 零差异,已实测。
            if (!first) {
                cumOpening = cumOpening.add(openingBaseline(slice, periodId));
            }
            first = false;
            BigDecimal v = netWorth(slice, periodId).subtract(cumOpening).setScale(2, RoundingMode.HALF_EVEN);
            out.add(new TrendPoint(periodId, periodStart(slice, periodId), label(slice, periodId), v));
        }
        return out;
    }

    @Override
    public List<AllocationSlice> allocationByType(FactSlice slice, Long periodId) {
        if (periodId == null) {
            return List.of();
        }
        Map<AccountType, BigDecimal> byType = slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .filter(row -> row.accountClass() == AccountClass.ASSET)
                .filter(row -> row.endBalanceBase() != null)
                .collect(Collectors.groupingBy(AccountPeriodFact::accountType, LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, AccountPeriodFact::endBalanceBase, BigDecimal::add)));
        BigDecimal total = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return byType.entrySet().stream()
                .map(entry -> new AllocationSlice(
                        entry.getKey().name(),
                        entry.getKey().getLabel() + "\n(" + entry.getKey().name() + ")",
                        entry.getValue().setScale(2, RoundingMode.HALF_EVEN),
                        total.signum() == 0 ? BigDecimal.ZERO : entry.getValue().divide(total, 6, RoundingMode.HALF_EVEN)))
                .toList();
    }

    @Override
    public List<WaterfallSegment> incomeExpenseWaterfall(FactSlice slice) {
        List<WaterfallSegment> result = new ArrayList<>();
        for (Long periodId : slice.periodIds()) {
            Long previous = previousPeriodId(slice, periodId);
            result.add(new WaterfallSegment(
                    periodId,
                    periodStart(slice, periodId),
                    label(slice, periodId),
                    previous == null ? BigDecimal.ZERO.setScale(2) : netWorth(slice, previous),
                    periodIncome(slice, periodId),
                    periodExpense(slice, periodId),
                    periodPnl(slice, periodId),
                    netWorth(slice, periodId)
            ));
        }
        return result;
    }

    @Override
    /**
     * 储蓄率 = (收入 − 支出) ÷ 收入 · 锚最新已关账期。
     *
     * <p>v1.6.30 修 · 起因:本方法是 {@code GoalMetricEvaluator} 算 SAVINGS_RATE 类目标当前值的唯一来源
     * ({@code nz(pct(factView.savingsRate(slice)))}),而原实现有两个毛病叠在一起:</p>
     * <ol>
     *   <li>只读 {@code cash_flow}(periodIncome/periodExpense),不走 PMC 优先 —— 与 reports 页
     *       「当期储蓄率」({@code HouseholdCashflowService.currentSavingsRate},PMC 优先 + cash_flow 回落)
     *       是两套口径,同一个概念两页两个数;</li>
     *   <li>锚 {@code lastPeriodId} = 可能是进行中的期。prod 2026-08 没有 cash_flow 收入 →
     *       返回 null → {@code nz()} 兜成 0 → <b>任何"储蓄率达到 X%"的家庭目标进度恒显示 0%</b>。</li>
     * </ol>
     *
     * <p>改成与 {@code pmcFirstNetInflow} / 人赚 / XIRR 完全同源(PMC 优先否则 cash_flow),
     * 并锚到最新已关账期。这样全站"收入/支出"只有一套口径。</p>
     */
    public BigDecimal savingsRate(FactSlice slice) {
        Long anchor = slice.returnAnchorPeriodId();
        if (anchor == null) {
            return null;
        }
        BigDecimal income = netInflowIncome(slice, anchor);
        if (income.signum() == 0) {
            return null;
        }
        return income.subtract(netInflowExpense(slice, anchor))
                .divide(income, 6, RoundingMode.HALF_EVEN);
    }

    @Override
    public Map<Long, BigDecimal> accountXirr(FactSlice slice) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<AccountPeriodFact>> entry : slice.byAccount().entrySet()) {
            List<AccountPeriodFact> rows = entry.getValue().stream()
                    .filter(row -> row.endBalanceOrig() != null)
                    .sorted(Comparator.comparing(AccountPeriodFact::periodStart))
                    .toList();
            result.put(entry.getKey(), xirrForAccountRows(rows));
        }
        return result;
    }

    @Override
    public BigDecimal familyXirr(FactSlice slice) {
        // v1.6.30 · 只用已关账期:进行中的期余额已填而收支未填,拿它当终值会把未录收支算成投资收益。
        List<Long> ids = slice.returnPeriodIds();
        if (ids.size() < 2) {
            return null;
        }
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        Long first = ids.getFirst();
        Long last = ids.getLast();
        flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, first), netWorth(slice, first).negate()));
        for (int i = 1; i < ids.size(); i++) {
            Long periodId = ids.get(i);
            // v0.13 · 开账基线并入外部资本流入(补录存量账户不抬高年化)
            // v1.6.29 修 · 外部净流入必须与同页「人赚 / 累计净投入」同源(pmcFirstNetInflow:PMC 优先否则 cash_flow)。
            //   原实现只读 cash_flow → prod 上 6 月页面「人赚」按 PMC 15.15 万算而 XIRR 只扣了 8.15 万、
            //   7 月用户填在 PMC 的 21,837 支出 XIRR 完全没扣 → 同一屏两个 KPI 互相矛盾。
            //   这正是 AGENTS.md 联动不变量 L1 要防的:改收支来源口径必须同步 familyXirr。
            BigDecimal external = pmcFirstNetInflow(slice, periodId)
                    .add(openingBaseline(slice, periodId));
            if (external.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, periodId), external.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, last), netWorth(slice, last)));
        return XirrCalculator.annualizedOrCumulative(flows, ids.size());
    }

    @Override
    public BigDecimal familyTwr(FactSlice slice) {
        // v1.6.30 · 同 familyXirr:只用已关账期,进行中的期不参与分段收益率连乘。
        List<Long> ids = slice.returnPeriodIds();
        if (ids.size() < 2) {
            return null;
        }
        List<TwrCalculator.TwrPoint> points = new ArrayList<>();
        for (int i = 1; i < ids.size(); i++) {
            Long previous = ids.get(i - 1);
            Long current = ids.get(i);
            points.add(new TwrCalculator.TwrPoint(
                    netWorth(slice, previous),
                    netWorth(slice, current),
                    // v0.13 · 开账基线并入当期外部流入 → TWR 不因补录存量账户跳升
                    // v1.6.29 修 · 同 familyXirr:外部净流入统一走 pmcFirstNetInflow(与「人赚」同源)
                    pmcFirstNetInflow(slice, current)
                            .add(openingBaseline(slice, current))
            ));
        }
        return TwrCalculator.annualizedOrCumulative(points, points.size());
    }

    @Override
    public List<PeriodFlow> periodFlows(FactSlice slice) {
        List<PeriodFlow> out = new ArrayList<>();
        // v1.6.30 · 只走已关账期(与 familyXirr / familyTwr / 本月资产收益 同锚)。
        //   进行中的期收支未录 → netInflow=0 → 该期的 ΔNW 会整块落进「钱赚」,把未录工资算成投资收益。
        List<Long> ids = slice.returnPeriodIds();
        for (int i = 1; i < ids.size(); i++) {
            Long periodId = ids.get(i);
            Long prevId = ids.get(i - 1);
            // v0.5 FR-84 · 人赚 = PMC 优先净流入;钱赚 = ΔNW − 人赚(由构造保证 人赚 + 钱赚 = ΔNetWorth)。
            // 原实现:人赚只读 account cash_flow(用户填 PMC 时恒为 0)· 钱赚读 periodPnlBase(把工资增长误算成投资)。
            BigDecimal netInflow = pmcFirstNetInflow(slice, periodId);
            // v0.13 · 开账基线归入"本金(external)"、剔出"投资损益(pnl)"
            BigDecimal ob = openingBaseline(slice, periodId);
            BigDecimal nw = netWorth(slice, periodId);
            BigDecimal prevNw = netWorth(slice, prevId);
            BigDecimal nwDelta = nw.subtract(prevNw);
            BigDecimal pnl = nwDelta.subtract(netInflow).subtract(ob);
            out.add(new PeriodFlow(periodId, periodStart(slice, periodId), label(slice, periodId),
                    netInflow, ob, nwDelta, pnl, nw, prevNw));
        }
        return out;
    }

    /**
     * v1.10 · 改为消费 {@link #periodFlows(FactSlice)} —— 逐期分解只保留一份实现。
     * 累计逻辑与口径**逐字未变**(同一个循环、同一组公式、同样的 setScale 位置)。
     */
    @Override
    public List<DecompositionPoint> principalVsReturnDecomposition(FactSlice slice) {
        List<DecompositionPoint> result = new ArrayList<>();
        BigDecimal cumulativeExternal = BigDecimal.ZERO;
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        for (PeriodFlow f : periodFlows(slice)) {
            cumulativeExternal = cumulativeExternal.add(f.netInflow()).add(f.openingBaseline());
            cumulativePnl = cumulativePnl.add(f.pnl());
            result.add(new DecompositionPoint(
                    f.periodId(),
                    f.periodStart(),
                    f.label(),
                    cumulativeExternal.setScale(2, RoundingMode.HALF_EVEN),
                    cumulativePnl.setScale(2, RoundingMode.HALF_EVEN)
            ));
        }
        return result;
    }

    @Override
    public List<TrendPoint> debtTrend(FactSlice slice) {
        return slice.periodIds().stream()
                .map(periodId -> new TrendPoint(periodId, periodStart(slice, periodId), label(slice, periodId),
                        slice.rows().stream()
                                .filter(row -> Objects.equals(row.periodId(), periodId))
                                .filter(row -> row.accountClass() == AccountClass.LIABILITY)
                                .map(AccountPeriodFact::endBalanceBase)
                                .filter(Objects::nonNull)
                                .map(BigDecimal::abs)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, RoundingMode.HALF_EVEN)))
                .toList();
    }

    @Override
    public List<AccountPerformance> accountPerformance(FactSlice slice) {
        return accountPerformance(slice, false);
    }

    @Override
    public List<AccountPerformance> accountPerformance(FactSlice slice, boolean sealedAttrs) {
        Map<Long, BigDecimal> xirr = accountXirr(slice);
        Long lastPid = slice.lastPeriodId();
        BigDecimal familyNetWorth = lastPid == null ? null : netWorth(slice, lastPid);
        Map<Long, BigDecimal> expected = expectedReturnByAccount(slice, sealedAttrs);
        // v0.13 · 窗口内"首次出现"的账户集合 → 其首期期末余额是"带入本金",计入 net_principal
        java.util.Set<Long> newInWindow = new java.util.HashSet<>();
        for (Long pid : slice.periodIds()) {
            newInWindow.addAll(firstAppearingIn(slice.filter().familyId(), pid));   // v1.11 · 走请求级缓存
        }
        // v1.10 · **列表只列锚期仍在册的账户**。
        //   归档过滤加了时间语义之后(archived_at > period_end),归档账户的历史事实回到了切片里 ——
        //   这对"历史期的净资产/集中度不再被归档动作改写"是必须的,但**账户列表**不该因此
        //   把已归档账户重新列出来(用户归档就是为了让它从当前列表消失)。
        //   queryBase 是 account × period 全交叉,所以"在锚期有行" 恰好等价于 "锚期时未归档"。
        java.util.Set<Long> activeAtAnchor = lastPid == null
                ? null
                : slice.rows().stream()
                        .filter(row -> java.util.Objects.equals(row.periodId(), lastPid))
                        .map(AccountPeriodFact::accountId)
                        .collect(Collectors.toSet());
        return slice.byAccount().values().stream()
                .map(rows -> rows.stream().sorted(Comparator.comparing(AccountPeriodFact::periodStart)).toList())
                .filter(rows -> activeAtAnchor == null || activeAtAnchor.contains(rows.getFirst().accountId()))
                .map(rows -> buildAccountPerformance(rows, xirr, familyNetWorth, expected, newInWindow))
                .sorted(Comparator.comparing(AccountPerformance::accountId))
                .toList();
    }

    @Override
    public MomYoy momYoy(FactFilter filter) {
        return momYoy(load(filter));
    }

    @Override
    public MomYoy momYoy(FactSlice slice) {
        Long last = slice.lastPeriodId();
        if (last == null) return new MomYoy(null, null, null, null, null);
        BigDecimal nwNow = netWorth(slice, last);
        Map<Long, LocalDate> startById = new LinkedHashMap<>();
        for (AccountPeriodFact r : slice.rows()) startById.putIfAbsent(r.periodId(), r.periodStart());
        LocalDate asOfStart = startById.get(last);

        BigDecimal momAmount = null, momPct = null;
        Long prev = previousPeriodId(slice, last);
        if (prev != null) {
            BigDecimal p = netWorth(slice, prev);
            momAmount = nwNow.subtract(p).setScale(2, RoundingMode.HALF_EVEN);
            if (p.signum() != 0) {
                momPct = momAmount.divide(p.abs(), 4, RoundingMode.HALF_EVEN).multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
            }
        }

        BigDecimal yoyAmount = null, yoyPct = null;
        if (asOfStart != null) {
            LocalDate yoyStart = asOfStart.minusMonths(12);
            Long yoyId = startById.entrySet().stream()
                    .filter(e -> e.getValue().equals(yoyStart)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (yoyId != null) {
                BigDecimal y = netWorth(slice, yoyId);
                yoyAmount = nwNow.subtract(y).setScale(2, RoundingMode.HALF_EVEN);
                if (y.signum() != 0) {
                    yoyPct = yoyAmount.divide(y.abs(), 4, RoundingMode.HALF_EVEN).multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
                }
            }
        }
        return new MomYoy(nwNow, momAmount, momPct, yoyAmount, yoyPct);
    }

    /**
     * 每账户的预期年化 %:账户 expected_return_pct 覆盖优先,否则回落品类 benchmark_pct;都没有=null。
     *
     * <p><b>v1.12 FR-350</b> · {@code sealedAttrs=true}(报表页)时这两个输入都取<b>锚期定格值</b>。
     * 这是本版第三条漂移入口 —— 设计时只找到 {@code FactMapper.queryBase}(封板二区)和
     * {@code ReportsController} 的基准 map(三区 vs-基准列)两条,是正向漂移测试把它逼出来的:
     * 「预实」列压根不吃那张基准 map,它吃 {@code AccountPerformance.planActualDiffPct},
     * 而后者由本方法算出来。只修前两条 → 基准列不动、紧挨着的预实列照旧漂。
     *
     * <p>定格行存在就<b>整行采信</b>(哪怕两个字段都是 null → 预实显示「—」),不再回落当前值:
     * 「关账那一刻这个账户没设预期」本身就是要保护的历史。只有<b>没有定格行</b>才回落当前值,
     * 三种情形与 {@code FactMapper.queryBase} 的注释一致(未关账当期 / 今天新建的账户 / 回填之前)。
     */
    private Map<Long, BigDecimal> expectedReturnByAccount(FactSlice slice, boolean sealedAttrs) {
        Map<Long, com.family.finance.domain.period.PeriodAccountAttr> frozen = new java.util.HashMap<>();
        if (sealedAttrs) {
            Long anchor = slice.returnAnchorPeriodId();
            if (anchor != null) {
                for (var attr : periodAccountAttrMapper.findByPeriod(anchor)) frozen.put(attr.accountId(), attr);
            }
        }
        // v1.12 FR-352 · 原来每个账户一次 findById + 一次 findByCode(24 账户 = 48 条 SQL,
        // 而且报表页每次请求都跑一遍),改成家庭级一次 + 类目全量一次。
        // 行集完全一致,所以零差异比对仍应通过:切片里的账户都属于本家庭;findAllByFamily 与
        // findById 一样**不过滤归档**(归档账户的历史事实按 v1.10 仍在切片里);
        // ProductCategoryMapper.findAll 与 findByCode 是同一张表同样的列、无额外 WHERE。
        Map<Long, Account> accountById = new java.util.HashMap<>();
        for (Account a : accountMapper.findAllByFamily(slice.filter().familyId())) accountById.put(a.getId(), a);
        Map<String, BigDecimal> benchByCode = new java.util.HashMap<>();
        for (var pc : productCategoryService.listAll()) benchByCode.put(pc.getCode(), pc.getBenchmarkPct());

        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Long accountId : slice.byAccount().keySet()) {
            var attr = frozen.get(accountId);
            if (attr != null) {
                result.put(accountId, attr.expectedReturnPct() != null
                        ? attr.expectedReturnPct() : attr.benchmarkPct());
                continue;
            }
            Account acc = accountById.get(accountId);
            BigDecimal expected = null;
            if (acc != null) {
                if (acc.getExpectedReturnPct() != null) expected = acc.getExpectedReturnPct();
                else if (acc.getProductCategoryCode() != null) expected = benchByCode.get(acc.getProductCategoryCode());
            }
            result.put(accountId, expected);
        }
        return result;
    }

    /** 从某账户的(已按期排序的)fact 行,一趟算出 v0.8 账户级指标全集(本位币 / 派生,实时算)。 */
    private AccountPerformance buildAccountPerformance(List<AccountPeriodFact> rows,
                                                       Map<Long, BigDecimal> xirr,
                                                       BigDecimal familyNetWorth,
                                                       Map<Long, BigDecimal> expectedByAccount,
                                                       java.util.Set<Long> newInWindow) {
        AccountPeriodFact first = rows.getFirst();
        List<AccountPeriodFact> filled = rows.stream()
                .filter(row -> row.endBalanceBase() != null)
                .toList();
        AccountPeriodFact latest = filled.isEmpty() ? first : filled.get(filled.size() - 1);
        BigDecimal currentValue = latest.endBalanceBase();

        List<TrendPoint> spark = filled.stream()
                .map(r -> new TrendPoint(r.periodId(), r.periodStart(), label(r.periodStart()), r.endBalanceBase()))
                .toList();

        // 累计投资损益 = Σ periodPnlBase(首期通常 null,跳过)
        BigDecimal cumPnl = rows.stream()
                .map(AccountPeriodFact::periodPnlBase).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_EVEN);
        // 累计净投入 = Σ(income − expense + transferIn − transferOut)本位币
        BigDecimal netPrincipal = rows.stream()
                .map(r -> nz(r.incomeBase()).subtract(nz(r.expenseBase()))
                        .add(nz(r.transferInBase())).subtract(nz(r.transferOutBase())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_EVEN);
        // v0.13 · 窗口内首次出现的账户:首期期末余额 = 带入本金,计入净投入(否则"净投入≈0 却有大额市值"不自洽)
        if (newInWindow.contains(first.accountId()) && !filled.isEmpty()
                && filled.get(0).endBalanceBase() != null) {
            netPrincipal = netPrincipal.add(filled.get(0).endBalanceBase()).setScale(2, RoundingMode.HALF_EVEN);
        }
        BigDecimal latestPnl = latest.periodPnlBase();

        // 较上一账期(最后两个有余额的期)
        BigDecimal momAmount = null, momPct = null;
        if (filled.size() >= 2) {
            BigDecimal prev = filled.get(filled.size() - 2).endBalanceBase();
            if (currentValue != null && prev != null) {
                momAmount = currentValue.subtract(prev).setScale(2, RoundingMode.HALF_EVEN);
                if (prev.signum() != 0) {
                    momPct = momAmount.divide(prev.abs(), 4, RoundingMode.HALF_EVEN)
                            .multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
                }
            }
        }

        BigDecimal sharePct = null;
        if (currentValue != null && familyNetWorth != null && familyNetWorth.signum() != 0) {
            sharePct = currentValue.divide(familyNetWorth, 4, RoundingMode.HALF_EVEN)
                    .multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
        }

        // 最大回撤(原币 NAV 序列)
        BigDecimal maxDrawdownPct = null;
        List<AccountPeriodFact> origRows = rows.stream().filter(r -> r.endBalanceOrig() != null).toList();
        if (origRows.size() >= 2) {
            List<NavSeriesBuilder.PeriodPoint> navInputs = origRows.stream()
                    .map(r -> new NavSeriesBuilder.PeriodPoint(r.periodStart(), r.endBalanceOrig(),
                            r.incomeOrig(), r.expenseOrig(), r.transferInOrig(), r.transferOutOrig()))
                    .toList();
            List<MaxDrawdownCalculator.NavPoint> nav = NavSeriesBuilder.build(navInputs);
            if (nav.size() >= 2) {
                MaxDrawdownCalculator.Result dd = MaxDrawdownCalculator.calculate(nav);
                if (dd != null && dd.drawdown() != null) {
                    maxDrawdownPct = dd.drawdown().multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
                }
            }
        }

        // Problem C:本位币年化(含 FX),与原币 xirr 并列;本位币账户两者相等
        BigDecimal returnBase = xirrBaseForAccountRows(filled);
        // 预实(v0.11.4 修口径):实际 = 该账户显示的那个 xirr(<12 期累计 / ≥12 期年化,与列头一致)− 预期(同基)。
        //   修 v0.10.5「cumPnl/净投入 当实际」:净投入极小的账户会爆成 +19497pp,且与显示的收益率脱节。
        //   满 12 期减年化预期,不足 12 期把预期缩放到同窗口 → like-for-like;前端标「近 N 月」。
        BigDecimal expectedPct = expectedByAccount.get(first.accountId());
        BigDecimal planActualDiff = com.family.finance.calc.BenchmarkAggregator
                .displayedDiffPercentPoints(xirr.get(first.accountId()), expectedPct, filled.size());

        return new AccountPerformance(
                first.accountId(), first.accountName(), first.accountType(), first.accountCurrency(),
                currentValue, xirr.get(first.accountId()), spark,
                cumPnl, netPrincipal, latestPnl, momAmount, momPct, sharePct, maxDrawdownPct,
                filled.size(), sparkPoints(spark), sparkTrend(spark),
                returnBase, expectedPct, planActualDiff);
    }

    /** 账户级 XIRR · 本位币口径(含 FX);与 xirrForAccountRows(原币)对应。<2 期返回 null。 */
    private BigDecimal xirrBaseForAccountRows(List<AccountPeriodFact> rows) {
        if (rows.size() < 2) return null;
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        AccountPeriodFact first = rows.getFirst();
        AccountPeriodFact last = rows.getLast();
        if (first.endBalanceBase() == null || last.endBalanceBase() == null) return null;
        flows.add(new XirrCalculator.CashFlowPoint(first.periodEnd(), first.endBalanceBase().negate()));
        for (int i = 1; i < rows.size(); i++) {
            AccountPeriodFact row = rows.get(i);
            BigDecimal netExternal = nz(row.incomeBase()).subtract(nz(row.expenseBase()))
                    .add(nz(row.transferInBase())).subtract(nz(row.transferOutBase()));
            if (netExternal.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(row.periodEnd(), netExternal.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(last.periodEnd(), last.endBalanceBase()));
        return XirrCalculator.annualizedOrCumulative(flows, rows.size());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /* v1.20 · 归一化搬到 {@link Sparkline} —— 账户组的组行要在【合并后的时序】上重算同一件事,
     * 抄一份的下场是两条曲线的 viewBox / 基准会漂,而且没有任何测试会失败。 */
    private static String sparkPoints(List<TrendPoint> spark) { return Sparkline.points(spark); }

    private static String sparkTrend(List<TrendPoint> spark) { return Sparkline.trend(spark); }

    private BigDecimal xirrForAccountRows(List<AccountPeriodFact> rows) {
        if (rows.size() < 2) {
            return null;
        }
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        AccountPeriodFact first = rows.getFirst();
        AccountPeriodFact last = rows.getLast();
        flows.add(new XirrCalculator.CashFlowPoint(first.periodEnd(), first.endBalanceOrig().negate()));
        for (int i = 1; i < rows.size(); i++) {
            AccountPeriodFact row = rows.get(i);
            BigDecimal netExternal = row.incomeOrig()
                    .subtract(row.expenseOrig())
                    .add(row.transferInOrig())
                    .subtract(row.transferOutOrig());
            if (netExternal.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(row.periodEnd(), netExternal.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(last.periodEnd(), last.endBalanceOrig()));
        return XirrCalculator.annualizedOrCumulative(flows, rows.size());
    }

    private Long previousPeriodId(FactSlice slice, Long periodId) {
        int index = slice.periodIds().indexOf(periodId);
        return index <= 0 ? null : slice.periodIds().get(index - 1);
    }

    private BigDecimal netWorth(FactSlice slice, Long periodId) {
        return sumEnd(slice, periodId, row -> true);
    }

    /**
     * v1.10 · 某一期的存量余额切面。{@code kpis()} 与三列对照共用它 ——
     * ASSET / LIABILITY / LIQUID 三个谓词只在这里出现一次。
     */
    @Override
    public PeriodBalance balanceAt(FactSlice slice, Long periodId) {
        BigDecimal nw = sumEnd(slice, periodId, row -> true);
        BigDecimal assets = sumEnd(slice, periodId, row -> row.accountClass() == AccountClass.ASSET);
        BigDecimal liabilities = slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .filter(row -> row.accountClass() == AccountClass.LIABILITY)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal liquid = sumEnd(slice, periodId, row -> row.accountLiquidity() == AccountLiquidity.LIQUID);
        return new PeriodBalance(nw, assets, liabilities, liquid);
    }

    /**
     * v0.13 · 本期「开账基线」= 本期**首次出现**账户的期末净值合计(本位币,ASSET+/LIABILITY−)。
     * 它是"你本来就有/欠、现在才开始记"的存量本金,属外部资本纳入 —— 从所有收益类指标剔除、计入账户净投入。
     */
    /**
     * v1.11 性能 · 「每期首次出现的账户」映射,原来**每次 {@link #load} 刷新一次**;
     * v1.12 FR-352 起收进 {@link FactLoadCache},同一请求内按家庭只查一次。
     *
     * <p>问题:原来 {@code snapshotMapper.firstAppearingAccountIds} 是按期查的(还带 NOT IN 子查询),
     * 而调用点全是 per-period 循环(openingBaseline / periodFlows / netWorthTrendExOpening /
     * accountPerformance)—— 报表页实测**一次请求 881 条 SQL、1.25s**。
     * 而「首次出现」是账户的属性、与查哪一期无关,一次查完在内存分组即可。</p>
     *
     * <p>为什么是请求级 / load 级缓存,而不是按 familyId 长缓存:
     * 长缓存必须在**所有**写 period_snapshot 的地方清掉(实测有 4 个文件、6 处 upsert),
     * 漏一处就是**静默算错开账基线** —— 而开账基线决定人赚/钱赚的分界,
     * 这个项目已经在它身上栽过两次。绑在请求/load 上则**结构上不可能陈旧**。</p>
     *
     * <p>手工构造 FactSlice 的单测不走 load(),此时缓存为空 → 回落到原来的按期查询,
     * 口径完全一致。</p>
     */
    private java.util.Set<Long> firstAppearingIn(long familyId, Long periodId) {
        FactLoadCache cache = currentCache();
        var map = cache == null ? null : cache.firstAppear.get(familyId);
        if (map != null) {
            return map.getOrDefault(periodId, java.util.Set.of());
        }
        // 回落:手工构造切片的单测走这里,与 v1.11 之前逐字同口径
        return new java.util.HashSet<>(snapshotMapper.firstAppearingAccountIds(familyId, periodId));
    }

    // ── v1.12 FR-352 · 请求内取数缓存 ─────────────────────────────────────
    //
    // 策略全在这三个方法里,数据结构在 FactLoadCache。分工:这里决定「缓存活多久」,
    // 那里只管装东西。读取点(familyOf / pmcAggregate / ledgerExpense / firstAppearingIn)
    // 一律「缓存拿不到就走原来的查询」—— 所以缓存为空(单测、cron)时口径与 v1.12 之前逐字相同。

    /**
     * load 级缓存的落脚点。GET 请求不用它(挂在请求属性上),所以那条路径会主动清掉 ——
     * 否则 Tomcat 线程复用时,一个「没走过 load 的请求」可能读到上一个请求留下的缓存。
     */
    private final ThreadLocal<FactLoadCache> cacheTl = new ThreadLocal<>();

    /** 每次 {@link #load} 入口:GET → 取/建请求级缓存;其它 → 建一份只管这次 load 的。 */
    private FactLoadCache beginLoad() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                && "GET".equalsIgnoreCase(sra.getRequest().getMethod())) {
            Object existing = attrs.getAttribute(FactLoadCache.ATTR,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof FactLoadCache hit) {
                cacheTl.remove();
                return hit;
            }
            FactLoadCache fresh = new FactLoadCache(true, attrs);
            attrs.setAttribute(FactLoadCache.ATTR, fresh,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            cacheTl.remove();
            return fresh;
        }
        // 非 GET / 非 Web 线程:只活到这次 load 的后续计算。写请求里「先写后读」必须读到新值,
        // 所以每次 load 都换一份新的(等于 v1.12 之前的行为 + 一次 load 内的按期批量)。
        FactLoadCache loadScoped = new FactLoadCache(false,
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());
        cacheTl.set(loadScoped);
        return loadScoped;
    }

    /**
     * 读取侧入口 · 请求属性优先(GET),否则用 load 级的;两者都没有 → null = 不缓存,走原查询。
     *
     * <p>load 级那份要验 {@code ownedBy}:ThreadLocal 在请求结束时没人清,Tomcat 线程一复用,
     * 上一个请求留下的缓存就可能被下一个请求读到(见 {@link FactLoadCache} 的 owner 注释)。</p>
     */
    private FactLoadCache currentCache() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object c = attrs.getAttribute(FactLoadCache.ATTR,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            if (c instanceof FactLoadCache hit) return hit;
        }
        FactLoadCache tl = cacheTl.get();
        return tl != null && tl.ownedBy(attrs) ? tl : null;
    }

    /** 家庭行 · {@code baseToViewFactor} 每期每指标都要读它,原来一次报表页查了 194 遍。 */
    private java.util.Optional<Family> familyOf(long familyId) {
        FactLoadCache cache = currentCache();
        if (cache == null) return familyMapper.findById(familyId);
        if (cache.families.containsKey(familyId)) {
            return java.util.Optional.ofNullable(cache.families.get(familyId));
        }
        var found = familyMapper.findById(familyId);
        cache.families.put(familyId, found.orElse(null));
        return found;
    }

    /**
     * 某期的 PMC 家庭聚合(手填收支)· 原来逐期点查,报表页 180 次。
     *
     * <p>缓存未命中时**一次把该切片的全部账期取回来** —— 调用点全是 per-period 循环,
     * 第一期就把后面 N−1 期的查询省掉了。切片外的期(极少)仍走点查。</p>
     */
    private PeriodMemberCashflowMapper.SinglePeriodAggregate pmcAggregate(FactSlice slice, Long periodId) {
        if (periodId == null) return null;
        FactLoadCache cache = currentCache();
        long familyId = slice.filter().familyId();
        if (cache == null) {
            return periodMemberCashflowMapper.findFamilyAggregateForPeriod(periodId).orElse(null);
        }
        var key = new FactLoadCache.PeriodKey(familyId, periodId);
        if (!cache.pmc.containsKey(key)) {
            List<Long> want = missing(cache.pmc, familyId, slice.periodIds(), periodId);
            if (!want.isEmpty()) {
                java.util.Map<Long, PeriodMemberCashflowMapper.SinglePeriodAggregate> got = new java.util.HashMap<>();
                for (var a : periodMemberCashflowMapper.findFamilyAggregateForPeriods(want)) {
                    if (a.periodId() != null) got.put(a.periodId(), a);
                }
                // 批量结果里没有的期 = 该期没有手填收支 → 存 null(点查返回的是一行 NULL 合计,等价)
                for (Long pid : want) cache.pmc.put(new FactLoadCache.PeriodKey(familyId, pid), got.get(pid));
            }
        }
        return cache.pmc.get(key);
    }

    /**
     * 某期的家庭支出(统一口径 · 逐笔 &gt; 总额)· 原来逐期调 {@code byPeriod},
     * 每次 3 条 SQL(家庭模式 + 逐笔汇总 + PMC),报表页累计 100 次以上。
     */
    private com.family.finance.service.expense.ExpenseLedgerService.PeriodExpense ledgerExpense(
            FactSlice slice, Long periodId) {
        FactLoadCache cache = currentCache();
        long familyId = slice.filter().familyId();
        if (cache == null || periodId == null) return expenseLedger.byPeriod(familyId, periodId);
        var key = new FactLoadCache.PeriodKey(familyId, periodId);
        if (!cache.expense.containsKey(key)) {
            List<Long> want = missing(cache.expense, familyId, slice.periodIds(), periodId);
            if (!want.isEmpty()) {
                var got = expenseLedger.byPeriods(familyId, want);
                for (Long pid : want) {
                    cache.expense.put(new FactLoadCache.PeriodKey(familyId, pid),
                            got.getOrDefault(pid,
                                    com.family.finance.service.expense.ExpenseLedgerService.PeriodExpense.none(pid)));
                }
            }
        }
        return cache.expense.get(key);
    }

    /** 「切片的全部期 + 当前这一期」里还没查过的部分。 */
    private static List<Long> missing(java.util.Map<FactLoadCache.PeriodKey, ?> filled, long familyId,
                                      List<Long> slicePeriodIds, Long periodId) {
        java.util.LinkedHashSet<Long> want = new java.util.LinkedHashSet<>();
        if (periodId != null) want.add(periodId);
        if (slicePeriodIds != null) want.addAll(slicePeriodIds);
        want.removeIf(pid -> pid == null || filled.containsKey(new FactLoadCache.PeriodKey(familyId, pid)));
        return new ArrayList<>(want);
    }

    private BigDecimal openingBaseline(FactSlice slice, Long periodId) {
        if (periodId == null) return BigDecimal.ZERO;
        java.util.Set<Long> ids = firstAppearingIn(slice.filter().familyId(), periodId);
        if (ids.isEmpty()) return BigDecimal.ZERO;
        return sumEnd(slice, periodId, row -> ids.contains(row.accountId()));
    }

    private BigDecimal sumEnd(FactSlice slice, Long periodId, Predicate<AccountPeriodFact> predicate) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .filter(predicate)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal periodIncome(FactSlice slice, Long periodId) {
        return sumMeasure(slice, periodId, AccountPeriodFact::incomeBase);
    }

    private BigDecimal periodExpense(FactSlice slice, Long periodId) {
        return sumMeasure(slice, periodId, AccountPeriodFact::expenseBase);
    }

    private BigDecimal periodPnl(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .map(AccountPeriodFact::periodPnlBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal sumMeasure(FactSlice slice, Long periodId, java.util.function.Function<AccountPeriodFact, BigDecimal> mapper) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .map(mapper)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * v0.4.3 B2 · 月均支出统一源 · PMC 优先 · cash_flow fallback。
     *
     * <p>v0.3 引入 period_member_cashflow.total_expense_input(用户在 /entry 第一步填家庭口径) ·
     * 比 cash_flow 表 by-account 加和更准(用户可能只填家庭总额没逐笔)。</p>
     *
     * <p>backward compat:PMC 空 → fallback 老 cash_flow 加和路径。</p>
     */
    private BigDecimal averageExpense(FactSlice slice, int maxPeriods) {
        // 1) v1.8 · 走统一口径(逐笔 > 总额,不相加,排除现金调整)。
        //    维持现行语义:除以**实际取到的期数**而不是固定除 maxPeriods —— 数据不足时不低估月均支出。
        long familyId = slice.filter().familyId();
        // v1.18.7 · 剔除进行中账期:半个月的支出按整月进均值 → 月均偏低 → 紧急储备虚高。
        //   紧急储备的分子(流动资产)是实时的,分母若混进半个月就是「实时 ÷ 均值」的错配。
        var recent = expenseLedger.recentClosed(familyId, maxPeriods);
        if (!recent.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var pe : recent) sum = sum.add(pe.amountBase());
            // v0.5 修 · 本位币 → view(与 endBalanceBase 同口径 · 紧急储备比值不随币种漂移)
            return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN)
                    .multiply(baseToViewFactor(slice)).setScale(2, RoundingMode.HALF_EVEN);
        }
        // 2) Fallback · v0.2 cash_flow 表加和
        List<Long> ids = slice.periodIds();
        if (ids.isEmpty()) {
            return zero();
        }
        int from = Math.max(0, ids.size() - maxPeriods);
        List<Long> window = ids.subList(from, ids.size());
        // BUG-FIX v0.8(v05-CCY-INV-1):原先逐期加 expenseBase,窗口早期账期缺 fx → 该期 expenseBase 落原币未换,
        // 与分子(流动资产取末期、已 ensure 汇率)不同口径 → 紧急储备比值随币种漂移。
        // 改:加原币 expenseOrig(币种无关)× 末期 baseToViewFactor(与分子同一换算),比值恒定。
        BigDecimal totalOrig = window.stream()
                .map(periodId -> sumMeasure(slice, periodId, AccountPeriodFact::expenseOrig))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalOrig.divide(BigDecimal.valueOf(window.size()), 2, RoundingMode.HALF_EVEN)
                .multiply(baseToViewFactor(slice)).setScale(2, RoundingMode.HALF_EVEN);
    }

    private LocalDate periodStart(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .findFirst()
                .map(AccountPeriodFact::periodStart)
                .orElse(slice.filter().rangeStart());
    }

    private LocalDate periodEnd(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .findFirst()
                .map(AccountPeriodFact::periodEnd)
                .orElse(slice.filter().rangeEnd());
    }

    private String label(FactSlice slice, Long periodId) {
        return label(periodStart(slice, periodId));
    }

    private String label(LocalDate periodStart) {
        return periodStart == null ? "" : MONTH_LABEL.format(periodStart);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
    }
}
