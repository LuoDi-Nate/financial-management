package com.family.finance.service;

import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 家庭月度收支指标 · v0.3 FR-51(2026-05-13 修订为成员级)。
 *
 * <p>数据源:{@code period_member_cashflow} 表 by 成员填报 ·
 * 家庭总额 = SUM(各成员) · 跨成员聚合后按"近 N 期均值/中位"算指标。</p>
 *
 * <p>fallback: v0.2 cash_flow 表(account-level INCOME / EXPENSE)。</p>
 */
@Service
@RequiredArgsConstructor
public class HouseholdCashflowService {

    private static final int LOOKBACK_PERIODS = 12;

    private final PeriodMemberCashflowMapper cashflowMapper;
    private final FactViewService factViewService;
    /** v1.8 · 家庭支出唯一口径入口(逐笔 > 总额)· 见 ExpenseLedgerService 类注释 */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger;

    /** v1.8 · 走统一口径(逐笔 > 总额);都没有时才回落 cash_flow 汇总。 */
    /**
     * v1.18.7 · 走 {@code recentClosed} —— <b>剔除进行中账期</b>。
     * 月中时本月支出只录了一部分,按整月进均值会把月均拉低;而这个数是
     * 「应急金超额闲置」banner 里「实际需求」的因子,偏低会让 banner 更容易弹出
     * 并建议你把钱挪走。理由详见 {@code ExpenseLedgerService.recentClosed}。
     */
    public BigDecimal avgMonthlyExpense(long familyId) {
        var recent = expenseLedger.recentClosed(familyId, LOOKBACK_PERIODS);
        if (!recent.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var pe : recent) sum = sum.add(pe.amountBase());
            return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
        }
        return avgFromCashFlow(familyId, false);
    }

    // v0.12 FR-142 · 收入侧口径:每期收入 = PMC 手填收入(历史)优先,否则该期 cash_flow INCOME 汇总
    //   (新账期由收入侧录入 → cash_flow)。支出侧不变(2框仍填总支出)。避免新账期收入被 PMC 空值低估 + 防 NPE。
    public BigDecimal avgMonthlyIncome(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        if (recent.isEmpty()) return avgFromCashFlow(familyId, true);
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);
        BigDecimal sum = BigDecimal.ZERO;
        for (FamilyPeriodAggregate a : recent) sum = sum.add(incomeBlend(a, cashInc));
        return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
    }

    /**
     * v1.18.7 · 储蓄率<b>连它算的是哪一期一起给出</b>。
     *
     * @param rate 储蓄率(可空 = 算不出来)
     * @param periodId 这个数实际来自哪一期(可空)
     * @param periodStart 该期的起始日 · 给「2026-07 账期」这类文案用(可空)
     */
    public record SavingsRateView(BigDecimal rate, Long periodId, java.time.LocalDate periodStart) {
        public static final SavingsRateView NONE = new SavingsRateView(null, null, null);
    }

    /**
     * v1.18.7 · <b>把「这个储蓄率是哪一期的」交出来</b> —— 此前它叫「本期储蓄率」,而两条路径都
     * <b>可能不是本期</b>,页面上一个字的标注都没有。
     *
     * <ol>
     *   <li>PMC 分支取的是<b>最近一个「有 PMC 记录」的期</b>。月初还没人填收支时,
     *       它就是<b>上个月</b>,却被写成「本期储蓄率」。</li>
     *   <li>兜底分支走 {@code factViewService.savingsRate(loadDefault(...))},而那个方法锚的是
     *       {@code returnAnchorPeriodId()} = <b>最新已关账期</b> —— 一定不是进行中的本期。</li>
     * </ol>
     *
     * <p>beta 实测(v1.18.6):本期 2026-08 有 51 笔收入、<b>0 笔支出</b> ——
     * 本期储蓄率必然是 100%,而页面显示 98.4%。它显示的根本不是本期。</p>
     *
     * <p>更要命的是它和<b>实时</b>的净资产挤在同一句话里:
     * 「净资产 X,较上期 +Y(环比 +Z)。<b>本期储蓄率 98.4%</b>。」——
     * 一句话两个期。这与 v1.18.3 那次「上面的卡是本月、下面的瀑布是上月」是同一个形状。</p>
     *
     * <p>维护者拍板(2026-08-25):<b>不改口径,老老实实把账期标出来</b>。
     * 改口径(强行锚本期)会让月初数字剧烈跳动,而且本期收支没录齐时储蓄率天然虚高;
     * 标注账期既诚实、又不制造新的失真。</p>
     */
    public SavingsRateView savingsRateView(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 1);
        if (!recent.isEmpty()) {
            FamilyPeriodAggregate a = recent.get(0);
            BigDecimal income = incomeBlend(a, cashIncomeByPeriod(familyId));
            if (income.signum() > 0) {
                // v1.8 · 支出改走统一口径(逐笔 > 总额);收入侧口径不动
                BigDecimal expense = expenseLedger.byPeriod(familyId, a.periodId()).amountBase();
                return new SavingsRateView(
                        income.subtract(expense).divide(income, 6, RoundingMode.HALF_EVEN),
                        a.periodId(), a.periodStart());
            }
        }
        var slice = factViewService.loadDefault(familyId);
        Long anchor = slice.returnAnchorPeriodId();
        return new SavingsRateView(factViewService.savingsRate(slice), anchor, slice.periodStartOf(anchor));
    }

    /** 只要数值的老入口(报表页封板快照用)· 口径与 {@link #savingsRateView} 完全同一份。 */
    public BigDecimal currentSavingsRate(long familyId) {
        return savingsRateView(familyId).rate();
    }

    /** 报表只读传入的家庭本位币切片；不能回查今天的最近一期或最近一条旧总额记录。 */
    public ReportSavings reportSavings(FactSlice slice, int expectedPeriods) {
        List<Long> ids = slice.closedPeriodIds() == null ? List.of() : slice.closedPeriodIds();
        if (ids.isEmpty()) {
            return new ReportSavings(List.of(), 0, Math.max(0, expectedPeriods), BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, null, SavingsRateView.NONE);
        }
        var expenses = expenseLedger.byPeriods(slice.filter().familyId(), ids);
        var totals = cashflowMapper.findFamilyAggregateForPeriods(slice.filter().familyId(), ids).stream()
                .collect(Collectors.toMap(PeriodMemberCashflowMapper.SinglePeriodAggregate::periodId, a -> a));
        List<SeriesPoint> series = new java.util.ArrayList<>();
        List<SeriesPoint> filled = new java.util.ArrayList<>();
        for (Long id : ids) {
            var cf = factViewService.cashflowBreakdown(slice, id);
            var expense = expenses.get(id);
            var total = totals.get(id);
            boolean hasData = cf.income().signum() != 0 || cf.expense().signum() != 0 || (expense != null && expense.filled())
                    || (total != null && total.filledMembers() != null && total.filledMembers() > 0);
            SeriesPoint point = new SeriesPoint(id, slice.periodStartOf(id),
                    hasData ? cf.income() : null, hasData ? cf.expense() : null);
            series.add(point);
            if (hasData) filled.add(point);
        }
        BigDecimal sumIncome = filled.stream().map(SeriesPoint::income).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumExpense = filled.stream().map(SeriesPoint::expense).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgIncome = null, avgExpense = null, median = null;
        if (!filled.isEmpty()) {
            BigDecimal count = BigDecimal.valueOf(filled.size());
            avgIncome = sumIncome.divide(count, 2, RoundingMode.HALF_EVEN);
            avgExpense = sumExpense.divide(count, 2, RoundingMode.HALF_EVEN);
            List<BigDecimal> savings = filled.stream().map(p -> p.income().subtract(p.expense())).sorted().toList();
            int n = savings.size();
            median = n % 2 == 1 ? savings.get(n / 2)
                    : savings.get(n / 2 - 1).add(savings.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
        }
        SeriesPoint latest = series.isEmpty() ? null : series.getLast();
        BigDecimal rate = latest == null || latest.income() == null || latest.income().signum() == 0 ? null
                : latest.income().subtract(latest.expense()).divide(latest.income(), 6, RoundingMode.HALF_EVEN);
        return new ReportSavings(List.copyOf(series), filled.size(), expectedPeriods > 0 ? expectedPeriods : ids.size(),
                sumIncome, sumExpense, avgIncome, avgExpense, median,
                latest == null ? SavingsRateView.NONE : new SavingsRateView(rate, latest.periodId(), latest.periodStart()));
    }

    public record ReportSavings(List<SeriesPoint> series, int filledPeriods, int totalPeriods,
                                BigDecimal sumIncome, BigDecimal sumExpense,
                                BigDecimal averageIncome, BigDecimal averageExpense, BigDecimal medianSavings,
                                SavingsRateView latest) {}

    public BigDecimal medianMonthlySavings(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 6);
        if (recent.isEmpty()) {
            return avgMonthlyIncome(familyId).subtract(avgMonthlyExpense(familyId));
        }
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);
        // v1.8 · 支出改走统一口径(逐笔 > 总额);收入仍用 incomeBlend
        var expByPeriod = expenseLedger.byPeriods(familyId,
                recent.stream().map(FamilyPeriodAggregate::periodId).toList());
        List<BigDecimal> savings = recent.stream()
            .map(a -> incomeBlend(a, cashInc).subtract(
                    expByPeriod.containsKey(a.periodId())
                        ? expByPeriod.get(a.periodId()).amountBase() : BigDecimal.ZERO))
            .sorted().toList();
        int n = savings.size();
        if (n % 2 == 1) return savings.get(n / 2);
        return savings.get(n / 2 - 1).add(savings.get(n / 2))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
    }

    /** v0.12 · 单期收入:PMC 手填收入>0 用之(历史),否则用该期 cash_flow INCOME 汇总(新账期收入侧录入)。 */
    /**
     * v1.8 · 储蓄能力折线的数据源:近 N 期(升序)的收入 / 支出,**各按自己的口径**取。
     *
     * <p>为什么要有这个方法:折线原来直接铺 PMC 聚合,于是出现两个毛病 ——</p>
     * <ul>
     *   <li>老毛病:家庭只逐笔录收入、没填过 PMC 时,折线整条空白(beta 一直如此),
     *       而同一段的 KPI「月均收入」却有值(它会回落 cash_flow)。同屏自相矛盾。</li>
     *   <li>v1.8 新增的毛病:{@code filledMonthRatio} 改判「统一口径有值」后,
     *       逐笔家庭会被判成「有数据」→ 渲染 KPI 卡,而折线仍取 PMC → 图区空白。</li>
     * </ul>
     *
     * <p>所以期集合取「PMC 期 ∪ 统一口径有支出的期」,收入用 incomeBlend(PMC 优先否则 cash_flow),
     * 支出用 {@link com.family.finance.service.expense.ExpenseLedgerService}。没数据的点留 null → 灰柱。</p>
     */
    public List<SeriesPoint> recentSeries(long familyId, int limit) {
        var pmc = cashflowMapper.findFamilyAggregateRecent(familyId, limit);
        Map<Long, FamilyPeriodAggregate> pmcById = new java.util.LinkedHashMap<>();
        Map<Long, java.time.LocalDate> startById = new java.util.LinkedHashMap<>();
        for (var a : pmc) {
            pmcById.put(a.periodId(), a);
            startById.put(a.periodId(), a.periodStart());
        }
        // 统一口径有支出、但 PMC 里没有的期(逐笔家庭的常态)
        for (var pe : expenseLedger.recent(familyId, limit)) {
            startById.putIfAbsent(pe.periodId(), pe.periodStart());
        }
        if (startById.isEmpty()) return List.of();

        var expByPeriod = expenseLedger.byPeriods(familyId, new java.util.ArrayList<>(startById.keySet()));
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);

        List<SeriesPoint> out = new java.util.ArrayList<>();
        for (var e : startById.entrySet()) {
            Long pid = e.getKey();
            var agg = pmcById.get(pid);
            BigDecimal income = agg != null ? incomeBlend(agg, cashInc) : cashInc.get(pid);
            var pe = expByPeriod.get(pid);
            BigDecimal expense = (pe != null && pe.filled()) ? pe.amountBase() : null;
            out.add(new SeriesPoint(pid, e.getValue(), income, expense));
        }
        // 期起始升序(缺日期的排在前面,用 id 兜底)
        out.sort((x, y) -> {
            if (x.periodStart() == null && y.periodStart() == null) return Long.compare(x.periodId(), y.periodId());
            if (x.periodStart() == null) return -1;
            if (y.periodStart() == null) return 1;
            return x.periodStart().compareTo(y.periodStart());
        });
        if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
        return out;
    }

    /** 折线上的一个点。income / expense 可为 null(该月没数据 → 灰柱)。 */
    public record SeriesPoint(Long periodId, java.time.LocalDate periodStart,
                              BigDecimal income, BigDecimal expense) {}

    private BigDecimal incomeBlend(FamilyPeriodAggregate a, Map<Long, BigDecimal> cashIncomeByPeriod) {
        if (a.totalIncome() != null && a.totalIncome().signum() > 0) return a.totalIncome();
        return cashIncomeByPeriod.getOrDefault(a.periodId(), BigDecimal.ZERO);
    }

    /** 各期 cash_flow INCOME 汇总(本位币口径 · loadDefault)· 供收入侧回退。 */
    private Map<Long, BigDecimal> cashIncomeByPeriod(long familyId) {
        FactSlice slice = factViewService.loadDefault(familyId);
        return slice.rows().stream()
            .filter(r -> r.periodId() != null && r.incomeBase() != null)
            .collect(Collectors.groupingBy(AccountPeriodFact::periodId,
                Collectors.reducing(BigDecimal.ZERO, AccountPeriodFact::incomeBase, BigDecimal::add)));
    }

    /**
     * v1.8 · 判据从「PMC 有行」改成「统一口径 source != NONE」。
     * 否则只录了逐笔、没填过总额的月份会被判成「没填」,导致已填月数偏少、月储蓄中位数取样变小。
     */
    public int[] filledMonthRatio(long familyId) {
        return new int[]{expenseLedger.recent(familyId, LOOKBACK_PERIODS).size(), LOOKBACK_PERIODS};
    }

    public List<FamilyPeriodAggregate> findRecentAggregates(long familyId, int limit) {
        return cashflowMapper.findFamilyAggregateRecent(familyId, limit);
    }

    /** v0.10 · 指定期已填收支的成员数(PMC 成员级 · 给「人赚 vs 钱赚」卡完整度用)。periodId 空 → 0。 */
    public int filledMembersForPeriod(long familyId, Long periodId) {
        if (periodId == null) return 0;
        return cashflowMapper.findFamilyAggregateForPeriod(familyId, periodId)
                .map(a -> a.filledMembers() == null ? 0 : a.filledMembers())
                .orElse(0);
    }

    /**
     * v1.8 · 收窄成只算收入。原来有个 {@code expense} 开关,但支出侧已全部改走
     * {@link com.family.finance.service.expense.ExpenseLedgerService},那个分支成了死路 ——
     * 留着会让下一个人以为「支出也可以从这儿读」,正是口径散落的起点。
     */
    private BigDecimal avgIncomeFromMemberCashflow(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        if (recent.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (FamilyPeriodAggregate a : recent) {
            if (a.totalIncome() != null) sum = sum.add(a.totalIncome());
        }
        return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal avgFromCashFlow(long familyId, boolean income) {
        FactSlice slice = factViewService.loadDefault(familyId);
        if (slice.periodIds().isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = slice.rows().stream()
            .map(income ? AccountPeriodFact::incomeBase : AccountPeriodFact::expenseBase)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int n = Math.max(1, slice.periodIds().size());
        return total.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN);
    }
}
