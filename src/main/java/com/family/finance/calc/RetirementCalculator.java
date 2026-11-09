package com.family.finance.calc;

import com.family.finance.domain.goal.RetirementPlan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 确定性购买力基准,无模型、无数据库、无隐式当前时间。 */
public final class RetirementCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    public static final int HORIZON_MONTHS = 600;
    private RetirementCalculator() { }

    public enum State { READY, INCOME_COVERS, MISSING, NO_FINITE_TARGET, RESERVES_SHORT }
    public record Scenario(String label, BigDecimal returnPct, List<BigDecimal> path,
                           LocalDate firstReached, BigDecimal atTargetMonth, BigDecimal targetMonthGap) { }
    public record Result(State state, String message, LocalDate asOf, BigDecimal assets, BigDecimal debt,
                         BigDecimal reserves, BigDecimal principal, BigDecimal monthlyExpense,
                         BigDecimal monthlyIncome, BigDecimal monthlyContribution, BigDecimal realRate,
                         BigDecimal target, BigDecimal gap, BigDecimal supportedExpense, BigDecimal coverage,
                         List<String> problems, List<Scenario> scenarios, LocalDate retirementDate) {
        /** 当前运行可作有限门槛判断,不代表配置保存状态或预测是否可用。 */
        public boolean ready() { return state == State.READY || state == State.INCOME_COVERS; }
        public boolean reached() {
            BigDecimal requiredAssets = totalAssetNeed();
            return ready() && assets != null && requiredAssets != null && assets.compareTo(requiredAssets) >= 0;
        }
        public BigDecimal totalNeed() { return target == null || reserves == null ? null : target.add(reserves); }
        public BigDecimal totalAssetNeed() { return totalNeed()==null || debt==null?null:totalNeed().add(debt); }
        public BigDecimal netFunds() { return assets == null || debt == null ? null : assets.subtract(debt); }
        public BigDecimal coveragePct() { return coverage == null ? null : coverage.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN); }
        public LocalDate neutralDate() { return scenarios.size()==1?scenarios.getFirst().firstReached():scenarios.size()==3?scenarios.get(1).firstReached():null; }
        public String aiSummary() {
            return "退休生活账 · 今天购买力(" + asOf + ") · 状态=" + message
                    + " · 所选账户资产A=" + assets + " · 所需总资金F=" + totalAssetNeed()
                    + " · 目标进度比率=" + coverage + " · 资金缺口=" + gap
                    + " · 生活本金P=" + principal + " · 生活本金门槛T=" + target
                    + " · 大额支出准备金B=" + reserves + " · 负债D=" + debt
                    + " · 生活费/月=" + monthlyExpense + " · 计划投入/月=" + monthlyContribution
                    + " · 目标退休月=" + retirementDate + " · 中性首次达到=" + neutralDate()
                    + "。数值均为系统计算,不得重算、补全缺失或把门槛说成保证退休。";
        }
    }

    public static Result calculate(RetirementPlan p, LocalDate today, BigDecimal assets, BigDecimal debt,
                                   BigDecimal autoExpenseToday, List<String> sourceProblems) {
        List<String> problems = new ArrayList<>(sourceProblems);
        boolean revision2=p.getVersion()>=2;
        if (!revision2 && !p.isBudgetConfirmed()) problems.add("生活预算尚未确认");
        if (!revision2 && !p.isReservesConfirmed()) problems.add("大额支出准备金尚未确认");
        if (!revision2 && !p.isNoOverlapConfirmed()) problems.add("生活费与预留的归属尚未核对");
        if (!revision2 && !p.isAssetsConfirmed()) problems.add("资金范围尚未确认");
        if (!revision2 && !p.isIncomeConfirmed()) problems.add("退休收入的开始时间与购买力尚未确认");
        if (!revision2 && !p.isContributionConfirmed()) problems.add("计划月投入尚未确认");
        if (!revision2 && debt != null && debt.signum() > 0 && !p.isAfterDebtConfirmed()) problems.add("需确认偿清负债后的计划月投入");
        BigDecimal inflation = fraction(p.getInflationPct());
        BigDecimal real = p.isDirectRealRate() ? fraction(p.getRealReturnPct())
                : p.getNominalReturnPct() == null || inflation == null ? null
                : BigDecimal.ONE.add(fraction(p.getNominalReturnPct())).divide(BigDecimal.ONE.add(inflation), MC).subtract(BigDecimal.ONE);
        BigDecimal factor = revision2 && today.equals(p.getPriceDate()) ? BigDecimal.ONE
                : p.getPriceDate() == null || inflation == null ? null : priceFactor(p.getPriceDate(), today, inflation);
        BigDecimal expense = p.isAutoExpense() ? autoExpenseToday : money(scale(p.getMonthlyExpense(), factor));
        BigDecimal income = revision2?BigDecimal.ZERO:money(scale(p.getMonthlyIncome(), factor));
        BigDecimal reserves = factor == null || p.getChildReserve() == null || p.getMedicalReserve() == null
                || p.getHousingReserve() == null || p.getOtherReserve() == null ? null
                : money(scale(p.getChildReserve(), factor)).add(money(scale(p.getMedicalReserve(), factor)))
                    .add(money(scale(p.getHousingReserve(), factor))).add(money(scale(p.getOtherReserve(), factor)));
        BigDecimal contribution = p.isContributionFollowsInflation() ? money(scale(p.getMonthlyContribution(), factor)) : p.getMonthlyContribution();
        if (!revision2) {
            if (expense == null) problems.add("生活预算缺失或账上建议不足");
            if (income == null) problems.add("退休月收入缺失");
            if (reserves == null) problems.add("预留金额未填,无需预留也应明确填零");
            if (contribution == null) problems.add("计划月投入未填");
            if (assets == null || debt == null) problems.add("资金数据尚未补齐");
            if (real == null || inflation == null) problems.add("收益或通胀假设未填");
            if (p.getLowReturnPct() == null || p.getMiddleReturnPct() == null || p.getHighReturnPct() == null) problems.add("积累期三情景回报未填");
        }
        LocalDate retirementDate = null;
        try { retirementDate = YearMonth.parse(p.getTargetMonth()).atEndOfMonth(); }
        catch (Exception e) { if (!revision2) problems.add("目标退休年月未填或无效"); }
        BigDecimal principal = assets == null || debt == null || reserves == null ? null : assets.subtract(debt).subtract(reserves);
        BigDecimal need = expense == null || income == null ? null : expense.subtract(income).max(BigDecimal.ZERO);
        BigDecimal target = null;
        if (need != null) {
            if (need.signum() == 0 && (revision2 || real != null)) target = BigDecimal.ZERO;
            else if (real != null && real.signum() > 0) target = need.multiply(TWELVE).divide(real, MC);
        }
        BigDecimal requiredAssets = target == null || reserves == null || debt == null ? null
                : revision2 ? money(target).add(money(reserves)).add(money(debt)) : target.add(reserves).add(debt);
        // R2 的目标进度是所选资产 A / 总资金需求 F;P / T 仅保留给旧版和生活本金预测。
        BigDecimal progressFunds = revision2 ? money(assets) : principal;
        BigDecimal progressNeed = revision2 ? requiredAssets : target;
        BigDecimal gap = progressNeed == null || progressFunds == null ? null : progressNeed.subtract(progressFunds).max(BigDecimal.ZERO);
        BigDecimal coverage = progressNeed == null || progressNeed.signum() == 0 || progressFunds == null ? null : progressFunds.divide(progressNeed, MC);
        BigDecimal supported = real == null || real.signum() <= 0 || principal == null || principal.signum() < 0 || income == null ? null
                : income.add(principal.multiply(real).divide(TWELVE, MC));
        State state;
        String message;
        // R2 的来源说明不是全局开关;只有当前判断真正依赖的数据缺失才影响运行状态。
        boolean missingCore = principal == null || need == null || (need.signum() > 0 && real == null);
        if (revision2 ? missingCore : !problems.isEmpty()) {
            state = State.MISSING; message = revision2 ? "部分指标暂无数据" : "待补齐 / 核对";
            if (!revision2) { coverage = null; gap = null; supported = null; }
        }
        else if (principal.signum() < 0) { state = State.RESERVES_SHORT; message = "大额支出准备金仍有缺口"; }
        else if (target == null) { state = State.NO_FINITE_TARGET; message = "这组假设没有有限的永久本金门槛"; }
        else if (need.signum() == 0) { state = State.INCOME_COVERS; message = revision2?"生活费明确为零,仍需保留负债与预留":"计划退休起的收入覆盖生活费,仍需保留预留"; }
        else { state = State.READY; message = assets.compareTo(requiredAssets) >= 0 ? "按当前假设达到门槛" : "按当前假设尚有缺口"; }
        List<Scenario> scenarios = List.of();
        boolean projectionRates = revision2 && !p.isAdvancedScenarios() ? p.getMiddleReturnPct()!=null
                : p.getLowReturnPct()!=null && p.getMiddleReturnPct()!=null && p.getHighReturnPct()!=null;
        if ((state == State.READY || state == State.INCOME_COVERS) && contribution != null && inflation != null
                && retirementDate != null && projectionRates && (!revision2 || p.isSimulateFuture())) {
            scenarios = revision2 && !p.isAdvancedScenarios()
                    ? List.of(project("当前假设",p.getMiddleReturnPct(),principal,contribution,inflation,p.isContributionFollowsInflation(),target,today,retirementDate))
                    : List.of(project("低回报", p.getLowReturnPct(), principal, contribution, inflation, p.isContributionFollowsInflation(), target, today, retirementDate),
                    project("中回报", p.getMiddleReturnPct(), principal, contribution, inflation, p.isContributionFollowsInflation(), target, today, retirementDate),
                    project("高回报", p.getHighReturnPct(), principal, contribution, inflation, p.isContributionFollowsInflation(), target, today, retirementDate));
        }
        return new Result(state, message, today, money(assets), money(debt), money(reserves), money(principal), money(expense), money(income),
                money(contribution), real, money(target), money(gap), money(supported), coverage, List.copyOf(problems), scenarios, retirementDate);
    }

    private static Scenario project(String label, BigDecimal pct, BigDecimal principal, BigDecimal contribution,
                                    BigDecimal inflation, boolean indexed, BigDecimal target, LocalDate today, LocalDate targetDate) {
        BigDecimal q = BigDecimal.valueOf(Math.pow((1 + fraction(pct).doubleValue()) / (1 + inflation.doubleValue()), 1d / 12d));
        BigDecimal priceMonth = BigDecimal.valueOf(Math.pow(1 + inflation.doubleValue(), 1d / 12d));
        BigDecimal price = BigDecimal.ONE, value = principal;
        List<BigDecimal> path = new ArrayList<>(); path.add(money(value));
        LocalDate first = value.compareTo(target) >= 0 ? today : null;
        int targetIndex = (int) Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(targetDate)));
        BigDecimal atTarget = targetIndex == 0 ? value : null;
        boolean exhausted = false;
        for (int m = 1; m <= HORIZON_MONTHS; m++) {
            price = price.multiply(priceMonth, MC);
            // 已耗尽的资金不靠负本金的投资回报生息;持续流出保持原符号并显式画出。
            value = value.signum() < 0 ? value.add(indexed ? contribution : contribution.divide(price, MC))
                    : value.multiply(q, MC).add(indexed ? contribution : contribution.divide(price, MC));
            if (value.signum() < 0) exhausted = true;
            path.add(money(value));
            if (m == targetIndex) atTarget = value;
            if (first == null && !exhausted && value.compareTo(target) >= 0) first = YearMonth.from(today).plusMonths(m).atEndOfMonth();
        }
        return new Scenario(label, pct, List.copyOf(path), first, money(atTarget),
                atTarget == null ? null : money(target.subtract(atTarget).max(BigDecimal.ZERO)));
    }

    public static BigDecimal priceFactor(LocalDate from, LocalDate to, BigDecimal inflationFraction) {
        return BigDecimal.valueOf(Math.pow(1 + inflationFraction.doubleValue(), ChronoUnit.DAYS.between(from, to) / 365.2425d));
    }
    public static BigDecimal fraction(BigDecimal pct) { return pct == null ? null : pct.movePointLeft(2); }
    private static BigDecimal scale(BigDecimal amount, BigDecimal factor) { return amount == null || factor == null ? null : amount.multiply(factor, MC); }
    private static BigDecimal money(BigDecimal amount) { return amount == null ? null : amount.setScale(2, RoundingMode.HALF_EVEN); }
}
