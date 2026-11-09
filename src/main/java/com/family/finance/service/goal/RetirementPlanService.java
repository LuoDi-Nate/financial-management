package com.family.finance.service.goal;

import com.family.finance.calc.RetirementCalculator;
import com.family.finance.calc.RetirementReservePresets;
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
                       LocalDateTime updatedAt, boolean createdForPlan, boolean active, String fingerprint,
                       RetirementPlan effective, List<String> configurationProblems) {
        public boolean configurationComplete() { return configurationProblems.isEmpty(); }
        public BigDecimal progressAssets() { return plan.getVersion()>=2 ? result.assets() : result.principal(); }
        public BigDecimal progressTarget() { return plan.getVersion()>=2 ? result.totalAssetNeed() : result.target(); }
        public List<String> dataReferences() {
            var notes=new LinkedHashSet<String>(result.problems());
            if(plan.isAutoExpense() || (plan.isSimulateFuture() && "AUTO".equals(plan.getContributionSource()))) notes.addAll(data.budgetNotes());
            data.assets().stream().filter(a -> (a.selected() || a.liability()) && a.note().contains("沿用最近已知余额"))
                    .map(a -> a.name()+":"+a.note()).forEach(notes::add);
            notes.removeAll(configurationProblems);
            return List.copyOf(notes);
        }
    }

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
        List<String> configurationProblems = new ArrayList<>();
        try { validate(p); } catch(IllegalArgumentException e) { configurationProblems.add(e.getMessage()); }
        boolean currencyMatches = Objects.equals(p.getBaseCurrency(),data.baseCurrency());
        if(!currencyMatches) configurationProblems.add("家庭本位币已改变,请重新核对计划金额的币种");
        Set<Long> validAccounts=data.assets().stream().filter(a -> !a.liability()).map(RetirementDataService.Asset::id).collect(java.util.stream.Collectors.toSet());
        boolean accountsValid=p.getVersion()>=2 && "FAMILY_FINANCIAL".equals(p.getScopeMode()) || validAccounts.containsAll(p.getAccountIds());
        if(!accountsValid) configurationProblems.add("资金范围中含失效或不属于本家庭的账户,请重新选择");
        List<String> problems = new ArrayList<>(data.problems());
        if (p.isAutoExpense() && data.suggestedExpense() == null) problems.add("自动生活费需至少3个可拆分的完整月份,可改为手填");
        RetirementPlan effective = p.getVersion() >= 2 ? effective(p,data,today,problems) : p;
        BigDecimal assets=p.getVersion()>=2 ? data.completeAssets() : data.problems().isEmpty()?data.selectedAssets():null;
        BigDecimal debt=p.getVersion()>=2 ? data.completeDebt() : data.problems().isEmpty()?data.debt():null;
        var result = RetirementCalculator.calculate(effective, today, currencyMatches && accountsValid ? assets : null,
                currencyMatches ? debt : null, data.suggestedExpense(), problems);
        String fingerprint = com.family.finance.service.llmtrace.PromptRecorder.sha256(encode(p) + "|" + data.sources() + "|" + data.assets() + "|" + result.aiSummary());
        return new View(p, result, data, updatedAt, created, active, fingerprint,effective,List.copyOf(configurationProblems));
    }

    private RetirementPlan effective(RetirementPlan stored, RetirementDataService.Data data, LocalDate today, List<String> problems) {
        RetirementPlan p = rebase(parse(encode(stored)),today);
        var sources = data.sources();
        if ("AUTO".equals(p.getInflationSource())) {
            p.setInflationPct(sources.inflation().percent());
            if (p.getInflationPct()==null) problems.add(sources.inflation().problem());
        }
        if ("AUTO".equals(p.getReturnSource())) {
            p.setDirectRealRate(false);
            p.setNominalReturnPct(sources.investment().percent());
            if(p.getNominalReturnPct()==null) problems.add(sources.investment().problem());
        }
        p.setMonthlyIncome(BigDecimal.ZERO);
        if ("CITY".equals(p.getReserveMode())) {
            try {
                    var preset = RetirementReservePresets.require(p.getCityTier());
                    boolean childrenKnown = p.getDependentCount()!=null && p.getDependentCount()>=0 && p.getDependentCount()<=8
                            && p.getChildPlanningYears()!=null && p.getChildPlanningYears().size()==p.getDependentCount()
                            && p.getChildPlanningYears().stream().allMatch(y->y!=null && y>=0 && y<=30);
                    var factor = RetirementCalculator.priceFactor(RetirementReservePresets.PRICE_DATE,today,
                            RetirementCalculator.fraction(p.getPriceInflationPct()==null?BigDecimal.ZERO:p.getPriceInflationPct()));
                    var amounts=RetirementReservePresets.calculate(preset,childrenKnown?p.getChildPlanningYears():List.of(),
                            p.isAutoExpense()?data.suggestedExpense():p.getMonthlyExpense(),sources.cnyToBase(),factor);
                    p.setChildReserve(childrenKnown?amounts.education():null);
                    p.setMedicalReserve(amounts.medical());p.setOtherReserve(amounts.emergency());
                    if(!childrenKnown) problems.add("请选择子女人数及每位子女的剩余规划年数");
                    if(Arrays.asList("SETTLED","RENT").contains(p.getHousingPlan())) p.setHousingReserve(BigDecimal.ZERO);
                    else if(!"CHANGE".equals(p.getHousingPlan())) { p.setHousingReserve(null);problems.add("请选择住房安排"); }
                    if(sources.cnyToBase()==null) problems.add("城市推荐值以人民币定价,当前缺少换算至家庭本位币的汇率");
            } catch(IllegalArgumentException e) {
                problems.add(e.getMessage());
                p.setChildReserve(null);p.setMedicalReserve(null);p.setOtherReserve(null);p.setHousingReserve(null);
            }
        }
        if("AUTO".equals(p.getContributionSource())) {
            p.setMonthlyContribution(data.suggestedContribution());
            if(p.isSimulateFuture() && p.getMonthlyContribution()==null) problems.add("月结余参考尚无完整收支数据,暂不预测达成时间");
        }
        if(!p.isAdvancedScenarios()) p.setMiddleReturnPct(p.isDirectRealRate()
                ? p.getRealReturnPct()==null || p.getInflationPct()==null ? null
                    : BigDecimal.ONE.add(p.getRealReturnPct().movePointLeft(2)).multiply(BigDecimal.ONE.add(p.getInflationPct().movePointLeft(2))).subtract(BigDecimal.ONE).movePointRight(2)
                : p.getNominalReturnPct());
        return p;
    }

    public Optional<RetirementPlan> stored(long familyId, long goalId) {
        return mapper.find(familyId,goalId).map(row->parse(row.planJson()));
    }

    public RetirementPlan draft(long familyId, Goal goal) {
        if (goal != null) {
            var row = mapper.find(familyId, goal.getId());
            if (row.isPresent()) {
                var p=rebase(parse(row.get().planJson()),LocalDate.now());
                if(p.getVersion()==1) {
                    p.setVersion(2);p.setMonthlyIncome(BigDecimal.ZERO);p.setSimulateFuture(false);
                    p.setReserveMode("CUSTOM");p.setScopeMode("CUSTOM");
                    p.setReturnSource("MANUAL");p.setInflationSource("MANUAL");
                }
                return p;
            }
        }
        var p = new RetirementPlan();
        p.setVersion(2);p.setScopeMode("FAMILY_FINANCIAL");p.setReserveMode("CITY");
        p.setReturnSource("AUTO");p.setInflationSource("AUTO");p.setContributionSource("AUTO");
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
        p.setAutoExpense(data.suggestedExpense()!=null);
        return p;
    }

    /** 编辑时金额先按原计划的通胀换到今天;保存时不会因改通胀再换一次。 */
    public RetirementPlan rebase(RetirementPlan p, LocalDate today) {
        if (p.getPriceDate() == null) { p.setPriceDate(today); return p; }
        BigDecimal priceRate=p.getVersion()>=2?p.getPriceInflationPct():p.getInflationPct();
        var factor = RetirementCalculator.priceFactor(p.getPriceDate(), today, RetirementCalculator.fraction(priceRate==null?BigDecimal.ZERO:priceRate));
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
        return save(familyId,goalId,p,adoptionConfirmed,false,actorId);
    }

    @Transactional
    public Goal save(long familyId, Long goalId, RetirementPlan p, boolean adoptionConfirmed, boolean revisionConfirmed, Long actorId) {
        Goal goal = goalId == null ? null : goalService.require(familyId, goalId);
        if (goal != null && goal.getArchivedAt() != null) throw new IllegalArgumentException("请先恢复目标再修改计划");
        var existing = goal == null ? Optional.<RetirementPlanMapper.Row>empty() : mapper.find(familyId, goalId);
        if (existing.isPresent() && !Objects.equals(parse(existing.get().planJson()).getBaseCurrency(), p.getBaseCurrency()))
            throw new IllegalArgumentException("家庭本位币已改变,不能将旧金额直接当作新币种;请按新币种新建退休计划,此目标保留作历史参考");
        if (goal != null && existing.filter(RetirementPlanMapper.Row::active).isEmpty() && !adoptionConfirmed)
            throw new IllegalArgumentException("请先比较旧新算法,并确认采用新方案");
        if(p.getVersion()>=2 && existing.isPresent()) {
            var previous=parse(existing.get().planJson());
            if(previous.getVersion()==1) {
                if(!revisionConfirmed) throw new IllegalArgumentException("请确认升级修订二:原持续收入不再抵扣生活费;旧方案会完整保留");
                p.setPreviousRevisionJson(existing.get().planJson());
            } else p.setPreviousRevisionJson(previous.getPreviousRevisionJson());
        } else p.setPreviousRevisionJson(null);
        validate(p);
        p.setPriceDate(LocalDate.now());
        var view = evaluate(familyId, p, null, goal == null, true);
        if (!Objects.equals(p.getBaseCurrency(), view.data().baseCurrency())) throw new IllegalArgumentException("计划币种与家庭本位币不一致");
        Set<Long> valid = new HashSet<>(view.data().assets().stream().filter(a -> !a.liability()).map(RetirementDataService.Asset::id).toList());
        if ((p.getVersion()==1 || "CUSTOM".equals(p.getScopeMode())) && !valid.containsAll(p.getAccountIds())) throw new IllegalArgumentException("资金范围中含失效或不属于本家庭的账户,请重新选择");
        if (p.getVersion()==1 && p.isAutoExpense() && view.data().suggestedExpense() == null) throw new IllegalArgumentException("自动预算的数据不足或需要人工核对,请先手填预算");
        if (p.getVersion()==1 && view.data().debt().signum() > 0 && !p.isAfterDebtConfirmed()) throw new IllegalArgumentException("请确认偿清负债后的计划月投入");
        if(p.getVersion()>=2) {
            // Persist configuration even when live balances, budget or rate sources are incomplete.
            if(view.effective().getInflationPct()!=null) p.setPriceInflationPct(view.effective().getInflationPct());
        }
        if (p.getEducationGoalId() != null && (p.getVersion()==1 || "CUSTOM".equals(p.getReserveMode()))) {
            var education = goalService.require(familyId, p.getEducationGoalId());
            if (education.getGoalType() != GoalType.EDUCATION || education.getArchivedAt() != null || !p.isEducationConfirmed())
                throw new IllegalArgumentException("教育来源须为本家庭有效教育目标,并确认剩余金额与币值年;不能以目标进度抵扣专款");
        }
        p.setBudgetFingerprint(view.data().budgetFingerprint());
        if (goal == null) {
            goal = Goal.builder().familyId(familyId).goalType(GoalType.RETIREMENT).name(p.getName().trim())
                    .metric(GoalMetric.AMOUNT_TOTAL).comparator(GoalComparator.GTE).timeMode(TimeMode.OPEN)
                    .targetValue(view.progressTarget()).targetDate(view.result().retirementDate()).paramsJson("{}")
                    .description("退休生活账 · 当前计划 · 修订"+p.getVersion()).build();
            goals.insert(goal);
        }
        int written = mapper.save(familyId, goal.getId(), encode(p), true, goalId == null);
        if (written == 0 && mapper.find(familyId, goal.getId()).isEmpty()) throw new IllegalArgumentException("退休计划写入失败");
        if (audit != null && actorId != null) audit.record(familyId, actorId, com.family.finance.domain.audit.AuditLogType.SYSTEM,
                "family_goal", goal.getId(), "保存退休生活账 修订"+p.getVersion()+" · " + (goalId == null ? "新建" : "调整/采用") + " · 不修改旧算法参数");
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

    @Transactional
    public void restorePrevious(long familyId,long goalId,Long actorId) {
        goalService.require(familyId,goalId);
        var row=mapper.find(familyId,goalId).filter(RetirementPlanMapper.Row::active).orElseThrow(()->new IllegalArgumentException("没有当前计划"));
        var current=parse(row.planJson());
        if(current.getPreviousRevisionJson()==null) throw new IllegalArgumentException("没有升级前方案");
        var previous=parse(current.getPreviousRevisionJson());
        mapper.save(familyId,goalId,encode(previous),true,row.createdForPlan());
        if(audit!=null && actorId!=null) audit.record(familyId,actorId,com.family.finance.domain.audit.AuditLogType.SYSTEM,
                "family_goal",goalId,"恢复退休升级前方案 · 未改动账上事实");
    }

    public void validate(RetirementPlan p) {
        if(p.getVersion()==2) { validateRevision2(p); return; }
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
    private void validateRevision2(RetirementPlan p) {
        if(p.getName()==null || p.getName().isBlank() || p.getName().length()>64) throw new IllegalArgumentException("目标名称须为1–64字");
        choice(p.getScopeMode(),"资金范围","FAMILY_FINANCIAL","CUSTOM");
        choice(p.getReturnSource(),"回报来源","AUTO","MANUAL");choice(p.getInflationSource(),"通胀来源","AUTO","MANUAL");
        choice(p.getReserveMode(),"预留来源","CITY","CUSTOM");choice(p.getContributionSource(),"结余来源","AUTO","MANUAL");
        if(!p.isAutoExpense()) nonnegative(p.getMonthlyExpense(),"每月生活费");
        if("MANUAL".equals(p.getInflationSource())) range(p.getInflationPct(),new BigDecimal("-20"),new BigDecimal("50"),"通胀率");
        if("MANUAL".equals(p.getReturnSource())) range(p.isDirectRealRate()?p.getRealReturnPct():p.getNominalReturnPct(),new BigDecimal("-99"),new BigDecimal("100"),"回报率");
        if("CITY".equals(p.getReserveMode())) {
            RetirementReservePresets.require(p.getCityTier());
            if(p.getDependentCount()==null || p.getDependentCount()<0 || p.getDependentCount()>8 || p.getChildPlanningYears()==null
                    || p.getChildPlanningYears().size()!=p.getDependentCount() || p.getChildPlanningYears().stream().anyMatch(y->y==null || y<0 || y>30))
                throw new IllegalArgumentException("请明确选择子女人数及每位子女的剩余规划年数(0–30年)");
            choice(p.getHousingPlan(),"住房安排","SETTLED","RENT","CHANGE");
            if("CHANGE".equals(p.getHousingPlan())) nonnegative(p.getHousingReserve(),"换房新增净支出");
        } else {
            nonnegative(p.getChildReserve(),"额外教育预留");nonnegative(p.getMedicalReserve(),"医疗预留");
            nonnegative(p.getHousingReserve(),"住房预留");nonnegative(p.getOtherReserve(),"应急及其他预留");
        }
        if(!p.isNoOverlapConfirmed()) throw new IllegalArgumentException("请确认生活费与额外预留不重复");
        if(p.getAccountIds()==null || p.getExcludedCategoryIds()==null || p.getRealizableAmounts()==null) throw new IllegalArgumentException("账户和分类选择无效");
        if(p.isSimulateFuture()) {
            if("MANUAL".equals(p.getContributionSource())) {range(p.getMonthlyContribution(),new BigDecimal("-1000000000"),new BigDecimal("1000000000"),"每月结余");cents(p.getMonthlyContribution(),"每月结余");}
            if(p.isAdvancedScenarios()) {
                for(var rate:Arrays.asList(p.getLowReturnPct(),p.getMiddleReturnPct(),p.getHighReturnPct())) range(rate,new BigDecimal("-99"),new BigDecimal("100"),"情景回报");
                if(p.getLowReturnPct().compareTo(p.getMiddleReturnPct())>0 || p.getMiddleReturnPct().compareTo(p.getHighReturnPct())>0) throw new IllegalArgumentException("情景回报应满足低≤中≤高");
            }
        }
        try { if(java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.now(),YearMonth.parse(p.getTargetMonth()))>600) throw new IllegalArgumentException("目标年月须在未来50年内"); }
        catch(java.time.format.DateTimeParseException | NullPointerException e) {throw new IllegalArgumentException("请填写有效退休年月");}
    }
    private void choice(String value,String name,String... options) {
        if(!Arrays.asList(options).contains(value)) throw new IllegalArgumentException("请选择"+name);
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
    public String chartJson(RetirementCalculator.Result r,BigDecimal factor,String currency) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("scenarios",r.scenarios());fields.put("target",r.target());fields.put("date",r.asOf().toString());
        fields.put("retirementDate",r.retirementDate()==null?"":r.retirementDate().toString());
        fields.put("neutralDate",r.neutralDate()==null?"":r.neutralDate().toString());fields.put("factor",factor);fields.put("currency",currency);
        try {return json.writeValueAsString(fields);} catch(Exception e) {throw new IllegalStateException("路径数据生成失败",e);}
    }
}
