package com.family.finance.service.goal;

import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.service.MoneyFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Read-only explanation of the same results used by the goal card. */
public record GoalProgressSummary(String basis, List<Section> sections, String assumption) {
    public record Section(String title, String formula, String values, String note, boolean privateValues) { }

    public static GoalProgressSummary from(GoalProgressService.GoalProgress progress, boolean includeForecast) {
        var retirement = progress.retirement();
        var forecast = progress.forecast();
        String currency = forecast != null ? forecast.currency() : retirement != null ? retirement.plan().getBaseCurrency() : null;
        String basis = currency == null ? "家庭本位币" : currency + " · 家庭本位币";
        boolean privateValues = !progress.isCustom() || progress.goal().metricOrDefault().kind() == GoalMetric.Kind.AMOUNT;
        if (!privateValues) basis = progress.goal().metricOrDefault().isRate() ? "百分比指标 · 当前目标口径" : "月数指标 · 当前目标口径";
        List<Section> sections = new ArrayList<>();
        if (retirement != null && retirement.plan().getVersion() >= 2) {
            var r = retirement.result();
            basis += " · 观察日 " + r.asOf();
            boolean finite = r.realRate() != null && r.realRate().signum() > 0 && r.target() != null && r.monthlyExpense() != null;
            sections.add(new Section("生活本金", finite ? "每月生活费 × 12 ÷ 实际年回报"
                    : r.target() != null && r.target().signum() == 0 ? "生活费为零,生活本金需求为零" : "正的实际回报才能形成有限的永久本金门槛",
                    finite ? amount(currency,r.monthlyExpense()) + " × 12 ÷ " + percent(r.realRate()) + " = " + amount(currency,r.target())
                            : r.target() == null && r.realRate() != null && r.realRate().signum() <= 0 ? "无有限的永久本金门槛" : "生活本金需求:" + amount(currency,r.target()),
                    (retirement.plan().isAutoExpense() ? "生活费跟随家庭定稿支出。" : "生活费由本目标填写。")
                            + retirement.returnBasis() + realRateBasis(retirement), true));
            sections.add(new Section("财务自由所需资金", "生活本金 + 大额支出准备金 + 全部负债",
                    progress.target() == null ? "生活本金:" + amount(currency,r.target()) + "\n准备金:" + amount(currency,r.reserves()) + " · 负债:" + amount(currency,r.debt())
                            : amount(currency,r.target()) + " + " + amount(currency,r.reserves()) + " + " + amount(currency,r.debt()) + " = " + amount(currency,progress.target()),
                    "准备金与负债各计一次。", true));
        } else if (retirement != null) {
            var r = retirement.result();
            basis += " · 观察日 " + r.asOf();
            sections.add(new Section("原计划生活本金目标", "月生活费减去退休月收入,不低于零,再 × 12 ÷ 实际年回报",
                    "月生活费:" + amount(currency,r.monthlyExpense()) + " · 退休月收入:" + amount(currency,r.monthlyIncome())
                            + "\n生活本金目标:" + amount(currency,progress.target()), retirement.returnBasis() + realRateBasis(retirement), true));
            sections.add(new Section("原计划可生息本金", "所选账户资产 - 全部负债 - 大额支出准备金",
                    amount(currency,r.assets()) + " - " + amount(currency,r.debt()) + " - " + amount(currency,r.reserves())
                            + " = " + amount(currency,r.principal()), "沿用此计划采用的原算法。", true));
        } else {
            String formula = switch (progress.goal().getGoalType()) {
                case RETIREMENT -> "原计划:月生活费 × 12,按退休年份通胀折算后除以提取率";
                case EDUCATION -> "教育预算按目标年份的通胀假设折算";
                case EMERGENCY -> "家庭月支出基线 × 计划储备月数";
                case CUSTOM -> "目标值由本目标设置";
            };
            String note = progress.goal().getGoalType() == GoalType.EMERGENCY && progress.params() != null
                    ? (Boolean.FALSE.equals(progress.params().getAutoBaseline()) ? "使用手填月支出基线" : "使用近3期家庭月支出中位数")
                    : progress.isCustom() ? "指标:" + progress.goal().metricOrDefault().label() + " · " + progress.goal().comparatorOrDefault().label() : "沿用原目标口径";
            sections.add(new Section("目标门槛", formula, "目标:" + value(progress,currency,progress.target()), note, privateValues));
        }
        boolean reducing = progress.isCustom() && progress.comparator() == GoalComparator.LTE;
        String progressFormula = reducing ? "当前值不高于目标即达成;否则按目标值 ÷ 当前值显示进度"
                : retirement != null && retirement.plan().getVersion() >= 2 ? "所选账户当前合计 ÷ 财务自由所需资金"
                : retirement != null ? "可生息本金 ÷ 原计划生活本金目标"
                : "当前值 ÷ 目标值";
        sections.add(new Section("当前进度", progressFormula,
                "当前:" + value(progress,currency,progress.pv()) + " · 目标:" + value(progress,currency,progress.target())
                        + "\n进度:" + (progress.pv() == null || progress.target() == null ? "暂不可用" : progress.progressLabel()),
                retirement != null && retirement.plan().getVersion() >= 2 ? "所选账户采用当前账面值,统一折算为家庭本位币。"
                        : "按目标既有达标方向及进度规则展示。", privateValues));
        String assumption = "";
        if (includeForecast && forecast != null && forecast.supported() && forecast.result() != null) {
            var result = forecast.result();
            String source = forecast.sourceLabel();
            if (forecast.window() != null) source += " · " + forecast.window().note();
            String formula;
            String values;
            String note = result.reason() + "。来源:" + source;
            switch (result.state()) {
                case READY -> {
                    formula = "当前资金缺口 ÷ 月可用结余,向上取整为月数";
                    values = amount(currency,result.gap()) + " ÷ " + amount(currency,result.contribution())
                            + " → " + result.months() + "个月";
                    note = "从" + YearMonth.from(result.asOf()) + "起计" + result.months() + "个月,预计"
                            + result.dateLabel() + "。来源:" + source;
                }
                case ACHIEVED -> { formula = "当前资金已达到门槛"; values = "已达成 · 0个月"; }
                case OVER_HORIZON -> { formula = "当前缺口 ÷ 月可用结余超过600个月"; values = "超过50年"; }
                case NO_GROWTH -> { formula = "零结余或每月净流出时,缺口暂不缩小"; values = "月可用结余:" + amount(currency,result.contribution()); }
                default -> { formula = "需要可用的资金缺口和正月结余才能估算"; values = null; }
            }
            sections.add(new Section("预计达成时间", formula, values, note, result.state() != com.family.finance.calc.GoalCashflowCalculator.State.ACHIEVED
                    && result.state() != com.family.finance.calc.GoalCashflowCalculator.State.OVER_HORIZON));
            assumption = "按当前目标金额与月结余不变估算,不额外计入未来投资收益、加薪或物价变化。";
        } else if (includeForecast && progress.neutralDate() != null) {
            sections.add(new Section("原投资情景预计时间", "采用原计划的月投入及投资回报情景", null,
                    "中性情景首次达到:" + YearMonth.from(progress.neutralDate()), false));
        }
        return new GoalProgressSummary(basis, List.copyOf(sections), assumption);
    }

