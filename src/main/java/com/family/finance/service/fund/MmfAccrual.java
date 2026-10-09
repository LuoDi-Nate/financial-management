package com.family.finance.service.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * v1.30 · 货币基金逐日结转(纯函数 · PRD FR-964 · tech-design/v1.30.md 选型六)。
 *
 * <p>给定「已结转到哪一天」和这一天之后的逐日万份收益,逐日算:
 * {@code 当天收益 = round(份额 × 万份收益 ÷ 10000, 2)},{@code 份额 += 当天收益}(复利,和平台逐日到账一致)。</p>
 *
 * <p>三个约定(写死在这里,改要改 TDD):</p>
 * <ol>
 *   <li><b>舍入</b>:每天四舍五入到分。平台的具体规则没有公开文档可查(未核实);偏差每天最多半分,
 *       由月底的「手动校正」吸收,时间线上看得见。</li>
 *   <li><b>只结连续的日子</b>:万份收益每个自然日都有一条(周末、长假都有)。中间缺一天 → 停在缺口前一天,
 *       不跳过去结 —— 跳过去就是少算了一天还看不出来。</li>
 *   <li><b>补齐上限</b> {@link #MAX_DAYS} 天:断这么久,App 里多半已有申赎,继续算只会离真值更远 → 不结,请用户核对。</li>
 * </ol>
 */
public final class MmfAccrual {

    private MmfAccrual() {}

    public static final int MAX_DAYS = 120;

    public record Day(LocalDate date, BigDecimal per10k) {}

    /**
     * @param sharesAfter   结转后的份额(= 金额)
     * @param accruedTo     结转到了哪一天(没结任何一天时 = 原来的 accruedThrough)
     * @param days          实际结了几天
     * @param gapAfter      在哪一天之后出现了缺口(没有缺口为 null)
     */
    public record Result(BigDecimal sharesBefore, BigDecimal sharesAfter, LocalDate accruedFrom, LocalDate accruedTo,
                         int days, LocalDate gapAfter, String error) {
        public BigDecimal delta() { return sharesAfter.subtract(sharesBefore); }
        public boolean changed() { return days > 0; }
    }

    /**
     * @param shares          当前份额(= 金额)
     * @param accruedThrough  已结转到哪一天(这一天及以前的收益已含在份额里)
     * @param income          (accruedThrough, …] 的逐日万份收益;顺序不限,本方法会按日期排
     */
    public static Result accrue(BigDecimal shares, LocalDate accruedThrough, List<Day> income) {
        BigDecimal start = shares == null ? BigDecimal.ZERO : shares;
        if (accruedThrough == null) {
            return new Result(start, start, null, null, 0, null, "不知道收益结转到了哪一天");
        }
        List<Day> sorted = income.stream()
                .filter(d -> d.date() != null && d.date().isAfter(accruedThrough) && d.per10k() != null)
                .sorted(java.util.Comparator.comparing(Day::date))
                .toList();
        if (!sorted.isEmpty()
                && java.time.temporal.ChronoUnit.DAYS.between(accruedThrough, sorted.get(sorted.size() - 1).date()) > MAX_DAYS) {
            return new Result(start, start, null, accruedThrough, 0, null,
                    "超过 " + MAX_DAYS + " 天没同步,请先核对金额");
        }
        BigDecimal s = start;
        LocalDate expected = accruedThrough.plusDays(1);
        LocalDate last = accruedThrough;
        LocalDate gap = null;
        int n = 0;
        for (Day d : sorted) {
            if (d.date().isBefore(expected)) continue;           // 同一天重复的数据,只认第一条
            if (!d.date().equals(expected)) { gap = last; break; }   // 缺了一天:停在缺口前
            BigDecimal inc = s.multiply(d.per10k()).divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
            s = s.add(inc);
            last = d.date();
            expected = last.plusDays(1);
            n++;
        }
        return new Result(start, s, n > 0 ? accruedThrough.plusDays(1) : null, last, n, gap,
                gap == null ? null : (gap.plusDays(1) + " 的收益数据缺失,结转停在 " + gap));
    }
}
