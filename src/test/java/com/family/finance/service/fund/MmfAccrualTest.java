package com.family.finance.service.fund;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.30 · 货币基金逐日结转(护栏 v130-MMF-ACCRUE-ONCE)。
 * 万份收益取 2026-10-01 ~ 10-07 余额宝的实测值(0.2253 × 4 天、0.2252 × 3 天)。
 */
class MmfAccrualTest {

    private static final LocalDate SEP30 = LocalDate.of(2026, 9, 30);

    private static List<MmfAccrual.Day> octFirstWeek() {
        List<MmfAccrual.Day> d = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            d.add(new MmfAccrual.Day(LocalDate.of(2026, 10, i), new BigDecimal(i <= 4 ? "0.2253" : "0.2252")));
        }
        return d;
    }

    @Test
    void 逐日复利_每天四舍五入到分() {
        // 12000 × 0.2253 / 10000 = 0.27036 → 0.27;七天都是 0.27 → +1.89
        var r = MmfAccrual.accrue(new BigDecimal("12000.00"), SEP30, octFirstWeek());
        assertThat(r.days()).isEqualTo(7);
        assertThat(r.sharesAfter()).isEqualByComparingTo("12001.89");
        assertThat(r.delta()).isEqualByComparingTo("1.89");
        assertThat(r.accruedFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(r.accruedTo()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(r.error()).isNull();
    }

    @Test
    void 大额时复利生效() {
        // 1,000,000 × 0.2253/10000 = 22.53;第二天按 1,000,022.53 算 = 22.5305 → 22.53 …… 每天都要按新份额算
        var r = MmfAccrual.accrue(new BigDecimal("1000000"), SEP30, octFirstWeek().subList(0, 2));
        assertThat(r.sharesAfter()).isEqualByComparingTo("1000045.06");
    }

    @Test
    void 同一天跑三次只结一次() {
        var first = MmfAccrual.accrue(new BigDecimal("12000.00"), SEP30, octFirstWeek());
        var second = MmfAccrual.accrue(first.sharesAfter(), first.accruedTo(), octFirstWeek());
        var third = MmfAccrual.accrue(second.sharesAfter(), second.accruedTo(), octFirstWeek());
        assertThat(second.changed()).isFalse();
        assertThat(third.changed()).isFalse();
        assertThat(third.sharesAfter()).isEqualByComparingTo(first.sharesAfter());
    }

    @Test
    void 断了几天就补几天() {
        var r = MmfAccrual.accrue(new BigDecimal("12000.00"), LocalDate.of(2026, 10, 4), octFirstWeek());
        assertThat(r.days()).isEqualTo(3);
        assertThat(r.accruedFrom()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void 中间缺一天_停在缺口前_不跳过去() {
        List<MmfAccrual.Day> d = new ArrayList<>(octFirstWeek());
        d.removeIf(x -> x.date().getDayOfMonth() == 4);
        var r = MmfAccrual.accrue(new BigDecimal("12000.00"), SEP30, d);
        assertThat(r.days()).isEqualTo(3);
        assertThat(r.accruedTo()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(r.gapAfter()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(r.error()).contains("缺失");
    }

    @Test
    void 超过120天不结() {
        List<MmfAccrual.Day> d = List.of(new MmfAccrual.Day(SEP30.plusDays(MmfAccrual.MAX_DAYS + 1), new BigDecimal("0.2")));
        var r = MmfAccrual.accrue(new BigDecimal("100"), SEP30, d);
        assertThat(r.changed()).isFalse();
        assertThat(r.error()).contains("120");
    }

    @Test
    void 重复日期只认一条_早于起点的忽略() {
        List<MmfAccrual.Day> d = new ArrayList<>(octFirstWeek());
        d.add(new MmfAccrual.Day(LocalDate.of(2026, 10, 2), new BigDecimal("9.9999")));   // 重复
        d.add(new MmfAccrual.Day(SEP30, new BigDecimal("9.9999")));                      // 已结过
        var r = MmfAccrual.accrue(new BigDecimal("12000.00"), SEP30, d);
        assertThat(r.days()).isEqualTo(7);
        assertThat(r.sharesAfter()).isLessThan(new BigDecimal("12010"));
    }

    @Test
    void 不知道结转到哪天_不结() {
        var r = MmfAccrual.accrue(new BigDecimal("100"), null, octFirstWeek());
        assertThat(r.changed()).isFalse();
        assertThat(r.error()).isNotNull();
    }
}
