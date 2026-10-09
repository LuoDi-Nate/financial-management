package com.family.finance.factview;

import com.family.finance.calc.PnlCalculator;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FactProjector {
    private FactProjector() {
    }

    public static AccountPeriodFact project(FactBaseRow row) {
        AccountType type = AccountType.valueOf(row.accountType());
        AccountLiquidity liquidity = liquidityOf(type, row.productLiquidityClass());
        BigDecimal fx = row.fxToBase() == null ? BigDecimal.ONE : row.fxToBase();
        BigDecimal incomeOrig = money(nz(row.incomeOrig()));
        BigDecimal expenseOrig = money(nz(row.expenseOrig()));
        BigDecimal transferInOrig = money(nz(row.transferInOrig()));
        BigDecimal transferOutOrig = money(nz(row.transferOutOrig()));
        // v1.30 · 补录本金:账户第一期(没有上期余额)不认 —— 第一期的余额已经整笔算开账基线,再认一次就重复了
        BigDecimal principalAdjOrig = row.previousEndBalance() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN) : money(nz(row.principalAdjOrig()));
        BigDecimal periodPnlOrig = PnlCalculator.periodPnl(
                row.endBalance(), row.previousEndBalance(), incomeOrig, expenseOrig, transferInOrig, transferOutOrig,
                principalAdjOrig);
        BigDecimal periodPnlBase = PnlCalculator.toBase(periodPnlOrig, fx);

        return new AccountPeriodFact(
                row.accountId(),
                row.accountName(),
                type,
                classOf(type),
                liquidity,
                row.accountCurrency(),
                row.ownerId(),
                row.displayOrder(),
                row.periodId(),
                row.periodStart(),
                row.periodEnd(),
                moneyOrNull(row.previousEndBalance()),
                moneyOrNull(row.endBalance()),
                PnlCalculator.toBase(row.previousEndBalance(), fx),
                PnlCalculator.toBase(row.endBalance(), fx),
                incomeOrig,
                PnlCalculator.toBase(incomeOrig, fx),
                expenseOrig,
                PnlCalculator.toBase(expenseOrig, fx),
                transferInOrig,
                PnlCalculator.toBase(transferInOrig, fx),
                transferOutOrig,
                PnlCalculator.toBase(transferOutOrig, fx),
                periodPnlOrig,
                periodPnlBase,
                fx.setScale(6, RoundingMode.HALF_EVEN),
                principalAdjOrig,
                PnlCalculator.toBase(principalAdjOrig, fx)
        );
    }

    public static AccountClass classOf(AccountType type) {
        return type.isLiability() ? AccountClass.LIABILITY : AccountClass.ASSET;
    }

    /**
     * 按 AccountType 兜底判定 · 老路径 · 供未指定 product_category 的账户使用。
     * 货币基金 / 银行理财 / 私募 等精细分级见 {@link #liquidityOf(AccountType, String)}。
     */
    public static AccountLiquidity liquidityOf(AccountType type) {
        return switch (type) {
            case CASH -> AccountLiquidity.LIQUID;
            case WEALTH, STOCK, CRYPTO, METAL, INSURANCE, FUND -> AccountLiquidity.SEMI_LIQUID;   // v1.30 FUND:赎回 T+1~T+3,同理财
            case PROPERTY -> AccountLiquidity.ILLIQUID;
            case LOAN, OTHER -> AccountLiquidity.NA;
        };
    }

    /**
     * 精细化判定(v0.3.3 引入)· 优先 product_category.liquidity_class · NULL/非法 fallback type。
     *
     * <p>核心场景:WEALTH 账户内 product=MONEY_FUND(余额宝)· 实际 T+0 赎回 = LIQUID,
     * 而旧逻辑按 type 一律 SEMI_LIQUID,导致流动资产被低估 → 应急月数虚低 → AI 误报。</p>
     */
    public static AccountLiquidity liquidityOf(AccountType type, String pcLiquidityClass) {
        if (pcLiquidityClass != null && !pcLiquidityClass.isBlank()) {
            try {
                return AccountLiquidity.valueOf(pcLiquidityClass.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 数据脏 · 落地到 type 兜底,不抛
            }
        }
        return liquidityOf(type);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal moneyOrNull(BigDecimal value) {
        return value == null ? null : money(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
