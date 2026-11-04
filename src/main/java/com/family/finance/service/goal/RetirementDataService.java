package com.family.finance.service.goal;

import com.family.finance.calc.RetirementCalculator;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.goal.RetirementPlan;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.factview.*;
import com.family.finance.repository.*;
import com.family.finance.service.expense.ExpenseLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetirementDataService {
    private final FactViewService facts;
    private final FamilyMapper families;
    private final AccountMapper accounts;
    private final SnapshotMapper snapshots;
    private final FxMapper fxMapper;
    private final RetirementSourceMapper sources;
    private final ExpenseLedgerService expenses;
    private final MacroBenchmarkMapper macro;

    public record Asset(Long id, String name, AccountType type, String currency, BigDecimal original,
                        BigDecimal amount, BigDecimal fx, LocalDateTime recordedAt, boolean eligible,
                        boolean selected, boolean liability, String note) { }
    public record Month(Long id, LocalDate start, BigDecimal income, BigDecimal expense, BigDecimal consumption,
                        BigDecimal removed, String source, boolean complete) { }
    public record Data(String baseCurrency, LocalDate balancePeriod, List<Asset> assets, List<Month> months,
                       BigDecimal selectedAssets, BigDecimal debt, BigDecimal suggestedExpense,
                       BigDecimal suggestedContribution, String budgetFingerprint, List<String> problems,
                       List<String> budgetNotes, String cpiReference) { }

    public Data load(long familyId, RetirementPlan plan, LocalDate today) {
        var family = families.findById(familyId).orElseThrow(() -> new IllegalArgumentException("家庭不存在"));
        String currency = family.getBaseCurrency();
        var slice = facts.load(new FactFilter(familyId, family.getPeriodType(), today.withDayOfMonth(1).minusMonths(12),
                today, true, null, currency));
        List<String> problems = new ArrayList<>(), budgetNotes = new ArrayList<>();
        boolean monthlyPeriods = family.getPeriodType() == com.family.finance.domain.period.PeriodType.MONTHLY;
        if (!monthlyPeriods) budgetNotes.add("当前为周账期,不将一周冒充一个月;本版请手填月度生活预算与未来月投入。");
        if (plan.getBaseCurrency() != null && !currency.equals(plan.getBaseCurrency())) problems.add("家庭本位币已改变,请重新核对计划金额的币种");
        var rates = fxMapper.findRatesAsOf(familyId, today);
        var active = accounts.findActiveByFamily(familyId);
        Map<Long, AccountPeriodFact> balances = slice.rows().stream().filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .collect(Collectors.toMap(AccountPeriodFact::accountId, r -> r, (a,b) -> a));
        var snapshotById = slice.lastPeriodId() == null ? Map.<Long, PeriodSnapshot>of()
                : snapshots.findByPeriod(familyId, slice.lastPeriodId()).stream().collect(Collectors.toMap(PeriodSnapshot::getAccountId, r -> r));
        List<Asset> assetRows = new ArrayList<>(); BigDecimal a = BigDecimal.ZERO, d = BigDecimal.ZERO;
        Set<Long> visible = new HashSet<>();
        for (var account : active) {
            visible.add(account.getId());
            var row = balances.get(account.getId());
            BigDecimal factor = factor(account.getCurrency(), currency, rates);
            BigDecimal original = row == null ? null : row.endBalanceOrig();
            BigDecimal amount = row == null ? null : row.endBalanceBase();
            boolean liability = account.getType() == AccountType.LOAN;
            boolean eligible = eligible(account.getType());
            boolean selected = plan.getAccountIds().contains(account.getId()) && !liability;
            String note = liability ? "全家庭负债:统一预留一次" : eligible ? "可选择用于退休" : "默认不作为生活本金";
            if ((selected || liability) && original == null) problems.add(account.getDisplayName() + ":当前账期余额未填");
            if ((selected || liability) && factor == null) problems.add(account.getDisplayName() + ":缺少 " + account.getCurrency() + " 换汇来源");
            if ((selected || liability) && row != null && factor != null && row.fxToBase() != null
                    && row.fxToBase().subtract(factor).abs().compareTo(new BigDecimal("0.000001")) > 0)
                problems.add(account.getDisplayName() + ":当前事实换汇与可追溯汇率不一致,请先核对汇率");
            if (selected && !eligible) {
                amount = plan.getRealizableAmounts().get(account.getId());
                if (amount == null || amount.signum() < 0) problems.add(account.getDisplayName() + ":需确认可变现金额(本位币)");
                else amount = amount.multiply(RetirementCalculator.priceFactor(plan.getPriceDate() == null ? today : plan.getPriceDate(),
                        today, RetirementCalculator.fraction(plan.getInflationPct() == null ? BigDecimal.ZERO : plan.getInflationPct())));
                note = "按人工确认的可变现金额,不按保额";
            }
            if (liability && amount != null) d = d.add(amount.abs());
            if (selected && amount != null) a = a.add(amount);
            var snapshot = snapshotById.get(account.getId());
            assetRows.add(new Asset(account.getId(), account.getDisplayName(), account.getType(), account.getCurrency(), original,
                    amount, factor, snapshot == null ? null : snapshot.getSubmittedAt(), eligible, selected, liability, note));
        }
        for (Long id : plan.getAccountIds()) if (!visible.contains(id)) problems.add("选中的账户 #" + id + " 已归档或删除,请重新确认资金范围");
        List<Long> ids = slice.closedPeriodIds() == null ? List.of() : slice.closedPeriodIds();
        if (ids.size() > 12) ids = ids.subList(ids.size() - 12, ids.size());
        var presence = ids.isEmpty() ? Map.<Long, RetirementSourceMapper.Presence>of()
                : sources.presence(familyId, ids).stream().collect(Collectors.toMap(RetirementSourceMapper.Presence::periodId, r -> r));
        Set<Long> totalIds = presence.values().stream().filter(v -> v.expenseFields() > 0).map(RetirementSourceMapper.Presence::periodId).collect(Collectors.toSet());
        var budgets = expenses.retirementBudget(familyId, ids, plan.getExcludedCategoryIds(), plan.isExcludeOneOff(), totalIds);
        List<Month> months = new ArrayList<>();
        List<BigDecimal> expenseValues = new ArrayList<>();
        for (Long id : ids) {
            var present = presence.get(id);
            boolean explicitlyComplete = present != null && present.requiredMembers() > 0 && present.completedMembers() >= present.requiredMembers();
            var budget = budgets.get(id);
            var cashflow = facts.cashflowBreakdown(slice, id);
            LocalDate start = slice.periodStartOf(id);
            var historicalRates = rates.stream().filter(rate -> rate.getPeriodStart() != null && !rate.getPeriodStart().isAfter(start)).toList();
            boolean missingFx = slice.rows().stream().filter(row -> Objects.equals(id, row.periodId()))
                    .filter(row -> (row.incomeOrig() != null && row.incomeOrig().signum() != 0)
                            || (row.expenseOrig() != null && row.expenseOrig().signum() != 0))
                    .anyMatch(row -> factor(row.accountCurrency(), currency, historicalRates) == null);
            if (missingFx) budgetNotes.add(start + ":历史收支缺少当时汇率,未用于预算/投入建议");
            boolean expenseKnown = budget != null || explicitlyComplete;
            boolean incomeKnown = explicitlyComplete || (present != null && (present.incomeFields() > 0 || present.incomeRows() > 0));
            if (missingFx) { expenseKnown = false; incomeKnown = false; }
            BigDecimal consumption = budget != null ? budget.amount() : explicitlyComplete ? BigDecimal.ZERO : null;
            if (missingFx) consumption = null;
            String source = budget == null ? explicitlyComplete ? "已确认填完的零支出" : "支出缺失" : budget.source();
            if (budget != null && budget.totalOnly() && (plan.isExcludeOneOff() || !plan.getExcludedCategoryIds().isEmpty()))
                budgetNotes.add(slice.periodStartOf(id) + ":只有总额,不能识别需要排除的分类/一次性支出");
            if (consumption != null && consumption.signum() < 0) budgetNotes.add(slice.periodStartOf(id) + ":退款多于消费,请确认长期预算");
            if (consumption != null) {
                BigDecimal pi = RetirementCalculator.fraction(plan.getInflationPct() == null ? BigDecimal.ZERO : plan.getInflationPct());
                expenseValues.add(consumption.multiply(RetirementCalculator.priceFactor(slice.periodStartOf(id), today, pi)));
            }
            months.add(new Month(id, slice.periodStartOf(id), incomeKnown ? cashflow.income() : null,
                    expenseKnown ? cashflow.expense() : null, consumption,
                    budget == null ? BigDecimal.ZERO : budget.removed(), source, expenseKnown && incomeKnown));
        }
        BigDecimal suggestedExpense = expenseValues.size() < 3 || !budgetNotes.isEmpty() ? null
                : expenseValues.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(expenseValues.size()), 2, RoundingMode.HALF_EVEN);
        List<Month> contributionWindow = months.subList(Math.max(0, months.size() - 6), months.size());
        List<BigDecimal> savings = contributionWindow.stream().filter(Month::complete).map(m -> m.income().subtract(m.expense())).sorted().toList();
        BigDecimal contribution = savings.isEmpty() ? null : savings.size() % 2 == 1 ? savings.get(savings.size() / 2)
                : savings.get(savings.size()/2-1).add(savings.get(savings.size()/2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
        if (!monthlyPeriods) contribution = null;
        String fingerprint = com.family.finance.service.llmtrace.PromptRecorder.sha256(months.toString());
        String cpi = macro.findAll().stream().filter(v -> v.getYear() < today.getYear()).max(Comparator.comparing(v -> v.getYear()))
                .map(v -> v.getYear() + " 年 CPI " + v.getCpiHeadline() + "% (年度历史参考,不自动替换长期假设)")
                .orElse("暂无已发布完整年度 CPI 参考,可手填长期假设");
        return new Data(currency, slice.periodStartOf(slice.lastPeriodId()), List.copyOf(assetRows), List.copyOf(months), a, d,
                suggestedExpense, contribution, fingerprint, List.copyOf(problems), List.copyOf(budgetNotes), cpi);
    }

    public static boolean eligible(AccountType type) {
        return type == AccountType.CASH || type == AccountType.STOCK || type == AccountType.FUND
                || type == AccountType.WEALTH || type == AccountType.CRYPTO || type == AccountType.METAL;
    }

    /** 只用数据库已有的、截止日期前的汇率;无来源返回 null,不会出网或写入。 */
    public static BigDecimal factor(String from, String to, List<FxRate> rates) {
        if (Objects.equals(from, to)) return BigDecimal.ONE;
        Map<String, Map<String, BigDecimal>> graph = new LinkedHashMap<>();
        for (var rate : rates) {
            if (rate.getRate() == null || rate.getRate().signum() <= 0) continue;
            graph.computeIfAbsent(rate.getBaseCurrency(), k -> new LinkedHashMap<>()).putIfAbsent(rate.getQuoteCurrency(), rate.getRate());
            graph.computeIfAbsent(rate.getQuoteCurrency(), k -> new LinkedHashMap<>()).putIfAbsent(rate.getBaseCurrency(), BigDecimal.ONE.divide(rate.getRate(), 16, RoundingMode.HALF_EVEN));
        }
        record Step(String currency, BigDecimal factor) { }
        ArrayDeque<Step> queue = new ArrayDeque<>(); queue.add(new Step(from, BigDecimal.ONE)); Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            var step = queue.remove(); if (!seen.add(step.currency())) continue;
            for (var edge : graph.getOrDefault(step.currency(), Map.of()).entrySet()) {
                var value = step.factor().multiply(edge.getValue());
                if (edge.getKey().equals(to)) return value;
                queue.add(new Step(edge.getKey(), value));
            }
        }
        return null;
    }

    public BigDecimal viewFactor(long familyId, String from, String to, LocalDate today) {
        return factor(from, to, fxMapper.findRatesAsOf(familyId, today));
    }
}
