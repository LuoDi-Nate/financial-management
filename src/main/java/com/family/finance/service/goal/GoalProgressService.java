package com.family.finance.service.goal;

import com.family.finance.calc.GoalProgressCalculator;
import com.family.finance.calc.GoalProjector;
import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.goal.TimeMode;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.GoalAccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import com.family.finance.repository.SnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标进度 + 三情景预测组合服务 · v0.3 FR-50。
 *
 * <p>v0.3 修订(2026-05-13):月度收支改为成员级 {@link PeriodMemberCashflowMapper}。
 * 月供 / 应急基线由家庭聚合(SUM 跨成员)计算。</p>
 */
@Service
@RequiredArgsConstructor
public class GoalProgressService {

    private static final int CONTRIBUTION_LOOKBACK_PERIODS = 6;
    private static final int EMERGENCY_BASELINE_LOOKBACK = 3;
    private static final int PROJECTION_YEARS = 30;

    private final GoalService goalService;
    private final FactViewService factViewService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final SnapshotMapper snapshotMapper;
    private final PeriodMemberCashflowMapper memberCashflowMapper;
    private final GoalMetricEvaluator metricEvaluator;   // v0.16 · CUSTOM 指标求值
    private final GoalAccountMapper goalAccountMapper;   // v0.16 · 绑定账户
    /** v1.8 · 家庭支出唯一口径入口(逐笔 > 总额)· 见 ExpenseLedgerService 类注释 */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetirementPlanService retirementPlans;

    public GoalProgress compute(long familyId, Goal goal) {
        // v0.16 · 自定义追踪目标走通用 evaluator + pace;预设三类保持既有口径 + 三情景
        if (goal.getGoalType() == GoalType.CUSTOM) return computeCustom(familyId, goal);
        if (retirementPlans != null && goal.getGoalType() == GoalType.RETIREMENT) {
            var retirement = retirementPlans.active(familyId, goal);
            if (retirement.isPresent()) {
                var view = retirement.get(); var r = view.result();
                Goal displayed = new Goal();
                org.springframework.beans.BeanUtils.copyProperties(goal, displayed);
                displayed.setName(view.plan().getName()); displayed.setTargetDate(r.retirementDate());
                return new GoalProgress(displayed, goalService.parseParams(goal), r.principal(), r.target(),
                        r.coverage() == null ? BigDecimal.ZERO : r.coverage(), r.monthlyContribution(), null,
                        goal.metricOrDefault(), goal.comparatorOrDefault(), goal.timeModeOrDefault(), null,
                        view.plan().getAccountIds().size(), view);
            }
        }

        GoalParams params = goalService.parseParams(goal);
        BigDecimal pv = computePv(familyId, goal.getGoalType());
        BigDecimal autoBaseline = (goal.getGoalType() == GoalType.EMERGENCY)
            ? computeEmergencyAutoBaseline(familyId) : null;
        BigDecimal target = GoalProgressCalculator.computeTarget(goal.getGoalType(), params, autoBaseline);
        BigDecimal progress = GoalProgressCalculator.computeProgress(pv, target);
        BigDecimal monthlyContribution = computeMonthlyContributionMedian(familyId);
        GoalProjector.ScenarioResult scenarios = GoalProjector.project(
            pv, monthlyContribution, target, PROJECTION_YEARS);
        return GoalProgress.preset(goal, params, pv, target, progress, monthlyContribution, scenarios);
    }

    /** v0.16 · 自定义追踪目标:指标聚合当前值 + 达标率/倒计时/pace(无三情景)。 */
    private GoalProgress computeCustom(long familyId, Goal goal) {
        Set<Long> accountIds = new HashSet<>(goalAccountMapper.findAccountIds(familyId, goal.getId()));
        GoalMetric metric = goal.metricOrDefault();
        GoalComparator cmp = goal.comparatorOrDefault();
        BigDecimal pv = metricEvaluator.current(familyId, metric, accountIds);
        BigDecimal target = goal.getTargetValue();
        LocalDate created = goal.getCreatedAt() == null ? LocalDate.now() : goal.getCreatedAt().toLocalDate();
        LocalDate deadline = goal.timeModeOrDefault() == TimeMode.DEADLINE ? goal.getTargetDate() : null;
        GoalPaceCalculator.Pace pace = GoalPaceCalculator.compute(
            metric, cmp, pv, target, null, created, deadline, LocalDate.now());
        BigDecimal progress = pace.attainPct() == null ? BigDecimal.ZERO : pace.attainPct();
        return GoalProgress.custom(goal, null, pv, target, progress, pace, accountIds.size());
    }

