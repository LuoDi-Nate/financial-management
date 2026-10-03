package com.family.finance.service.broker;

import com.family.finance.domain.stock.InstrumentKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.29 · 期权这一行能不能信(PRD FR-950 · pre-mortem ①:市值差 100 倍或符号反了)。
 * 静默算错不报错是最贵的失败 —— 这里守「对不上就不同步」。
 */
class DerivativeRowsTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void 真实形状的一买一卖_都能用() {
        // 2026-10-02 @Jsonya 贴的形状(数是编的):position × markPrice × 100 = positionValue,卖出为负
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("1"), d("28.5419"), d("100"), d("2854.19"))).isNull();
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("-1"), d("16.1751"), d("100"), d("-1617.51"))).isNull();
    }

    @Test
    void 市值没乘乘数_或符号反了_都拒() {
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("2"), d("5"), d("100"), d("10")))
                .contains("对不上");
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("-1"), d("16.2"), d("100"), d("1620")))
                .contains("对不上");
    }

    @Test
    void 缺字段_拒_并说缺什么() {
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("1"), d("2"), d("100"), null)).contains("缺持仓市值");
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("1"), null, d("100"), d("200"))).contains("缺标记价");
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, d("1"), d("2"), null, d("200"))).contains("缺乘数");
        assertThat(DerivativeRows.check(InstrumentKind.OPTION, null, d("2"), d("100"), d("200"))).contains("缺张数");
    }

    @Test
    void 期货记0_数不全也不影响余额_照样同步() {
        assertThat(DerivativeRows.check(InstrumentKind.FUTURE, d("1"), null, null, null)).isNull();
    }

    @Test
    void 债券标记价按面值百分比给_也认() {
        assertThat(DerivativeRows.check(InstrumentKind.BOND, d("10000"), d("98.5"), d("1"), d("9850"))).isNull();
        assertThat(DerivativeRows.check(InstrumentKind.BOND, d("10000"), d("98.5"), d("0.01"), d("9850"))).isNull();
        assertThat(DerivativeRows.check(InstrumentKind.BOND, d("10000"), d("98.5"), d("1"), d("5000"))).contains("对不上");
    }

    @Test
    void 结果摘要最多点名两行() {
        String one = DerivativeRows.rejectLine(InstrumentKind.OPTION, "TSLA", "x", "C", d("300"),
                LocalDate.of(2026, 11, 20), null, "报表里缺持仓市值");
        assertThat(one).isEqualTo("TSLA · 看涨 · 行权价 300 · 2026-11-20 到期:报表里缺持仓市值,这一行没有同步");
        assertThat(DerivativeRows.rejectSummary(List.of(one)))
                .isEqualTo(" · 1 行数据对不上没同步(TSLA · 看涨 · 行权价 300 · 2026-11-20 到期:报表里缺持仓市值)");
        assertThat(DerivativeRows.rejectSummary(List.of(one, one, one))).startsWith(" · 3 行").endsWith(" 等)");
        assertThat(DerivativeRows.rejectSummary(List.of())).isEmpty();
    }

    @Test
    void 到期日三种写法() {
        assertThat(DerivativeRows.parseDate("20270416")).isEqualTo(LocalDate.of(2027, 4, 16));
        assertThat(DerivativeRows.parseDate("2027-04-16")).isEqualTo(LocalDate.of(2027, 4, 16));
        assertThat(DerivativeRows.parseDate("04/16/2027")).isEqualTo(LocalDate.of(2027, 4, 16));
        assertThat(DerivativeRows.parseDate("20270416;000000")).isEqualTo(LocalDate.of(2027, 4, 16));
        assertThat(DerivativeRows.parseDate("下周五")).isNull();
        assertThat(DerivativeRows.parseDate(null)).isNull();
    }
}
