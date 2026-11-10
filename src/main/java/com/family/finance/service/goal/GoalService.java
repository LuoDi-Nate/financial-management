package com.family.finance.service.goal;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalForecastForm;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.goal.TimeMode;
import com.family.finance.repository.GoalAccountMapper;
import com.family.finance.repository.GoalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Set;

/**
 * 目标 CRUD · v0.3 FR-50。
 *
 * <p>负责:</p>
 * <ul>
 *   <li>类型 + 参数校验(范围 / 必填字段)· 在 Service 层,不在 DB 层(MySQL CHECK 跨厂商性差)</li>
 *   <li>params_json 序列化 · 用 Jackson</li>
 *   <li>EMERGENCY target_value/target_date NULL 校准 · 其它类型反推 target_value 入库(便于查询)</li>
 *   <li>软删 / 恢复 · 沿用 v0.2 风格</li>
 * </ul>
 *
 * <p>不负责:PV 计算 / 三情景预测(那是 GoalProgressCalculator / GoalProjector 的事)。</p>
 */
@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalMapper goalMapper;
    private final ObjectMapper objectMapper;
    private final com.family.finance.repository.PeriodMemberCashflowMapper periodMemberCashflowMapper; // v0.5 FR-82
    private final GoalAccountMapper goalAccountMapper;
    /**
     * v1.8 · 家庭支出唯一口径入口。ExpenseLedgerService 只依赖 mapper,
     * 所以本类(原本只依赖 mapper 层)注入它不会引入循环依赖。
     */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger; // v0.16 · 自定义目标绑定账户
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.repository.RetirementPlanMapper retirementPlanMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GoalForecastService forecasts;

    // ---------- v0.5 FR-82 · FIRE 月支出自动派生 ----------

    /**
     * 周期关闭时调用 · 对 AUTO_MONTHLY 模式的 RETIREMENT 目标,按近 N 月真实月结支出
     * 滚动派生 monthly_expense 并回写 + 重算 targetValue。
     *
     * <p>红线:只取真实填报的周期(findFamilyAggregateRecent HAVING filledMembers>0 已排除空期);
     * 数据不足 → 保留原 monthly_expense(不动)。失败不抛(不阻塞周期关闭)。</p>
     */
    @Transactional
    public void recomputeAutoExpenseGoals(long familyId) {
        List<Goal> goals;
        try {
            goals = goalMapper.findActiveByFamilyAndType(familyId, GoalType.RETIREMENT);
        } catch (Exception e) {
            return;
        }
        for (Goal g : goals) {
            try {
                if (retirementPlanMapper != null && retirementPlanMapper.find(familyId, g.getId())
                        .filter(com.family.finance.repository.RetirementPlanMapper.Row::active).isPresent()) continue;
                GoalParams p = parseParams(g);
                if (!"AUTO_MONTHLY".equalsIgnoreCase(p.getExpenseMode())) continue;
                int window = p.getExpenseWindowMonths() != null && p.getExpenseWindowMonths() > 0
                        ? p.getExpenseWindowMonths() : 12;
                // v1.8 · FIRE「自适应近月真实支出」走统一口径(逐笔 > 总额)
                List<BigDecimal> expenses = expenseLedger.recent(familyId, window).stream()
                        .map(com.family.finance.service.expense.ExpenseLedgerService.PeriodExpense::amountBase)
                        .filter(java.util.Objects::nonNull)
                        .filter(v -> v.signum() > 0)
                        .toList();
                if (expenses.isEmpty()) continue; // 数据不足 → 保留原值
                BigDecimal derived = smooth(expenses, p.getExpenseSmoothing());
                p.setMonthlyExpense(derived.setScale(2, java.math.RoundingMode.HALF_EVEN));
                p.setExpenseComputedAt(java.time.YearMonth.now().toString());
                g.setParamsJson(serialize(p));
                g.setTargetValue(com.family.finance.calc.GoalProgressCalculator.computeRetirementTarget(p));
                goalMapper.update(g);
            } catch (Exception ignored) {
                // 单个目标失败不影响其它
            }
        }
    }

    /** 月支出平滑:TRIMMED(剔头尾各 1·默认)/ MEDIAN / MEAN。 */
    private BigDecimal smooth(List<BigDecimal> values, String mode) {
        List<BigDecimal> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if ("MEAN".equalsIgnoreCase(mode)) {
            return mean(sorted);
        }
        if ("MEDIAN".equalsIgnoreCase(mode)) {
            return n % 2 == 1 ? sorted.get(n / 2)
                    : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_EVEN);
        }
        // TRIMMED(默认):≥3 项时剔头尾各 1,再均值;否则退回均值
        if (n >= 3) {
            return mean(sorted.subList(1, n - 1));
        }
        return mean(sorted);
    }

    private BigDecimal mean(List<BigDecimal> vs) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : vs) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(vs.size()), 2, java.math.RoundingMode.HALF_EVEN);
    }

    // ---------- 查询 ----------

    public List<Goal> findActiveByFamily(long familyId) {
        return goalMapper.findActiveByFamily(familyId);
    }

    public Goal require(long familyId, long goalId) {
        Goal goal = goalMapper.findById(familyId, goalId)
            .orElseThrow(() -> new IllegalArgumentException("目标不存在: " + goalId));
        if (!goal.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问该目标: " + goalId);
        }
        return goal;
    }

    public GoalParams parseParams(Goal goal) {
        try {
            return objectMapper.readValue(goal.getParamsJson(), GoalParams.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("目标参数解析失败 · goalId=" + goal.getId(), e);
        }
    }

    // ---------- 创建 ----------

    @Transactional
    public Goal create(long familyId, GoalType type, String name, GoalParams params) {
        return create(familyId,type,name,params,null);
    }

    @Transactional
    public Goal create(long familyId, GoalType type, String name, GoalParams params, GoalForecastForm estimate) {
        validateForecast(estimate);
        validateParams(type, params);
        String paramsJson = serialize(params);
        BigDecimal targetValue;
        LocalDate targetDate;
        switch (type) {
            case RETIREMENT -> {
                targetValue = com.family.finance.calc.GoalProgressCalculator.computeRetirementTarget(params);
                int years = params.getRetireAge() - params.getCurrentAge();
                targetDate = LocalDate.now().plusYears(Math.max(0, years));
            }
            case EDUCATION -> {
                targetValue = com.family.finance.calc.GoalProgressCalculator.computeEducationTarget(params);
                int offset = params.getTargetYearOffset() == null ? 18 : params.getTargetYearOffset();
                targetDate = LocalDate.of(params.getChildBirthYear() + offset, 9, 1);
            }
            case EMERGENCY -> {
                // EMERGENCY 由 caller 在 Controller 算 PV 时 derived · 不存 target_value
                targetValue = null;
                targetDate = null;
            }
            default -> throw new IllegalStateException("unknown type: " + type);
        }
        Goal goal = Goal.builder()
            .familyId(familyId)
            .goalType(type)
            .name(name == null || name.isBlank() ? defaultName(type) : name.trim())
            .targetValue(targetValue)
            .targetDate(targetDate)
            // v0.16 · 预设三类的通用字段:退休/教育=全家净资产、应急=全家现金;长期 · GTE(NOT NULL 列必须给值)
            .metric(type == GoalType.EMERGENCY ? GoalMetric.CASH_TOTAL : GoalMetric.AMOUNT_TOTAL)
            .comparator(GoalComparator.GTE)
            .timeMode(TimeMode.OPEN)
            .paramsJson(paramsJson)
            .build();
        goalMapper.insert(goal);
        if(estimate!=null && estimate.getEstimateSource()!=null)forecasts.save(familyId,goal.getId(),estimate);
        return goal;
    }

    // ---------- 更新 ----------

    @Transactional
    public Goal update(long familyId, long goalId, String name, GoalParams params) {
        Goal goal = require(familyId, goalId);
        if (retirementPlanMapper != null && retirementPlanMapper.find(familyId, goalId)
                .filter(com.family.finance.repository.RetirementPlanMapper.Row::active).isPresent())
            throw new IllegalArgumentException("此目标已采用退休生活账,请从调整计划修改");
        validateParams(goal.getGoalType(), params);
        goal.setName(name == null || name.isBlank() ? defaultName(goal.getGoalType()) : name.trim());
        goal.setParamsJson(serialize(params));
        // 重算 target_value / target_date
        switch (goal.getGoalType()) {
            case RETIREMENT -> {
                goal.setTargetValue(com.family.finance.calc.GoalProgressCalculator.computeRetirementTarget(params));
                int years = params.getRetireAge() - params.getCurrentAge();
                goal.setTargetDate(LocalDate.now().plusYears(Math.max(0, years)));
            }
            case EDUCATION -> {
                goal.setTargetValue(com.family.finance.calc.GoalProgressCalculator.computeEducationTarget(params));
                int offset = params.getTargetYearOffset() == null ? 18 : params.getTargetYearOffset();
                goal.setTargetDate(LocalDate.of(params.getChildBirthYear() + offset, 9, 1));
            }
            case EMERGENCY -> {
                goal.setTargetValue(null);
                goal.setTargetDate(null);
            }
        }
        goalMapper.update(goal);
        return goal;
    }

    @Transactional
    public void archive(long familyId, long goalId) {
        require(familyId, goalId); // 权限校验
        goalMapper.archive(familyId, goalId);
    }

    @Transactional
    public void restore(long familyId, long goalId) {
        goalMapper.restore(familyId, goalId);
    }

    // ---------- v0.16 · 自定义追踪目标(CUSTOM)----------

    @Transactional
    public Goal createCustom(long familyId, String name, String description, GoalMetric metric, GoalComparator cmp,
                             BigDecimal targetValue, TimeMode timeMode, LocalDate targetDate, List<Long> accountIds) {
        return createCustom(familyId,name,description,metric,cmp,targetValue,timeMode,targetDate,accountIds,null);
    }

    @Transactional
    public Goal createCustom(long familyId, String name, String description, GoalMetric metric, GoalComparator cmp,
                             BigDecimal targetValue, TimeMode timeMode, LocalDate targetDate, List<Long> accountIds,
                             GoalForecastForm estimate) {
        validateForecast(estimate);
        Goal goal = Goal.builder()
            .familyId(familyId)
            .goalType(GoalType.CUSTOM)
            .name(name == null || name.isBlank() ? defaultName(GoalType.CUSTOM) : name.trim())
            .description(description == null || description.isBlank() ? null : description.trim())
            .metric(metric == null ? GoalMetric.AMOUNT_TOTAL : metric)
            .comparator(cmp == null ? GoalComparator.GTE : cmp)
            .targetValue(targetValue)
            .timeMode(timeMode == null ? TimeMode.OPEN : timeMode)
            .targetDate(timeMode == TimeMode.DEADLINE ? targetDate : null)
            .paramsJson("{}")
            .build();
        goalMapper.insert(goal);
        rebindAccounts(familyId, goal.getId(), accountIds);
        if(estimate!=null && estimate.getEstimateSource()!=null) {
            if(!GoalForecastService.supports(goal,null))throw new IllegalArgumentException("此目标不支持按结余估算时间");
            forecasts.save(familyId,goal.getId(),estimate);
        }
        return goal;
    }

    private void validateForecast(GoalForecastForm estimate) {
        if(estimate!=null && estimate.getEstimateSource()!=null && !estimate.errors().isEmpty())
            throw new IllegalArgumentException(estimate.errors().values().iterator().next());
    }

    @Transactional
    public void updateCustom(long familyId, long goalId, String name, String description, GoalMetric metric, GoalComparator cmp,
                             BigDecimal targetValue, TimeMode timeMode, LocalDate targetDate, List<Long> accountIds) {
        Goal goal = require(familyId, goalId);
        goal.setName(name == null || name.isBlank() ? defaultName(GoalType.CUSTOM) : name.trim());
        goal.setDescription(description == null || description.isBlank() ? null : description.trim());
        goal.setMetric(metric == null ? GoalMetric.AMOUNT_TOTAL : metric);
        goal.setComparator(cmp == null ? GoalComparator.GTE : cmp);
        goal.setTargetValue(targetValue);
        goal.setTimeMode(timeMode == null ? TimeMode.OPEN : timeMode);
        goal.setTargetDate(timeMode == TimeMode.DEADLINE ? targetDate : null);
        if (goal.getParamsJson() == null || goal.getParamsJson().isBlank()) goal.setParamsJson("{}");
        goalMapper.update(goal);
        rebindAccounts(familyId, goalId, accountIds);
    }

    /** 清并重绑账户(0..N;去重、跳过 null)。 */
    private void rebindAccounts(long familyId, long goalId, List<Long> accountIds) {
        goalAccountMapper.clear(familyId, goalId);
        if (accountIds == null) return;
        for (Long a : accountIds) if (a != null) goalAccountMapper.bind(familyId, goalId, a);
    }

    /** 目标已绑定账户 id(供编辑回显)。 */
    public List<Long> boundAccountIds(long familyId, long goalId) {
        return goalAccountMapper.findAccountIds(familyId, goalId);
    }

    // ---------- 参数校验 ----------

    private static final Set<Integer> RETIRE_AGE_OK = null; // 用范围而非枚举
    private static final BigDecimal INFLATION_MIN = new BigDecimal("0");
    private static final BigDecimal INFLATION_MAX = new BigDecimal("0.10");
    private static final BigDecimal WITHDRAWAL_MIN = new BigDecimal("0.02");
    private static final BigDecimal WITHDRAWAL_MAX = new BigDecimal("0.06");

    public void validateParams(GoalType type, GoalParams p) {
        if (p == null) throw new IllegalArgumentException("参数不能为空");
        switch (type) {
            case RETIREMENT -> {
                requirePositiveInt(p.getCurrentAge(), 18, 80, "current_age");
                requirePositiveInt(p.getRetireAge(), 18, 90, "retire_age");
                if (p.getRetireAge() <= p.getCurrentAge()) {
                    throw new IllegalArgumentException("retire_age 必须 > current_age");
                }
                requirePositiveDecimal(p.getMonthlyExpense(),
                    new BigDecimal("1000"), new BigDecimal("1000000"), "monthly_expense");
                if (p.getInflationRate() != null) requireRange(p.getInflationRate(), INFLATION_MIN, INFLATION_MAX, "inflation_rate");
                if (p.getWithdrawalRate() != null) requireRange(p.getWithdrawalRate(), WITHDRAWAL_MIN, WITHDRAWAL_MAX, "withdrawal_rate");
            }
            case EDUCATION -> {
                if (p.getChildMemberId() == null) throw new IllegalArgumentException("缺少 child_member_id");
                int currentYear = Year.now().getValue();
                requirePositiveInt(p.getChildBirthYear(), 1980, currentYear, "child_birth_year");
                int offset = p.getTargetYearOffset() == null ? 18 : p.getTargetYearOffset();
                if (offset < 16 || offset > 30) {
                    throw new IllegalArgumentException("target_year_offset 范围 16-30");
                }
                requirePositiveDecimal(p.getTargetAmount(),
                    new BigDecimal("10000"), new BigDecimal("10000000"), "target_amount");
                if (p.getInflationRate() != null) requireRange(p.getInflationRate(), INFLATION_MIN, INFLATION_MAX, "inflation_rate");
            }
            case EMERGENCY -> {
                int months = p.getMonthsTarget() == null ? 6 : p.getMonthsTarget();
                if (months < 1 || months > 24) {
                    throw new IllegalArgumentException("months_target 范围 1-24");
                }
                if (Boolean.FALSE.equals(p.getAutoBaseline())) {
                    requirePositiveDecimal(p.getFixedBaseline(),
                        new BigDecimal("100"), new BigDecimal("1000000"), "fixed_baseline");
                }
            }
        }
    }

    private static void requirePositiveInt(Integer v, int min, int max, String name) {
        if (v == null) throw new IllegalArgumentException("缺少 " + name);
        if (v < min || v > max) throw new IllegalArgumentException(name + " 范围 " + min + "-" + max);
    }

    private static void requirePositiveDecimal(BigDecimal v, BigDecimal min, BigDecimal max, String name) {
        if (v == null) throw new IllegalArgumentException("缺少 " + name);
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " 范围 " + min + "-" + max);
        }
    }

    private static void requireRange(BigDecimal v, BigDecimal min, BigDecimal max, String name) {
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " 范围 " + min + "-" + max);
        }
    }

    private String serialize(GoalParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("目标参数序列化失败", e);
        }
    }

    private static String defaultName(GoalType type) {
        return switch (type) {
            case RETIREMENT -> "退休 / 自由生活";
            case EDUCATION -> "子女教育金";
            case EMERGENCY -> "应急储备";
            case CUSTOM -> "自定义追踪目标";
        };
    }
}