    private static String amount(String currency, BigDecimal value) {
        if (value == null) return "待来源";
        return currency == null ? value.setScale(2,RoundingMode.HALF_EVEN).toPlainString() + "(本位币)" : MoneyFormat.format2(currency,value);
    }
    private static String value(GoalProgressService.GoalProgress progress, String currency, BigDecimal value) {
        if (value == null) return "待来源";
        GoalMetric metric = progress.isCustom() ? progress.goal().metricOrDefault() : null;
        if (metric != null && metric.kind() == GoalMetric.Kind.RATE) return value.stripTrailingZeros().toPlainString() + "%";
        if (metric != null && metric.kind() == GoalMetric.Kind.MONTHS) return value.stripTrailingZeros().toPlainString() + "个月";
        return amount(currency,value);
    }
    private static String realRateBasis(RetirementPlanService.View retirement) {
        var effective = retirement.effective();
        if (effective == null || effective.isDirectRealRate() || effective.getNominalReturnPct() == null || effective.getInflationPct() == null) return "";
        return "。实际回报=(1+名义回报" + effective.getNominalReturnPct().stripTrailingZeros().toPlainString()
                + "%)/(1+通胀" + effective.getInflationPct().stripTrailingZeros().toPlainString() + "%)-1";
    }
    private static String percent(BigDecimal fraction) { return fraction.movePointRight(2).stripTrailingZeros().toPlainString() + "%"; }
}
