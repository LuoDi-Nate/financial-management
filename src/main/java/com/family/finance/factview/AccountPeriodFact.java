package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccountPeriodFact(
        Long accountId,
        String accountName,
        AccountType accountType,
        AccountClass accountClass,
        AccountLiquidity accountLiquidity,
        String accountCurrency,
        Long ownerId,
        Integer displayOrder,
        Long periodId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal previousEndBalanceOrig,
        BigDecimal endBalanceOrig,
        BigDecimal previousEndBalanceBase,
        BigDecimal endBalanceBase,
        BigDecimal incomeOrig,
        BigDecimal incomeBase,
        BigDecimal expenseOrig,
        BigDecimal expenseBase,
        BigDecimal transferInOrig,
        BigDecimal transferInBase,
        BigDecimal transferOutOrig,
        BigDecimal transferOutBase,
        BigDecimal periodPnlOrig,
        BigDecimal periodPnlBase,
        BigDecimal fxToBase,
        /**
         * v1.30 · 补录本金(这一期余额里「以前就有、这期才补录」的那部分)。
         * 口径同开账基线:不算收入、不算收益,算本金。账户第一期(没有上期余额)恒为 0 ——
         * 第一期的余额已经整笔算开账基线,再算一次就重复了。
         */
        BigDecimal principalAdjOrig,
        BigDecimal principalAdjBase
) {
    /** v1.30 之前的 26 参构造 · 没有补录本金(测试与老调用方不用改) */
    public AccountPeriodFact(Long accountId, String accountName, AccountType accountType, AccountClass accountClass,
                             AccountLiquidity accountLiquidity, String accountCurrency, Long ownerId,
                             Integer displayOrder, Long periodId, LocalDate periodStart, LocalDate periodEnd,
                             BigDecimal previousEndBalanceOrig, BigDecimal endBalanceOrig,
                             BigDecimal previousEndBalanceBase, BigDecimal endBalanceBase,
                             BigDecimal incomeOrig, BigDecimal incomeBase, BigDecimal expenseOrig,
                             BigDecimal expenseBase, BigDecimal transferInOrig, BigDecimal transferInBase,
                             BigDecimal transferOutOrig, BigDecimal transferOutBase,
                             BigDecimal periodPnlOrig, BigDecimal periodPnlBase, BigDecimal fxToBase) {
        this(accountId, accountName, accountType, accountClass, accountLiquidity, accountCurrency, ownerId,
                displayOrder, periodId, periodStart, periodEnd, previousEndBalanceOrig, endBalanceOrig,
                previousEndBalanceBase, endBalanceBase, incomeOrig, incomeBase, expenseOrig, expenseBase,
                transferInOrig, transferInBase, transferOutOrig, transferOutBase, periodPnlOrig, periodPnlBase,
                fxToBase, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal netExternalBase() {
        return incomeBase.subtract(expenseBase);
    }

    public BigDecimal netExternalOrig() {
        return incomeOrig.subtract(expenseOrig);
    }

    public BigDecimal netTransferOrig() {
        return transferInOrig.subtract(transferOutOrig);
    }
}
