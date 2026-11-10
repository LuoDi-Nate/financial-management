package com.family.finance.service.goal;

import com.family.finance.calc.GoalCashflowCalculator;
import com.family.finance.domain.goal.*;
import com.family.finance.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoalForecastService {
    private final MonthlyContributionService contributions;
    private final FamilyMapper families;
    private final GoalMapper goals;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.family.finance.service.AuditLogService audit;

    public record View(boolean supported, GoalCashflowCalculator.Result result, String currency, String source,
                       String sourceLabel, MonthlyContributionService.Window window, GoalForecastForm form,
                       Map<String,String> errors, List<GoalIndicator> indicators) {
        public String dateLabel() { return result == null ? "" : result.dateLabel(); }
        public String durationLabel() { return result == null ? "" : result.durationLabel(); }
        public String reason() { return result == null ? "" : result.reason(); }
        public String amount(BigDecimal value) { return value==null?"—":com.family.finance.service.MoneyFormat.format2(currency,value); }
    }
    public class Context {
        private final long familyId; private final LocalDate asOf;
        private String currency; private MonthlyContributionService.Window window;
        private boolean loaded; private boolean failed;
        private Context(long familyId, LocalDate asOf) { this.familyId=familyId; this.asOf=asOf; }
        public String currency() {
            if(currency==null) currency=families.findById(familyId).orElseThrow().getBaseCurrency(); return currency;
        }
        private MonthlyContributionService.Window window() {
            if(!loaded) { loaded=true; try { window=contributions.load(familyId,asOf); }
                catch(RuntimeException e) { failed=true; log.warn("goal forecast source unavailable family={} category={}",familyId,e.getClass().getSimpleName()); } }
            return window;
        }
    }
    public Context context(long familyId,LocalDate asOf) { return new Context(familyId,asOf); }
    public static boolean supports(Goal goal, RetirementPlanService.View retirement) {
        if(goal.getArchivedAt()!=null) return false;
        return switch(goal.getGoalType()) {
            case RETIREMENT -> retirement!=null && retirement.plan().getVersion()>=2;
            case EDUCATION, EMERGENCY -> true;
            case CUSTOM -> goal.comparatorOrDefault()==GoalComparator.GTE && Set.of(GoalMetric.AMOUNT_TOTAL,
                    GoalMetric.CASH_TOTAL,GoalMetric.NET_PRINCIPAL,GoalMetric.TOTAL_ASSETS).contains(goal.metricOrDefault());
        };
    }
    public GoalForecastForm form(Goal goal, RetirementPlanService.View retirement) {
        GoalForecastForm f=new GoalForecastForm();
        boolean legacyManual=retirement!=null && "MANUAL".equals(retirement.plan().getContributionSource());
        f.setEstimateSource(goal.getForecastSource()!=null?goal.getForecastSource():legacyManual?"MANUAL":"AUTO");
        BigDecimal amount=goal.getForecastManualAmount()!=null?goal.getForecastManualAmount():legacyManual?retirement.plan().getMonthlyContribution():null;
        f.setEstimateMonthlyAmount(amount==null?"":amount.toPlainString());
        String wish=Boolean.TRUE.equals(goal.getForecastWishOverride())?goal.getForecastWishMonth()
                :retirement!=null?retirement.plan().getTargetMonth():goal.getTargetDate()==null?null:YearMonth.from(goal.getTargetDate()).toString();
        f.setEstimateWishMonth(wish==null?"":wish); return f;
    }
    public View evaluate(Goal goal, BigDecimal assets, BigDecimal target, BigDecimal progress,
                         RetirementPlanService.View retirement, GoalForecastForm draft, Context context) {
        if(!supports(goal,retirement)) return new View(false,null,context.currency(),"AUTO","",null,new GoalForecastForm(),Map.of(),List.of());
        GoalForecastForm form=draft==null?form(goal,retirement):draft.copy();
        Map<String,String> errors=form.errors();
        String currency=context.currency(), source=form.getEstimateSource();
        String label="MANUAL".equals(source)?"本目标手填月可用结余":"家庭近6个已结束月净结余中位数";
        BigDecimal contribution=null; MonthlyContributionService.Window window=null;
        boolean currencyMismatch=false;
        if(errors.isEmpty() && "MANUAL".equals(source)) {
            contribution=form.amount();
            String manualCurrency=goal.getForecastSource()==null && retirement!=null?retirement.plan().getBaseCurrency():goal.getForecastManualCurrency();
            currencyMismatch=draft==null && manualCurrency!=null && !currency.equals(manualCurrency);
            if(currencyMismatch) contribution=null;
        }
        var basis=GoalCashflowCalculator.calculate(context.asOf,assets,target,contribution,errors.isEmpty()?form.wish():null);
        if(errors.isEmpty() && "AUTO".equals(source) && basis.state()!=GoalCashflowCalculator.State.ACHIEVED) {
            window=context.window(); contribution=window==null?null:window.median();
            basis=GoalCashflowCalculator.calculate(context.asOf,assets,target,contribution,form.wish());
        }
        if(currencyMismatch) basis=GoalCashflowCalculator.unavailable(basis,"手填结余的币种已改变,请按当前本位币重新核对");
        if(!errors.isEmpty()) basis=GoalCashflowCalculator.unavailable(basis,"请修正时间估算的输入");
        if(context.failed && "AUTO".equals(source)) basis=GoalCashflowCalculator.unavailable(basis,"月结余来源暂不可用,资金进度仍有效");
        var indicators=List.of(
            new GoalIndicator(GoalIndicator.Kind.FUNDING_PROGRESS,"资金完成度",new GoalIndicator.Ratio(progress),progress==null?"MISSING":"AVAILABLE","原目标口径"),
            new GoalIndicator(GoalIndicator.Kind.FUNDING_GAP,"资金缺口",new GoalIndicator.Amount(basis.gap(),currency),basis.gap()==null?"MISSING":"AVAILABLE","原目标资金"),
            new GoalIndicator(GoalIndicator.Kind.CASHFLOW_DATE,"按当前结余预计达成",new GoalIndicator.DateAndMonths(basis.date(),basis.months()),basis.state().name(),label),
            new GoalIndicator(GoalIndicator.Kind.DEADLINE_DELTA,"希望月份对照",new GoalIndicator.MonthDifference(basis.monthDelta()),basis.monthDelta()==null?"MISSING":"AVAILABLE","时间估算希望月份"));
        return new View(true,basis,currency,source,label,window,form,errors,indicators);
    }
    @Transactional
    public void save(long familyId,long goalId,GoalForecastForm form) {
        save(familyId,goalId,form,null);
    }
    @Transactional
    public void save(long familyId,long goalId,GoalForecastForm form,Long actorId) {
        if(!form.errors().isEmpty()) throw new IllegalArgumentException("时间估算的输入无效");
        var goal=goals.findById(familyId,goalId).filter(g->g.getArchivedAt()==null).orElseThrow(()->new IllegalArgumentException("目标不可用"));
        String currency=families.findById(familyId).orElseThrow().getBaseCurrency();
        int count=goals.updateForecast(familyId,goalId,form.getEstimateSource(),"MANUAL".equals(form.getEstimateSource())?form.amount():null,
                currency,form.getEstimateWishMonth()==null || form.getEstimateWishMonth().isBlank()?null:form.getEstimateWishMonth());
        if(count==0) {
            var actual=goals.findById(familyId,goalId).filter(g->g.getArchivedAt()==null).orElseThrow(()->new IllegalArgumentException("目标不可用"));
            if(!Objects.equals(actual.getForecastSource(),form.getEstimateSource()) || !Boolean.TRUE.equals(actual.getForecastWishOverride())
                    || !Objects.equals(actual.getForecastWishMonth(),form.wish()==null?null:form.wish().toString())
                    || "MANUAL".equals(form.getEstimateSource()) && (actual.getForecastManualAmount()==null
                    || actual.getForecastManualAmount().compareTo(form.amount())!=0 || !currency.equals(actual.getForecastManualCurrency())))
                throw new IllegalStateException("时间估算未能保存");
        }
        if(actorId!=null && audit!=null) audit.record(familyId,actorId,com.family.finance.domain.audit.AuditLogType.SYSTEM,
                "family_goal",goalId,"保存时间估算偏好 · "+form.getEstimateSource());
    }
}
