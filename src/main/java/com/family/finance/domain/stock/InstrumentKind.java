package com.family.finance.domain.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 券商同步来的「不是股票」的持仓 · v1.29(issue #26)。
 *
 * <p>它们在库里都是 {@link ValuationMode#MANUAL} 行(单价 × 张数,<b>卖出张数为负</b>),
 * 估值路径与手填持仓同一条 —— 这个枚举只决定<b>怎么把这一行写成人话</b>,以及期货<b>不计入余额</b>。</p>
 *
 * <ul>
 *   <li>{@link #OPTION} 期权(含期货期权):按券商给的持仓市值记,已含乘数;卖出为负。</li>
 *   <li>{@link #WARRANT} 权证:同期权。</li>
 *   <li>{@link #FUTURE} 期货:<b>记 0</b>,只展示名义价值 —— 盈亏每天结算进现金,现金里已经有了;
 *       按名义价值记会把一张几千块保证金的合约算成几十万。</li>
 *   <li>{@link #BOND} 债券:按券商给的持仓市值记(不含应计利息 —— 现金的应计利息我们也不记,口径一致)。</li>
 * </ul>
 */
public enum InstrumentKind {
    OPTION("期权"),
    WARRANT("权证"),
    FUTURE("期货"),
    BOND("债券");

    private final String label;

    InstrumentKind(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 期货不计入余额(见类注释) */
    public boolean countsInBalance() { return this != FUTURE; }

    /** 按「张」数的品种(期权 / 权证 / 期货);债券按面值 */
    public boolean countsContracts() { return this != BOND; }

    /** 库里的字符串 → 枚举;空 / 不认识返回 null(= 普通持仓) */
    public static InstrumentKind of(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** 7 天内到期就标出来(PRD FR-946) */
    public static final int EXPIRY_WARN_DAYS = 7;

    /**
     * 持仓页上这一行的人话标题。例:
     * <ul>
     *   <li>期权:{@code AAPL · 看涨 · 行权价 250 · 2026-12-18 到期}</li>
     *   <li>期货:{@code ES · 期货 · 2026-12-18 到期}</li>
     *   <li>债券:{@code T 4 1/4 11/15/34 · 债券}</li>
     * </ul>
     * 缺哪一段就省哪一段,不编。
     */
    public static String title(InstrumentKind kind, String underlying, String symbol, String putCall,
                               BigDecimal strike, LocalDate expiry, String description) {
        if (kind == null) return symbol;
        String base = firstNonBlank(underlying, kind == BOND ? description : null, symbol, kind.label);
        StringBuilder sb = new StringBuilder(base.trim());
        switch (kind) {
            case OPTION, WARRANT -> {
                String pc = putCallLabel(putCall, kind);
                sb.append(" · ").append(pc != null ? pc : kind.label);
                if (strike != null) sb.append(" · 行权价 ").append(plain(strike));
            }
            case FUTURE -> sb.append(" · 期货");
            case BOND -> sb.append(" · 债券");
        }
        if (expiry != null) sb.append(" · ").append(expiry).append(" 到期");
        String s = sb.toString();
        return s.length() > 64 ? s.substring(0, 63) + "…" : s;   // display_name 是 VARCHAR(64)
    }

    /** C/P → 看涨 / 看跌(权证叫认购 / 认沽);不认识返回 null */
    public static String putCallLabel(String putCall, InstrumentKind kind) {
        if (putCall == null || putCall.isBlank()) return null;
        String p = putCall.trim().toUpperCase(Locale.ROOT);
        boolean call = p.equals("C") || p.equals("CALL");
        boolean put = p.equals("P") || p.equals("PUT");
        if (!call && !put) return null;
        if (kind == WARRANT) return call ? "认购" : "认沽";
        return call ? "看涨" : "看跌";
    }

    /** 「买入 1 张」/「卖出 2 张」;债券「持有面值 10,000」 */
    public static String sideText(InstrumentKind kind, BigDecimal qty) {
        if (qty == null) return "";
        boolean shortSide = qty.signum() < 0;
        String n = plain(qty.abs());
        if (kind == BOND) return (shortSide ? "卖出面值 " : "持有面值 ") + n;
        return (shortSide ? "卖出 " : "买入 ") + n + " 张";
    }

    /**
     * 到期提示:已过期 / 今天到期 / N 天内到期(≤ 7 天);其余返回 null(不标)。
     * 已过期的行下次同步会被归档(券商报表里不再有它)。
     */
    public static String expiryBadge(LocalDate expiry, LocalDate today) {
        if (expiry == null || today == null) return null;
        long d = ChronoUnit.DAYS.between(today, expiry);
        if (d < 0) return "已到期 · 下次同步移除";
        if (d == 0) return "今天到期";
        if (d <= EXPIRY_WARN_DAYS) return EXPIRY_WARN_DAYS + " 天内到期";
        return null;
    }

    static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString();
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) if (x != null && !x.isBlank()) return x;
        return "";
    }
}
