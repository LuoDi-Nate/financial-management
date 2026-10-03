package com.family.finance.service.broker;

import com.family.finance.domain.stock.InstrumentKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * v1.29 · 三家券商共用的「期权这一行能不能信」· 纯函数(issue #26 · PRD FR-950)。
 *
 * <p>为什么要核对:持仓市值如果<b>没乘乘数</b>(差 100 倍)或<b>卖出时不是负数</b>(符号反了),
 * 账户余额会错得很离谱,而且<b>不报错</b> —— 这是本版最贵的那种失败。所以券商给了市值,
 * 我们还要用「张数 × 标记价 × 乘数」再算一遍,对不上就<b>这一行不同步、照实列出来</b>,不拿猜的数顶上。</p>
 */
public final class DerivativeRows {
    private DerivativeRows() {}

    /** 允许的误差:1 个货币单位或 1%(标记价在报表里是四舍五入过的) */
    static final BigDecimal ABS_TOL = BigDecimal.ONE;
    static final BigDecimal REL_TOL = new BigDecimal("0.01");

    /**
     * 期权 / 权证 / 债券:核对「张数 × 标记价 × 乘数 ≈ 持仓市值」。
     *
     * @return null = 可以用;否则是没同步的原因(人话,接在合约名后面)
     */
    public static String check(InstrumentKind kind, BigDecimal qty, BigDecimal mark,
                               BigDecimal multiplier, BigDecimal value) {
        if (qty == null || qty.signum() == 0) return "报表里缺张数";
        if (kind == InstrumentKind.FUTURE) return null;               // 期货记 0,数不全也不影响余额
        if (value == null) return "报表里缺持仓市值";
        if (mark == null) return "报表里缺标记价,核对不了";
        if (multiplier == null || multiplier.signum() <= 0) return "报表里缺乘数,核对不了";
        BigDecimal expected = qty.multiply(mark).multiply(multiplier);
        if (close(expected, value)) return null;
        // 债券的标记价常按「面值的百分比」给(99.5 = 面值的 99.5%)→ 再按 /100 核一次
        if (kind == InstrumentKind.BOND
                && close(expected.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN), value)) return null;
        return "张数 × 标记价 × 乘数 与持仓市值对不上";
    }

    static boolean close(BigDecimal expected, BigDecimal value) {
        BigDecimal diff = expected.subtract(value).abs();
        BigDecimal tol = value.abs().multiply(REL_TOL).max(ABS_TOL);
        return diff.compareTo(tol) <= 0;
    }

    /** 没同步的那一行怎么说:「TSLA · 看涨 · 行权价 300 · 2026-11-20 到期:报表里缺持仓市值,这一行没有同步」 */
    public static String rejectLine(InstrumentKind kind, String underlying, String symbol, String putCall,
                                    BigDecimal strike, LocalDate expiry, String description, String reason) {
        return InstrumentKind.title(kind, underlying, symbol, putCall, strike, expiry, description)
                + ":" + reason + ",这一行没有同步";
    }

    /** 同步结果里最多点名几行,其余说「等 N 行」(last_status 只有 255 字) */
    static final int REJECT_NAMED = 2;

    static String rejectSummary(List<String> rejected) {
        if (rejected == null || rejected.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" · ").append(rejected.size()).append(" 行数据对不上没同步(");
        for (int i = 0; i < Math.min(REJECT_NAMED, rejected.size()); i++) {
            if (i > 0) sb.append(";");
            String r = rejected.get(i);
            int cut = r.indexOf(',');            // 只要「哪一张:为什么」,后半句在这里是废话
            sb.append(cut > 0 ? r.substring(0, cut) : r);
        }
        if (rejected.size() > REJECT_NAMED) sb.append(" 等");
        return sb.append(")").toString();
    }

    /** 券商的到期日:20270416 / 2027-04-16 / 04/16/2027;认不出返回 null(不编) */
    public static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > 10 && t.contains(";")) t = t.substring(0, t.indexOf(';'));   // 20270416;000000
        for (DateTimeFormatter f : new DateTimeFormatter[]{
                DateTimeFormatter.BASIC_ISO_DATE, DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("MM/dd/yyyy")}) {
            try { return LocalDate.parse(t, f); } catch (DateTimeParseException ignored) { }
        }
        return null;
    }
}
