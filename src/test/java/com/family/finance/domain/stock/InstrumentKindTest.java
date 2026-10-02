package com.family.finance.domain.stock;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.29 · 期权这一行写人话(PRD FR-946 的文案) */
class InstrumentKindTest {

    @Test
    void 期权标题_标的_看涨看跌_行权价_到期日() {
        assertThat(InstrumentKind.title(InstrumentKind.OPTION, "AAPL", "AAPL  261218C00250000", "C",
                new BigDecimal("250.000"), LocalDate.of(2026, 12, 18), null))
                .isEqualTo("AAPL · 看涨 · 行权价 250 · 2026-12-18 到期");
        assertThat(InstrumentKind.title(InstrumentKind.OPTION, "SPY", "x", "PUT",
                new BigDecimal("552.5"), LocalDate.of(2026, 10, 9), null))
                .isEqualTo("SPY · 看跌 · 行权价 552.5 · 2026-10-09 到期");
        // 权证叫认购 / 认沽
        assertThat(InstrumentKind.title(InstrumentKind.WARRANT, "00700", "x", "C", null, null, null))
                .isEqualTo("00700 · 认购");
    }

    @Test
    void 缺哪段省哪段_不编() {
        assertThat(InstrumentKind.title(InstrumentKind.OPTION, null, "AAPL  261218C00250000", null, null, null, null))
                .isEqualTo("AAPL  261218C00250000 · 期权");
        assertThat(InstrumentKind.title(InstrumentKind.FUTURE, "ES", "ESZ6", null, null, LocalDate.of(2026, 12, 18), null))
                .isEqualTo("ES · 期货 · 2026-12-18 到期");
        assertThat(InstrumentKind.title(InstrumentKind.BOND, null, "T 4 1/4 11/15/34", null, null, null, "T 4 1/4 11/15/34"))
                .isEqualTo("T 4 1/4 11/15/34 · 债券");
    }

    @Test
    void 张数_买入卖出() {
        assertThat(InstrumentKind.sideText(InstrumentKind.OPTION, new BigDecimal("1.00000000"))).isEqualTo("买入 1 张");
        assertThat(InstrumentKind.sideText(InstrumentKind.OPTION, new BigDecimal("-2"))).isEqualTo("卖出 2 张");
        assertThat(InstrumentKind.sideText(InstrumentKind.BOND, new BigDecimal("10000"))).isEqualTo("持有面值 10000");
    }

    @Test
    void 到期提示_7天内_今天_已到期() {
        LocalDate today = LocalDate.of(2026, 10, 3);
        assertThat(InstrumentKind.expiryBadge(today.plusDays(8), today)).isNull();
        assertThat(InstrumentKind.expiryBadge(today.plusDays(7), today)).isEqualTo("7 天内到期");
        assertThat(InstrumentKind.expiryBadge(today.plusDays(1), today)).isEqualTo("7 天内到期");
        assertThat(InstrumentKind.expiryBadge(today, today)).isEqualTo("今天到期");
        assertThat(InstrumentKind.expiryBadge(today.minusDays(1), today)).startsWith("已到期");
        assertThat(InstrumentKind.expiryBadge(null, today)).isNull();
    }

    @Test
    void 只有期货不计入余额() {
        assertThat(InstrumentKind.FUTURE.countsInBalance()).isFalse();
        assertThat(InstrumentKind.OPTION.countsInBalance()).isTrue();
        assertThat(InstrumentKind.BOND.countsInBalance()).isTrue();
        assertThat(InstrumentKind.of("option")).isEqualTo(InstrumentKind.OPTION);
        assertThat(InstrumentKind.of(null)).isNull();
        assertThat(InstrumentKind.of("CFD")).isNull();
    }
}
