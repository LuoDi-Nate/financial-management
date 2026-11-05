package com.family.finance.service.goal;

import com.family.finance.calc.BenchmarkAverage;
import com.family.finance.calc.TwrCalculator;
import com.family.finance.domain.macro.MacroBenchmark;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.CashflowBreakdown;
import com.family.finance.factview.FactSlice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Source-qualified rates. A short cumulative return is never relabelled as an annual assumption. */
public final class RetirementRateSources {
    private RetirementRateSources() { }
    public record Sample(String period, BigDecimal percent, BigDecimal startCapital, BigDecimal profit,
                         BigDecimal external, String source, String sourceUrl, LocalDateTime verifiedAt) {
        public String sourceLabel() { return "manual".equals(source)?"管理页人工核验":source; }
    }
    public record Rate(BigDecimal percent, LocalDate from, LocalDate to, List<Sample> samples, String problem, String note) {
        public static Rate missing(String why) { return new Rate(null,null,null,List.of(),why,null); }
        public int count() { return samples.size(); }
        public boolean available() { return percent!=null; }
    }

    public static Rate cpi(List<MacroBenchmark> values,LocalDate today) {
        Map<Integer,MacroBenchmark> known=values.stream().filter(b->b.getYear()!=null && b.getYear()<today.getYear()
                        && b.getVerifiedAt()!=null && b.getCpiHeadline()!=null && b.getCpiHeadline().compareTo(new BigDecimal("-100"))>0
                        && ("manual".equals(b.getSource()) || "official-nbs".equals(b.getSource())))
                .collect(Collectors.toMap(MacroBenchmark::getYear,Function.identity(),(a,b)->b));
        if(known.isEmpty())return Rate.missing("没有已核验的完整年度CPI,可选择自己填写");
        int last=known.keySet().stream().max(Integer::compareTo).orElseThrow();
        List<Sample> samples=new ArrayList<>();List<BigDecimal> rates=new ArrayList<>();
        for(int year=last-9;year<=last;year++) {
            MacroBenchmark b=known.get(year);
            if(b==null)return Rate.missing("年度CPI需要连续10年;缺少或尚未核验 "+year+" 年");
            samples.add(new Sample(String.valueOf(year),b.getCpiHeadline(),null,null,null,
                    "manual".equals(b.getSource())?"manual":"国家统计局",b.getSourceUrl(),b.getVerifiedAt()));
            rates.add(b.getCpiHeadline());
        }
        String note=last<today.getYear()-1?"最近已核验至 "+last+" 年,新完整年度数据待更新":"已核验的10个完整年度几何均值";
        return new Rate(BenchmarkAverage.geometricMean(rates),LocalDate.of(last-9,1,1),LocalDate.of(last,12,31),List.copyOf(samples),null,note);
    }

    public static Rate investment(FactSlice slice,Set<Long> selected,LocalDate today,
                                  Function<Long,CashflowBreakdown> household,Predicate<AccountPeriodFact> knownFx) {
        if(selected.isEmpty())return Rate.missing("没有纳入的金融资产,无法提供动态投资回报");
        Map<Long,Map<Long,AccountPeriodFact>> grouped=slice.rows().stream().collect(Collectors.groupingBy(AccountPeriodFact::periodId,
                Collectors.toMap(AccountPeriodFact::accountId,Function.identity(),(a,b)->a)));
        List<Long> ids=(slice.closedPeriodIds()==null?List.<Long>of():slice.closedPeriodIds()).stream()
                .filter(id->slice.periodStartOf(id)!=null && slice.periodStartOf(id).isBefore(today.withDayOfMonth(1)))
                .sorted(Comparator.comparing(slice::periodStartOf)).toList();
        List<TwrCalculator.TwrPoint> points=new ArrayList<>();List<Sample> samples=new ArrayList<>();
        String stopped=null;
        for(int i=ids.size()-1;i>0 && points.size()<36;i--) {
            Long id=ids.get(i),previousId=ids.get(i-1);
            LocalDate start=slice.periodStartOf(id),previous=slice.periodStartOf(previousId);
            if(!YearMonth.from(previous).plusMonths(1).equals(YearMonth.from(start))) {stopped="收益来源中间有缺月";break;}
            Map<Long,AccountPeriodFact> currentRows=grouped.getOrDefault(id,Map.of()),before=grouped.getOrDefault(previousId,Map.of());
            CashflowBreakdown cf=household.apply(id);
            BigDecimal accountIncome=currentRows.values().stream().map(AccountPeriodFact::incomeBase).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal accountExpense=currentRows.values().stream().map(AccountPeriodFact::expenseBase).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(cf==null || nz(cf.income()).subtract(accountIncome).abs().compareTo(new BigDecimal("0.01"))>0
                    || nz(cf.expense()).subtract(accountExpense).abs().compareTo(new BigDecimal("0.01"))>0) {
                stopped="历史家庭收支有未分配到账户的总额,无法核对所选资金的纯投资回报";break;
            }
            BigDecimal capital=BigDecimal.ZERO,end=BigDecimal.ZERO,profit=BigDecimal.ZERO;
            boolean bad=false;
            for(Long account:selected) {
                AccountPeriodFact row=currentRows.get(account),prev=before.get(account);
                if((row==null || row.endBalanceBase()==null) && (prev==null || prev.endBalanceBase()==null))continue;
                if(row==null || row.endBalanceBase()==null || !knownFx.test(row)) {bad=true;break;}
                end=end.add(row.endBalanceBase());
                if(prev==null || prev.endBalanceBase()==null) {
                    if(row.previousEndBalanceOrig()!=null) {bad=true;break;}
                    // A newly recorded account is opening capital, not first-month investment performance.
                    continue;
                }
                if(!knownFx.test(prev) || row.previousEndBalanceBase()==null || row.periodPnlBase()==null) {bad=true;break;}
                capital=capital.add(prev.endBalanceBase());
                profit=profit.add(row.periodPnlBase()).add(row.previousEndBalanceBase().subtract(prev.endBalanceBase()));
            }
            if(bad || capital.signum()<=0) {stopped="收益来源缺余额/汇率或期初本金非正";break;}
            BigDecimal periodReturn=profit.divide(capital,12,RoundingMode.HALF_EVEN);
            if(BigDecimal.ONE.add(periodReturn).signum()<=0) {stopped="该月投资本金已耗尽,不能按有效复利年化";break;}
            BigDecimal external=end.subtract(capital).subtract(profit);
            points.add(new TwrCalculator.TwrPoint(capital,end,external));
            samples.add(new Sample(YearMonth.from(start).toString(),periodReturn.movePointRight(2),capital,profit,external,
                    "账户月度事实",null,null));
        }
        java.util.Collections.reverse(points);java.util.Collections.reverse(samples);
        if(points.size()<12)return new Rate(null,null,null,List.copyOf(samples),
                "只找到 "+points.size()+" 个完整月度收益间隔,至少需要12个(13张月末快照)"+(stopped==null?"":";"+stopped),null);
        BigDecimal cumulative=TwrCalculator.cumulative(points).orElse(null);
        BigDecimal annual=TwrCalculator.annualized(cumulative,points.size()).orElse(null);
        if(annual==null)return Rate.missing("这组历史收益无法换算为有效年回报");
        return new Rate(annual.movePointRight(2),YearMonth.parse(samples.getFirst().period()).atDay(1),
                YearMonth.parse(samples.getLast().period()).atEndOfMonth(),List.copyOf(samples),null,
                "同一金融资产范围的历史纯投资收益;仅反映已入账税费,不是未来回报承诺");
    }
    private static BigDecimal nz(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
