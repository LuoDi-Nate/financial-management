package com.family.finance.service.checkup;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.calc.MaxDrawdownCalculator;
import com.family.finance.calc.NavSeriesBuilder;
import com.family.finance.calc.XirrCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.family.Family;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.service.AccountService;
import com.family.finance.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账户体检 · 主聚合服务 · v0.2 FR-40b
 *
 * 加载某账户过去 12 个月的 fact slice → 计算所有指标 → 输出 {@link AccountDiagnose} 给模板。
 *
 * 无副作用、可并发。每次请求完整重算,数据量小(<= 12 行)无须缓存。
 */
@Service
@RequiredArgsConstructor
public class AccountDiagnoseService {

    private final AccountService accountService;
    private final ProductCategoryService productCategoryService;
    private final FactViewService factViewService;
    private final FamilyMapper familyMapper;
    private final com.family.finance.repository.PeriodMapper periodMapper;

    public AccountDiagnose diagnose(long familyId, long accountId) {
        Account account = accountService.require(familyId, accountId);
        ProductCategory category = account.getProductCategoryCode() == null
                ? null
                : productCategoryService.findByCode(account.getProductCategoryCode()).orElse(null);

        Family family = familyMapper.findById(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));

        // v0.2 bug 修(2026-05-10): 与 dashboard / FamilyDiagnoseService 同口径,
        // 用 latest period 作 end,而非 LocalDate.now() — 避免未来期数据被排除。
        com.family.finance.domain.period.Period latest = periodMapper.findLatest(familyId, 1)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("尚未创建周期"));
        LocalDate end = latest.getPeriodStart();
        LocalDate start = end.minusMonths(11);
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), start, end,
                true,                                  // 体检页允许查归档账户
                List.of(accountId),
                family.getBaseCurrency()
        ));

        List<AccountPeriodFact> rows = slice.rows().stream()
                .filter(r -> r.accountId().equals(accountId))
                .sorted(Comparator.comparing(AccountPeriodFact::periodStart))
                .toList();

        BigDecimal currentBalance = rows.isEmpty() ? null : rows.get(rows.size() - 1).endBalanceOrig();
        // v1.27 · 占比规则要和全家总资产(本位币)同币种相除
        BigDecimal currentBalanceBase = rows.isEmpty() ? null : rows.get(rows.size() - 1).endBalanceBase();
        BigDecimal previousBalance = rows.size() < 2 ? null : rows.get(rows.size() - 2).endBalanceOrig();
        BigDecimal monthDelta = (currentBalance != null && previousBalance != null)
                ? currentBalance.subtract(previousBalance) : null;
        Integer monthsHeld = rows.isEmpty() ? null : rows.size();

        // 累计现金流(账户币种)
        BigDecimal cumIncome = sum(rows, AccountPeriodFact::incomeOrig);
        BigDecimal cumExpense = sum(rows, AccountPeriodFact::expenseOrig);
        BigDecimal cumIn = sum(rows, AccountPeriodFact::transferInOrig);
        BigDecimal cumOut = sum(rows, AccountPeriodFact::transferOutOrig);
        // v1.30.1 · 补录本金也是投进来的本金(与事实层 FactViewServiceImpl 同口径;v1.30 这里漏了)
        BigDecimal cumPrincipal = sum(rows, AccountPeriodFact::principalAdjOrig);
        BigDecimal netPrincipal = cumIncome.subtract(cumExpense).add(cumIn).subtract(cumOut).add(cumPrincipal);

        // 累计投资损益:跨期 PnL 累加(已剔除外部 + 转账)
        BigDecimal cumPnl = sum(rows, AccountPeriodFact::periodPnlOrig);

        // 年化:用 XIRR(基于账户原币种 endBalance + 净外部流入流出)
        BigDecimal annualized = computeAnnualized(rows);

        // NAV 序列 + 最大回撤
        List<NavSeriesBuilder.PeriodPoint> navInputs = rows.stream()
                .map(r -> new NavSeriesBuilder.PeriodPoint(
                        r.periodStart(),
                        r.endBalanceOrig(),
                        r.incomeOrig(),
                        r.expenseOrig(),
                        // v1.30.1 · 补录本金按外部投入进净值序列(不然补录那一期像涨了一大截)
                        nz(r.transferInOrig()).add(nz(r.principalAdjOrig())),
                        r.transferOutOrig()))
                .toList();
        List<MaxDrawdownCalculator.NavPoint> navSeries = NavSeriesBuilder.build(navInputs);
        MaxDrawdownCalculator.Result drawdown = navSeries.size() >= 2
                ? MaxDrawdownCalculator.calculate(navSeries)
                : null;

        // 基准对照
        BenchmarkComparator.Result benchmark = BenchmarkComparator.compare(
                annualized,
                category == null ? null : category.getBenchmarkPct());

        Integer effectiveRisk = effectiveRiskLevel(account, category);
        boolean riskOverridden = account.getRiskLevelOverride() != null && account.getRiskLevelOverride() > 0;

        // v1.30.1 · 「本期变化」拆开说:其中多少是账户间划转 + 补录本金,本期投资损益多少(给页面与 AI 提示词)
        AccountPeriodFact lastRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        BigDecimal periodNetTransfer = lastRow == null || previousBalance == null ? null
                : nz(lastRow.transferInOrig()).subtract(nz(lastRow.transferOutOrig())).add(nz(lastRow.principalAdjOrig()));
        BigDecimal periodPnl = lastRow == null ? null : lastRow.periodPnlOrig();

        // 余额走势(取每期 endBalance 用作 sparkline)
        List<AccountDiagnose.TrendPoint> sparkline = rows.stream()
                .map(r -> new AccountDiagnose.TrendPoint(r.periodStart(), r.endBalanceOrig()))
                .toList();

        return new AccountDiagnose(
                account,
                category,
                currentBalance,
                previousBalance,
                monthDelta,
                monthsHeld,
                cumIncome,
                cumExpense,
                cumIn,
                cumOut,
                netPrincipal,
                cumPnl,
                annualized,
                drawdown,
                benchmark,
                effectiveRisk,
                riskOverridden,
                sparkline,
                currentBalanceBase,
                periodNetTransfer,
                periodPnl
        );
    }

    private static BigDecimal computeAnnualized(List<AccountPeriodFact> rows) {
        if (rows.size() < 2) return null;
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        AccountPeriodFact first = rows.getFirst();
        AccountPeriodFact last = rows.getLast();
        if (first.endBalanceOrig() == null || last.endBalanceOrig() == null) return null;
        flows.add(new XirrCalculator.CashFlowPoint(first.periodEnd(), first.endBalanceOrig().negate()));
        for (int i = 1; i < rows.size(); i++) {
            AccountPeriodFact row = rows.get(i);
            BigDecimal netExternal = nz(row.incomeOrig())
                    .subtract(nz(row.expenseOrig()))
                    .add(nz(row.transferInOrig()))
                    .subtract(nz(row.transferOutOrig()))
                    .add(nz(row.principalAdjOrig()));   // v1.30.1 · 补录本金 = 外部投入
            if (netExternal.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(row.periodEnd(), netExternal.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(last.periodEnd(), last.endBalanceOrig()));
        return XirrCalculator.annualizedOrCumulative(flows, rows.size());
    }

    private static Integer effectiveRiskLevel(Account account, ProductCategory category) {
        Integer override = account.getRiskLevelOverride();
        if (override != null && override > 0) return override;
        return category == null ? null : category.getRiskLevel();
    }

    private static BigDecimal sum(List<AccountPeriodFact> rows,
                                  java.util.function.Function<AccountPeriodFact, BigDecimal> getter) {
        return rows.stream()
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