    public List<GoalProgress> computeAll(long familyId) {
        List<Goal> goals = goalService.findActiveByFamily(familyId);
        List<GoalProgress> out = new ArrayList<>(goals.size());
        for (Goal g : goals) out.add(compute(familyId, g));
        return out;
    }

    // ---------- PV 计算 ----------

    public BigDecimal computePv(long familyId, GoalType type) {
        if (type == GoalType.EMERGENCY) return computeCashPv(familyId);
        FactSlice slice = factViewService.loadDefault(familyId);
        KpiSnapshot kpi = factViewService.kpis(slice);
        return kpi.netWorth() == null ? BigDecimal.ZERO : kpi.netWorth();
    }

    private BigDecimal computeCashPv(long familyId) {
        // v1.23 · 现金现值 = **当前**余额,属余额轴 → 锚最新 OPEN(双活跃下即进行期)
        Period current = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (current == null) {
            List<Period> latest = periodMapper.findLatest(familyId, 1);
            if (latest.isEmpty()) return BigDecimal.ZERO;
            current = latest.get(0);
        }
        return snapshotMapper.sumEndBalanceByAccountType(familyId, current.getId(), "CASH")
            .orElse(BigDecimal.ZERO);
    }

    // ---------- 月度供款中位数 ----------
    // v0.3 修订:用 period_member_cashflow 家庭聚合(SUM 跨成员)再算中位

    public BigDecimal computeMonthlyContributionMedian(long familyId) {
        List<FamilyPeriodAggregate> recent = memberCashflowMapper
            .findFamilyAggregateRecent(familyId, CONTRIBUTION_LOOKBACK_PERIODS);
        List<BigDecimal> savings = recent.stream()
            // v1.8 · 支出走统一口径(逐笔 > 总额);收入侧不动
            .map(a -> a.totalIncome().subtract(expenseLedger.byPeriod(familyId, a.periodId()).amountBase()))
            .toList();
        return GoalProgressCalculator.medianMonthlyContribution(savings);
    }

    /**
     * 应急 auto_baseline = 过去 3 期 SUM(member total_expense_input) 的中位。
     */
    public BigDecimal computeEmergencyAutoBaseline(long familyId) {
        // v1.8 · 走统一口径(逐笔 > 总额)。**这是最隐蔽的一处** —— 若漏改,启用逐笔的家庭
        // 会因 PMC 里没有总额而拿到偏小甚至 null 的基线,应急目标凭空变容易达成,
        // 而页面上完全看不出异常。
        // 期集合仍取 PMC 近 3 期(与 v1.8 之前一致 —— 那时「填了但支出为 0」的月份是以 0 参与中位的,
        // 若换成 ledger.recent 会把这些月剔掉,基线从 0 变 null,应急目标的判定语义就变了);
        // 金额则走统一口径。
        List<FamilyPeriodAggregate> pmc = memberCashflowMapper
            .findFamilyAggregateRecent(familyId, EMERGENCY_BASELINE_LOOKBACK);
        if (pmc.isEmpty()) return null;
        var byPeriod = expenseLedger.byPeriods(familyId,
            pmc.stream().map(FamilyPeriodAggregate::periodId).toList());
        List<BigDecimal> expenses = pmc.stream()
            .map(a -> byPeriod.get(a.periodId()).amountBase())
            .toList();
        return GoalProgressCalculator.medianMonthlyContribution(expenses);
    }

    // ---------- VO ----------

