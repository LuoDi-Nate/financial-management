package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PnlCalculator {
    private static final int MONEY_SCALE = 2;

    private PnlCalculator() {
    }

    public static BigDecimal periodPnl(BigDecimal endThis,
                                       BigDecimal endPrev,
                                       BigDecimal income,
                                       BigDecimal expense,
                                       BigDecimal transferIn,
                                       BigDecimal transferOut) {
        if (endThis == null || endPrev == null) {
            return null;
        }
        BigDecimal netExternal = nz(income).subtract(nz(expense));
        BigDecimal netTransfer = nz(transferIn).subtract(nz(transferOut));
        return money(endThis.subtract(endPrev).subtract(netExternal).subtract(netTransfer));
    }

    /**
     * v1.30 · 带补录本金的版本:补录本金和划转一样是「从外面进来的本金」,从收益里剔除。
     * {@code principalAdj} 为 0 时与 6 参版本逐分相同。
     */
    public static BigDecimal periodPnl(BigDecimal endThis,
                                       BigDecimal endPrev,
                                       BigDecimal income,
                                       BigDecimal expense,
                                       BigDecimal transferIn,
                                       BigDecimal transferOut,
                                       BigDecimal principalAdj) {
        BigDecimal pnl = periodPnl(endThis, endPrev, income, expense, transferIn, transferOut);
        if (pnl == null || principalAdj == null || principalAdj.signum() == 0) return pnl;
        return money(pnl.subtract(principalAdj));
    }

    public static BigDecimal toBase(BigDecimal orig, BigDecimal fxToBase) {
        if (orig == null) {
            return null;
        }
        return money(orig.multiply(fxToBase == null ? BigDecimal.ONE : fxToBase));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }
}
