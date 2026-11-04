package com.family.finance.service.goal;

import com.family.finance.calc.RetirementCalculator;
import com.family.finance.domain.goal.*;
import com.family.finance.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RetirementPlanService {
    private final RetirementPlanMapper mapper;
    private final GoalMapper goals;
    private final FamilyMapper families;
    private final GoalService goalService;
    private final RetirementDataService dataService;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.AuditLogService audit;

    public record View(RetirementPlan plan, RetirementCalculator.Result result, RetirementDataService.Data data,
                       LocalDateTime updatedAt, boolean createdForPlan, boolean active, String fingerprint) { }

    public Optional<View> active(long familyId, Goal goal) {
        if (goal.getGoalType() != GoalType.RETIREMENT) return Optional.empty();
        return rowForRequest(familyId, goal.getId()).filter(RetirementPlanMapper.Row::active)
                .map(row -> evaluate(familyId, parse(row.planJson()), row.updatedAt(), row.createdForPlan(), true));
    }

    @SuppressWarnings("unchecked")
    private Optional<RetirementPlanMapper.Row> rowForRequest(long familyId, long goalId) {
        var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (request == null) return mapper.find(familyId, goalId);
        String key = RetirementPlanService.class.getName() + ".plans." + familyId;
        var rows = (Map<Long, RetirementPlanMapper.Row>) request.getAttribute(key, 0);
        if (rows == null) {
            rows = mapper.findAll(familyId).stream().collect(java.util.stream.Collectors.toMap(RetirementPlanMapper.Row::goalId, r -> r));
            request.setAttribute(key, rows, 0);
        }
        return Optional.ofNullable(rows.get(goalId));
    }

    public View evaluate(long familyId, RetirementPlan p, LocalDateTime updatedAt, boolean created, boolean active) {
        LocalDate today = LocalDate.now();
        var data = dataService.load(familyId, p, today);
        List<String> problems = new ArrayList<>(data.problems());
        if (p.isAutoExpense() && data.suggestedExpense() == null) problems.add("账上预算建议不足3期或包含需核对的退款/总额月份,可改为手填预算");
        var result = RetirementCalculator.calculate(p, today, data.selectedAssets(), data.debt(), data.suggestedExpense(), problems);
        String fingerprint = com.family.finance.service.llmtrace.PromptRecorder.sha256(encode(p) + "|" + result.aiSummary());
        return new View(p, result, data, updatedAt, created, active, fingerprint);
    }

    public RetirementPlan draft(long familyId, Goal goal) {
        if (goal != null) {
            var row = mapper.find(familyId, goal.getId());
            if (row.isPresent()) return rebase(parse(row.get().planJson()), LocalDate.now());
        }
        var p = new RetirementPlan();
        p.setBaseCurrency(families.findById(familyId).orElseThrow().getBaseCurrency());
        p.setPriceDate(LocalDate.now());
        p.setTargetMonth(YearMonth.now().plusYears(10).toString());
        if (goal != null) {
            if (goal.getGoalType() != GoalType.RETIREMENT) throw new IllegalArgumentException("只有退休目标可采用此方案");
            var old = goalService.parseParams(goal);
            p.setName(goal.getName()); p.setMonthlyExpense(old.getMonthlyExpense());
            if (old.getInflationRate() != null) p.setInflationPct(old.getInflationRate().movePointRight(2));
            if (goal.getTargetDate() != null) p.setTargetMonth(YearMonth.from(goal.getTargetDate()).toString());
        }
        var data = dataService.load(familyId, p, LocalDate.now());
        p.setAccountIds(new LinkedHashSet<>(data.assets().stream().filter(a -> a.eligible() && !a.liability()).map(RetirementDataService.Asset::id).toList()));
        if (p.getMonthlyExpense() == null) p.setMonthlyExpense(data.suggestedExpense());
        p.setMonthlyContribution(data.suggestedContribution());
        return p;
    }

    /** 编辑时金额先按原计划的通胀换到今天;保存时不会因改通胀再换一次。 */
    public RetirementPlan rebase(RetirementPlan p, LocalDate today) {
        if (p.getPriceDate() == null) { p.setPriceDate(today); return p; }
        var factor = RetirementCalculator.priceFactor(p.getPriceDate(), today, RetirementCalculator.fraction(p.getInflationPct()));
        p.setMonthlyExpense(scale(p.getMonthlyExpense(), factor)); p.setMonthlyIncome(scale(p.getMonthlyIncome(), factor));
        p.setChildReserve(scale(p.getChildReserve(), factor)); p.setMedicalReserve(scale(p.getMedicalReserve(), factor));
        p.setHousingReserve(scale(p.getHousingReserve(), factor)); p.setOtherReserve(scale(p.getOtherReserve(), factor));
        if (p.isContributionFollowsInflation()) p.setMonthlyContribution(scale(p.getMonthlyContribution(), factor));
        p.getRealizableAmounts().replaceAll((k,v) -> scale(v, factor));
        p.setPriceDate(today);
        return p;
    }

    @Transactional
    public Goal save(long familyId, Long goalId, RetirementPlan p, boolean adoptionConfirmed) {
        return save(familyId, goalId, p, adoptionConfirmed, null);
    }

    @Transactional
    public Goal save(long familyId, Long goalId, RetirementPlan p, boolean adoptionConfirmed, Long actorId) {
        Goal goal = goalId == null ? null : goalService.require(familyId, goalId);
        if (goal != null && goal.getArchivedAt() != null) throw new IllegalArgumentException("请先恢复目标再修改计划");
        var existing = goal == null ? Optional.<RetirementPlanMapper.Row>empty() : mapper.find(familyId, goalId);
        if (goal != null && existing.filter(RetirementPlanMapper.Row::active).isEmpty() && !adoptionConfirmed)
            throw new IllegalArgumentException("请先比较旧新算法,并确认采用新方案");
        validate(p);
        p.setPriceDate(LocalDate.now());
        var view = evaluate(familyId, p, null, goal == null, true);
        if (!Objects.equals(p.getBaseCurrency(), view.data().baseCurrency())) throw new IllegalArgumentException("计划币种与家庭本位币不一致");
        Set<Long> valid = new HashSet<>(view.data().assets().stream().filter(a -> !a.liability()).map(RetirementDataService.Asset::id).toList());
        if (!valid.containsAll(p.getAccountIds())) throw new IllegalArgumentException("资金范围中含失效或不属于本家庭的账户,请重新选择");
        if (p.isAutoExpense() && view.data().suggestedExpense() == null) throw new IllegalArgumentException("自动预算的数据不足或需要人工核对,请先手填预算");
        if (view.data().debt().signum() > 0 && !p.isAfterDebtConfirmed()) throw new IllegalArgumentException("请确认偿清负债后的计划月投入");
        if (p.getEducationGoalId() != null) {
            var education = goalService.require(familyId, p.getEducationGoalId());
            if (education.getGoalType() != GoalType.EDUCATION || education.getArchivedAt() != null || !p.isEducationConfirmed())
                throw new IllegalArgumentException("教育来源须为本家庭有效教育目标,并确认剩余金额与币值年;不能以目标进度抵扣专款");
        }
        p.setBudgetFingerprint(view.data().budgetFingerprint());
        if (goal == null) {
            goal = Goal.builder().familyId(familyId).goalType(GoalType.RETIREMENT).name(p.getName().trim())
                    .metric(GoalMetric.AMOUNT_TOTAL).comparator(GoalComparator.GTE).timeMode(TimeMode.OPEN)
                    .targetValue(view.result().target()).targetDate(view.result().retirementDate()).paramsJson("{}")
                    .description("退休生活账 · 当前计划 · v1").build();
            goals.insert(goal);
        }
        int written = mapper.save(familyId, goal.getId(), encode(p), true, goalId == null);
        if (written == 0 && mapper.find(familyId, goal.getId()).isEmpty()) throw new IllegalArgumentException("退休计划写入失败");
        if (audit != null && actorId != null) audit.record(familyId, actorId, com.family.finance.domain.audit.AuditLogType.SYSTEM,
                "family_goal", goal.getId(), "保存退休生活账 v1 · " + (goalId == null ? "新建" : "调整/采用") + " · 不修改旧算法参数");
        return goal;
    }

    @Transactional
    public void revert(long familyId, long goalId) {
        revert(familyId, goalId, null);
    }

    @Transactional
    public void revert(long familyId, long goalId, Long actorId) {
        goalService.require(familyId, goalId);
        var row = mapper.find(familyId, goalId).orElseThrow(() -> new IllegalArgumentException("没有新方案"));
        if (row.createdForPlan()) throw new IllegalArgumentException("此目标没有旧算法快照,可编辑或归档");
        mapper.deactivate(familyId, goalId);
        if (audit != null && actorId != null) audit.record(familyId, actorId, com.family.finance.domain.audit.AuditLogType.SYSTEM,
                "family_goal", goalId, "退出退休生活账 · 恢复原算法参数");
    }

    public void validate(RetirementPlan p) {
        if (p.getVersion() != 1) throw new IllegalArgumentException("不支持的退休计划版本");
        if (p.getName() == null || p.getName().isBlank() || p.getName().length() > 64) throw new IllegalArgumentException("目标名称须为1–64字");
        nonnegative(p.getMonthlyExpense(), "生活预算"); nonnegative(p.getMonthlyIncome(), "退休月收入");
        nonnegative(p.getChildReserve(), "育儿预留"); nonnegative(p.getMedicalReserve(), "医疗预留");
        nonnegative(p.getHousingReserve(), "住房预留"); nonnegative(p.getOtherReserve(), "应急及其他预留");
        range(p.getMonthlyContribution(), new BigDecimal("-1000000000"), new BigDecimal("1000000000"), "计划月投入");
        cents(p.getMonthlyContribution(), "计划月投入");
        range(p.getInflationPct(), BigDecimal.ZERO, new BigDecimal("15"), "通胀率");
        for (BigDecimal rate : Arrays.asList(p.getNominalReturnPct(), p.getRealReturnPct(), p.getLowReturnPct(), p.getMiddleReturnPct(), p.getHighReturnPct()))
            range(rate, new BigDecimal("-99"), new BigDecimal("100"), "回报率");
        if (p.getLowReturnPct().compareTo(p.getMiddleReturnPct()) > 0 || p.getMiddleReturnPct().compareTo(p.getHighReturnPct()) > 0)
            throw new IllegalArgumentException("积累期回报应满足低≤中≤高");
        try {
            long months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.parse(p.getTargetMonth()));
            if (months > 600) throw new IllegalArgumentException("目标年月须在未来50年内");
        } catch (java.time.format.DateTimeParseException | NullPointerException e) { throw new IllegalArgumentException("请填写有效退休年月"); }
        if (!(p.isBudgetConfirmed() && p.isReservesConfirmed() && p.isNoOverlapConfirmed() && p.isAssetsConfirmed() && p.isIncomeConfirmed() && p.isContributionConfirmed()))
            throw new IllegalArgumentException("请逐项确认预算、预留归属、资金范围、退休收入和月投入");
        if (p.getAccountIds() == null || p.getExcludedCategoryIds() == null || p.getRealizableAmounts() == null)
            throw new IllegalArgumentException("账户和分类选择无效");
        p.getRealizableAmounts().forEach((id, v) -> { if (p.getAccountIds().contains(id) && v != null) nonnegative(v, "可变现金额"); });
    }
    private void nonnegative(BigDecimal value, String name) {
        range(value, BigDecimal.ZERO, new BigDecimal("1000000000000"), name); cents(value, name);
    }
    private void cents(BigDecimal value, String name) {
        if (value.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException(name + "最多保留到分");
    }
    private void range(BigDecimal value, BigDecimal min, BigDecimal max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0 || value.scale() > 12)
            throw new IllegalArgumentException(name + "未填或超出允许范围");
    }
    private BigDecimal scale(BigDecimal value, BigDecimal factor) { return value == null ? null : value.multiply(factor).setScale(2, java.math.RoundingMode.HALF_EVEN); }
    public RetirementPlan parse(String raw) {
        try { return json.readValue(raw, RetirementPlan.class); }
        catch (Exception e) { throw new IllegalStateException("退休计划读取失败"); }
    }
    public String encode(RetirementPlan p) {
        try { return json.writeValueAsString(p); }
        catch (Exception e) { throw new IllegalArgumentException("退休计划保存失败"); }
    }
}
