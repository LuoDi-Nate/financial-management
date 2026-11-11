package com.family.finance.service.goal;

import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.*;
import com.family.finance.repository.*;
import com.family.finance.service.expense.ExpenseLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonthlyContributionService {
    private final FactViewService facts;
    private final FamilyMapper families;
    private final RetirementSourceMapper sources;
    private final ExpenseLedgerService expenses;
    private final FxMapper fx;

    public record Month(LocalDate start, BigDecimal income, BigDecimal expense, boolean complete, String reason) {
        public BigDecimal saving() { return complete ? income.subtract(expense) : null; }
    }
    public record Window(String currency, LocalDate asOf, LocalDate from, LocalDate to,
                         List<Month> months, BigDecimal median, String note) {
        public int count() { return (int) months.stream().filter(Month::complete).count(); }
    }

    @Transactional(readOnly = true)
    public Window load(long familyId, LocalDate asOf) {
        var family = families.findById(familyId).orElseThrow();
        String currency = family.getBaseCurrency();
        LocalDate until = asOf.withDayOfMonth(1), from = until.minusMonths(6);
        if (family.getPeriodType() != PeriodType.MONTHLY)
            return new Window(currency, asOf, from, until.minusDays(1), List.of(), null, "周账期不能冒充月度结余,可手填本目标月可用结余");
        var slice = facts.load(new FactFilter(familyId, PeriodType.MONTHLY, from, until.minusDays(1), true, null, currency));
        List<Long> ids = slice.periodIds().stream().filter(id -> slice.periodStartOf(id) != null
                && !slice.periodStartOf(id).isBefore(from) && slice.periodStartOf(id).isBefore(until)).toList();
        Map<Long, RetirementSourceMapper.Presence> presence = ids.isEmpty() ? Map.of() : sources.presence(familyId, ids).stream()
                .collect(Collectors.toMap(RetirementSourceMapper.Presence::periodId, p -> p));
        var ledger = ids.isEmpty() ? Map.<Long,ExpenseLedgerService.PeriodExpense>of() : expenses.byPeriods(familyId, ids);
        var rates = fx.findRatesAsOf(familyId, asOf);
        Map<LocalDate,Long> byMonth = new HashMap<>();
        ids.forEach(id -> byMonth.put(slice.periodStartOf(id), id));
        List<Month> months = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            LocalDate start = from.plusMonths(i); Long id = byMonth.get(start);
            var p = id == null ? null : presence.get(id);
            boolean settled = id != null && slice.closedPeriodIds() != null && slice.closedPeriodIds().contains(id);
            if (!settled || p == null) { months.add(new Month(start,null,null,false,"账期或收支尚未定稿")); continue; }
            boolean confirmed = p.requiredMembers() > 0 && p.completedMembers() >= p.requiredMembers();
            boolean incomeKnown = confirmed || p.incomeFields() > 0 || p.incomeRows() > 0;
            var expense = ledger.get(id);
            boolean expenseKnown = confirmed || p.expenseFields() > 0 || expense != null && expense.filled();
            var historicalRates = rates.stream().filter(rate -> rate.getPeriodStart() != null && !rate.getPeriodStart().isAfter(start)).toList();
            boolean missingFx = slice.rows().stream().filter(row -> Objects.equals(row.periodId(),id))
                    .filter(row -> row.incomeOrig() != null && row.incomeOrig().signum()!=0 || row.expenseOrig()!=null && row.expenseOrig().signum()!=0)
                    .anyMatch(row -> RetirementDataService.factor(row.accountCurrency(),currency,historicalRates)==null);
            if (!incomeKnown || !expenseKnown || missingFx) {
                months.add(new Month(start,null,null,false,missingFx?"缺少可追溯汇率":"收入或支出来源未齐,不当作零")); continue;
            }
            var flow = facts.cashflowBreakdown(slice,id);
            months.add(new Month(start,flow.income(),flow.expense(),true,"收入减支出,参与中位数"));
        }
        var savings = months.stream().filter(Month::complete).map(Month::saving).sorted().toList();
        BigDecimal median = median(savings);
        String note = "近6个已结束月,采用"+savings.size()+"个月"+(savings.size()<3?" · 样本较少":"");
        return new Window(currency,asOf,from,until.minusDays(1),List.copyOf(months),median,note);
    }
    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList(); int n=sorted.size();
        return n%2==1?sorted.get(n/2):sorted.get(n/2-1).add(sorted.get(n/2)).divide(BigDecimal.valueOf(2),2,RoundingMode.HALF_EVEN);
    }
}
