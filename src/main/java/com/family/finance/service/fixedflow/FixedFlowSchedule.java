package com.family.finance.service.fixedflow;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.TreeSet;

/**
 * v1.32 · 一条固定收支在某一期里「哪天该有」—— 纯函数(PRD FR-1100 / FR-1140)。
 *
 * <p>账期不一定是自然月(有按周的家庭),所以不按「这一期是几月」算,而是:
 * 把这一期跨到的每个自然月里「几号」那天找出来,落在 {@code [期初, 期末]} 里的那天就是。
 * 月份没有 31 号就取当月最后一天(2 月设 30 号 = 2 月最后一天)。</p>
 *
 * <p>一期最多一个日子:月度期正好一个,周度期 0 或 1 个(同一个「几号」一个月只有一天)。
 * 表上的唯一键 {@code (fixed_flow_id, period_id)} 靠的就是这一条。</p>
 */
public final class FixedFlowSchedule {

    private FixedFlowSchedule() {
    }

    public static final String MONTHLY = "MONTHLY";
    public static final String YEARLY = "YEARLY";

    /**
     * @param cycle      {@link #MONTHLY} / {@link #YEARLY}
     * @param months     每年的哪几个月(YEARLY 才用;"3" 或 "3,9")
     * @param day        几号(1–31)
     * @param startMonth 从哪个月开始(含);null = 不限
     * @param endMonth   到哪个月为止(含);null = 不限
     * @return 这一期里该有的那天;这一期没有 → null
     */
    public static LocalDate occurrenceIn(String cycle, String months, int day,
                                         YearMonth startMonth, YearMonth endMonth,
                                         LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) return null;
        Set<Integer> allowed = YEARLY.equals(cycle) ? parseMonths(months) : null;
        if (allowed != null && allowed.isEmpty()) return null;
        int d = Math.max(1, Math.min(31, day));
        for (YearMonth ym = YearMonth.from(periodStart); !ym.isAfter(YearMonth.from(periodEnd)); ym = ym.plusMonths(1)) {
            if (startMonth != null && ym.isBefore(startMonth)) continue;
            if (endMonth != null && ym.isAfter(endMonth)) continue;
            if (allowed != null && !allowed.contains(ym.getMonthValue())) continue;
            LocalDate date = ym.atDay(Math.min(d, ym.lengthOfMonth()));
            if (!date.isBefore(periodStart) && !date.isAfter(periodEnd)) return date;
        }
        return null;
    }

    /** 到日子了没有:当天起算(FR-1140);这一期已经过完的,全都算到了。 */
    public static boolean isDue(LocalDate date, LocalDate today) {
        return date != null && !date.isAfter(today);
    }

    /** "3,9" → {3, 9};认不出的数字丢掉。 */
    public static Set<Integer> parseMonths(String months) {
        Set<Integer> out = new TreeSet<>();
        if (months == null) return out;
        for (String s : months.split("[,，\\s]+")) {
            try {
                int m = Integer.parseInt(s.trim());
                if (m >= 1 && m <= 12) out.add(m);
            } catch (NumberFormatException ignored) {
                // 不认识的就跳过
            }
        }
        return out;
    }

    /** {3, 9} → "3,9"(落库形态) */
    public static String formatMonths(Set<Integer> months) {
        StringBuilder sb = new StringBuilder();
        for (Integer m : new TreeSet<>(months)) {
            if (m == null || m < 1 || m > 12) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(m);
        }
        return sb.toString();
    }

    /** 从 {@code from} 那个月起(含)到 {@code endMonth}(含)还有几次;没有终点 → -1。 */
    public static int remainingCount(String cycle, String months, YearMonth from, YearMonth endMonth) {
        if (endMonth == null) return -1;
        Set<Integer> allowed = YEARLY.equals(cycle) ? parseMonths(months) : null;
        int n = 0;
        for (YearMonth ym = from; !ym.isAfter(endMonth); ym = ym.plusMonths(1)) {
            if (allowed == null || allowed.contains(ym.getMonthValue())) n++;
        }
        return n;
    }
}
