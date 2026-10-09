package com.family.finance.factview;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FactBaseRow(
        Long accountId,
        String accountName,
        String accountType,
        String accountCurrency,
        Long ownerId,
        Integer displayOrder,
        Long periodId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal previousEndBalance,
        BigDecimal endBalance,
        BigDecimal incomeOrig,
        BigDecimal expenseOrig,
        BigDecimal transferInOrig,
        BigDecimal transferOutOrig,
        BigDecimal fxToBase,
        /** v0.3.3 · pc.liquidity_class · 来自 LEFT JOIN product_category · 可空(未设类目) */
        String productLiquidityClass,
        /** v1.30 · 这一期补录的本金(账户币种 · 补录本金合计 · 没有 = 0) */
        BigDecimal principalAdjOrig
) {
    /** v1.30 之前的 17 参构造 · 没有补录本金(测试与老调用方不用改) */
    public FactBaseRow(Long accountId, String accountName, String accountType, String accountCurrency, Long ownerId,
                       Integer displayOrder, Long periodId, LocalDate periodStart, LocalDate periodEnd,
                       BigDecimal previousEndBalance, BigDecimal endBalance, BigDecimal incomeOrig,
                       BigDecimal expenseOrig, BigDecimal transferInOrig, BigDecimal transferOutOrig,
                       BigDecimal fxToBase, String productLiquidityClass) {
        this(accountId, accountName, accountType, accountCurrency, ownerId, displayOrder, periodId, periodStart,
                periodEnd, previousEndBalance, endBalance, incomeOrig, expenseOrig, transferInOrig, transferOutOrig,
                fxToBase, productLiquidityClass, BigDecimal.ZERO);
    }
}
