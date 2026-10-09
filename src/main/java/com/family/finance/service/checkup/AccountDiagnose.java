package com.family.finance.service.checkup;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.calc.MaxDrawdownCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 账户体检 ViewModel · v0.2 · FR-40b
 *
 * 由 {@link AccountDiagnoseService} 计算填充,模板按 {@link AccountType} 分支渲染:
 * - STOCK / WEALTH / CRYPTO:4 张投资卡 — 收益 / 风险 / 基准 / 现金流
 * - CASH:2 张储蓄卡 — 余额 / 流动性
 * - LOAN:2 张负债卡 — 本金 / 还款进度
 * - PROPERTY / OTHER:1 张简卡 — 估值
 */
public record AccountDiagnose(
        Account account,
        ProductCategory category,
        BigDecimal currentBalance,
        BigDecimal previousBalance,
        BigDecimal monthDelta,
        Integer monthsHeld,
        BigDecimal cumulativeIncome,
        BigDecimal cumulativeExpense,
        BigDecimal cumulativeTransferIn,
        BigDecimal cumulativeTransferOut,
        /** 净外部本金投入 = income - expense + transferIn - transferOut(累计) */
        BigDecimal netPrincipalInjected,
        /** 累计投资损益 = currentBalance - sum(net principal injected) */
        BigDecimal cumulativePnl,
        BigDecimal annualizedReturn,
        MaxDrawdownCalculator.Result drawdown,
        BenchmarkComparator.Result benchmark,
        Integer effectiveRiskLevel,
        boolean riskOverridden,
        List<TrendPoint> sparkline,
        /**
         * v1.27(PRD §13 ⑩)· 当前余额<b>换算成本位币</b>。
         * {@link #currentBalance} 是账户原币 —— 集中度类规则拿它去除以全家总资产(本位币),
         * 一个 10 万美元的账户被当成 10 万人民币,占比算小了七倍多。占比一律用这个。
         */
        BigDecimal currentBalanceBase,
        /**
         * v1.30.1 · 本期「本期变化」里有多少是账户间划转 + 补录本金(原币 · 转入 − 转出 + 补录)。
         * 「本期变化」是余额差,含划转;不把这一块单独说出来,AI 会把一笔转出当成亏损。null = 没有上期
         */
        BigDecimal periodNetTransfer,
        /** v1.30.1 · 本期投资损益(原币 · 已剔收支、划转、补录本金)· null = 没有上期 */
        BigDecimal periodPnl
) {
    /** v1.30.1 之前的签名:没有本期划转 / 本期损益 */
    public AccountDiagnose(Account account, ProductCategory category, BigDecimal currentBalance,
                           BigDecimal previousBalance, BigDecimal monthDelta, Integer monthsHeld,
                           BigDecimal cumulativeIncome, BigDecimal cumulativeExpense,
                           BigDecimal cumulativeTransferIn, BigDecimal cumulativeTransferOut,
                           BigDecimal netPrincipalInjected, BigDecimal cumulativePnl, BigDecimal annualizedReturn,
                           MaxDrawdownCalculator.Result drawdown, BenchmarkComparator.Result benchmark,
                           Integer effectiveRiskLevel, boolean riskOverridden, List<TrendPoint> sparkline,
                           BigDecimal currentBalanceBase) {
        this(account, category, currentBalance, previousBalance, monthDelta, monthsHeld, cumulativeIncome,
                cumulativeExpense, cumulativeTransferIn, cumulativeTransferOut, netPrincipalInjected, cumulativePnl,
                annualizedReturn, drawdown, benchmark, effectiveRiskLevel, riskOverridden, sparkline,
                currentBalanceBase, null, null);
    }

    /** v1.27 之前的签名:没有本位币余额(测试 / 老调用方)—— 占比规则回落原币 */
    public AccountDiagnose(Account account, ProductCategory category, BigDecimal currentBalance,
                           BigDecimal previousBalance, BigDecimal monthDelta, Integer monthsHeld,
                           BigDecimal cumulativeIncome, BigDecimal cumulativeExpense,
                           BigDecimal cumulativeTransferIn, BigDecimal cumulativeTransferOut,
                           BigDecimal netPrincipalInjected, BigDecimal cumulativePnl, BigDecimal annualizedReturn,
                           MaxDrawdownCalculator.Result drawdown, BenchmarkComparator.Result benchmark,
                           Integer effectiveRiskLevel, boolean riskOverridden, List<TrendPoint> sparkline) {
        this(account, category, currentBalance, previousBalance, monthDelta, monthsHeld, cumulativeIncome,
                cumulativeExpense, cumulativeTransferIn, cumulativeTransferOut, netPrincipalInjected, cumulativePnl,
                annualizedReturn, drawdown, benchmark, effectiveRiskLevel, riskOverridden, sparkline, currentBalance,
                null, null);
    }

    /** 与全家总资产(本位币)相除时用这个 */
    public BigDecimal balanceForShare() {
        return currentBalanceBase != null ? currentBalanceBase : currentBalance;
    }

    /**
     * v1.18.5 · 收口到 {@link AccountType#isInvestment()}。
     * 原来这里写死 STOCK/WEALTH/CRYPTO —— <b>漏了 v0.14 加的 METAL</b>,
     * 于是贵金属账户被「持有期 / 收益 / 回撤」三条投资类体检规则静默跳过。
     */
    public boolean isInvestment() {
        return account.getType().isInvestment();
    }

    public boolean isCash() {
        return account.getType() == AccountType.CASH;
    }

    public boolean isLoan() {
        return account.getType().isLiability();
    }

    public boolean isProperty() {
        return account.getType() == AccountType.PROPERTY;
    }

    public String riskStars() {
        if (effectiveRiskLevel == null || effectiveRiskLevel <= 0) return "—";
        return "★".repeat(Math.min(effectiveRiskLevel, 6));
    }

    public String monthDeltaPctLabel() {
        if (previousBalance == null || previousBalance.signum() == 0 || monthDelta == null) return "—";
        BigDecimal pct = monthDelta.multiply(new BigDecimal("100"))
                .divide(previousBalance.abs(), 1, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public String annualizedReturnPctLabel() {
        if (annualizedReturn == null) return "—";
        BigDecimal pct = annualizedReturn.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public record TrendPoint(LocalDate month, BigDecimal endBalance) {
    }
}