    public record GoalProgress(
        Goal goal,
        GoalParams params,
        BigDecimal pv,
        BigDecimal target,
        BigDecimal progress,
        BigDecimal monthlyContribution,
        GoalProjector.ScenarioResult scenarios,
        // ── v0.16 通用追踪 ──
        GoalMetric metric,
        GoalComparator comparator,
        TimeMode timeMode,
        GoalPaceCalculator.Pace pace,
        int accountCount,
        RetirementPlanService.View retirement
    ) {
        public GoalProgress(Goal goal, GoalParams params, BigDecimal pv, BigDecimal target, BigDecimal progress,
                            BigDecimal monthlyContribution, GoalProjector.ScenarioResult scenarios, GoalMetric metric,
                            GoalComparator comparator, TimeMode timeMode, GoalPaceCalculator.Pace pace, int accountCount) {
            this(goal, params, pv, target, progress, monthlyContribution, scenarios, metric, comparator, timeMode, pace, accountCount, null);
        }
        /** 向后兼容 7 参构造器(v0.3 老调用/测试继续用;新字段按 goal 默认回填)。 */
        public GoalProgress(Goal goal, GoalParams params, BigDecimal pv, BigDecimal target, BigDecimal progress,
                            BigDecimal monthlyContribution, GoalProjector.ScenarioResult scenarios) {
            this(goal, params, pv, target, progress, monthlyContribution, scenarios,
                 goal == null ? null : goal.metricOrDefault(),
                 goal == null ? null : goal.comparatorOrDefault(),
                 goal == null ? null : goal.timeModeOrDefault(), null, 0);
        }

        /** 预设三类(退休/教育/应急)· 保留三情景,无 pace。 */
        static GoalProgress preset(Goal g, GoalParams p, BigDecimal pv, BigDecimal target, BigDecimal progress,
                                   BigDecimal contrib, GoalProjector.ScenarioResult sc) {
            return new GoalProgress(g, p, pv, target, progress, contrib, sc,
                g.metricOrDefault(), g.comparatorOrDefault(), g.timeModeOrDefault(), null, 0);
        }
        /** 自定义追踪 · 有 pace/倒计时,无三情景。 */
        static GoalProgress custom(Goal g, GoalParams p, BigDecimal pv, BigDecimal target, BigDecimal progress,
                                   GoalPaceCalculator.Pace pace, int accountCount) {
            return new GoalProgress(g, p, pv, target, progress, null, null,
                g.metricOrDefault(), g.comparatorOrDefault(), g.timeModeOrDefault(), pace, accountCount);
        }

        public boolean targetReached() {
            if (retirement != null) return retirement.result().reached();
            if (pace != null) return pace.status() == GoalPaceCalculator.Status.ACHIEVED;
            return target != null && pv != null && target.signum() > 0 && pv.compareTo(target) >= 0;
        }
        public BigDecimal progressPct() {
            // 2026-07-19 评审:百分比精度 2 位小数(57% → 57.38%)
            return (progress == null ? BigDecimal.ZERO : progress)
                .movePointRight(2).setScale(2, java.math.RoundingMode.HALF_EVEN);
        }
        public LocalDate neutralDate() {
            if (retirement != null) return retirement.result().neutralDate();
            return scenarios == null ? null : scenarios.neutralDate();
        }
        // ── v0.16 视图辅助 ──
        public boolean isCustom() { return goal.getGoalType() == GoalType.CUSTOM; }
        public boolean isNewRetirement() { return retirement != null; }
        public String progressLabel() {
            if (retirement != null && (!retirement.result().ready() || retirement.result().coverage() == null))
                return retirement.result().message();
            return progressPct().setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString() + "%";
        }
        public boolean isRate() { return metric != null && metric.isRate(); }
        public Long daysLeft() { return pace == null ? null : pace.daysLeft(); }
        public String paceStatus() { return pace == null ? "NONE" : pace.status().name(); }
        /** 时间进度百分数(整数;长期/无截止为 null)。 */
        public Integer timePctInt() {
            if (pace == null || pace.timePct() == null) return null;
            return pace.timePct().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_EVEN).intValue();
        }
        /** 条带用:当前值紧凑显示(收益率→N%;金额→N.N万)。 */
        public String currentDisp() { return compactVal(pv); }
        /** 条带用:目标值紧凑显示。 */
        public String targetDisp() { return compactVal(target); }
        private String compactVal(BigDecimal v) {
            if (v == null) return "—";
            // v1.11.1 · 比率类目标保留 **1 位小数**。原来 setScale(0) 把储蓄率 8.4% 显示成 8%、
              //   0.4% 显示成 0% —— 维护者反馈「0%/8% 不够直观」:整数化之后月度推进(几个零点几)
              //   全被吃掉,条带上看不出任何变化。金额类本来就是 N.N万,已有 1 位小数。
              if (isRate()) return v.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
            return v.divide(java.math.BigDecimal.valueOf(10000), 1, java.math.RoundingMode.HALF_UP).toPlainString() + "万";
        }
    }
}
