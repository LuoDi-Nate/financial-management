package com.family.finance.service.broker;

import com.family.finance.domain.stock.InstrumentKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.29 · 富途一笔持仓归到哪儿(issue #26 顺带修的两个洞)。
 *
 * <p>没有真实富途期权账户跑过 —— 这里按 OpenD 的 Position 字段(code / qty / price / val / positionSide /
 * secMarket)核对归类与符号。富途文档没写清 val 含不含乘数,所以美股期权用「val ÷(张数 × 价格)≈ 100」核一遍。</p>
 */
class FutuPositionMapTest {

    static final int LONG = 0, SHORT = 1, HK = 1, US = 2, SH = 31;

    @Test
    void 买入的美股期权_不再当股票_按富途市值记() {
        var m = FutuBrokerClient.map("AAPL260116C250000", "AAPL 260116 250.00C", 1, 9.8, 980, 7.1, US, LONG);
        assertThat(m.stock()).isNull();
        var d = m.derivative();
        assertThat(d.kind()).isEqualTo(InstrumentKind.OPTION);
        assertThat(d.symbol()).isEqualTo("AAPL260116C250000");   // 17 个字符:v1.28 的 16 字符列放不下,整次同步失败
        assertThat(d.underlying()).isEqualTo("AAPL");
        assertThat(d.putCall()).isEqualTo("C");
        assertThat(d.strike()).isEqualByComparingTo("250");
        assertThat(d.expiry()).isEqualTo(LocalDate.of(2026, 1, 16));
        assertThat(d.multiplier()).isEqualByComparingTo("100");
        assertThat(d.quantity()).isEqualByComparingTo("1");
        assertThat(d.marketValue()).isEqualByComparingTo("980");
        assertThat(d.currency()).isEqualTo("USD");
    }

    @Test
    void 卖出的期权_以前跳过_现在记负数() {
        // 不管富途给的 qty / val 带不带负号,方向一律跟 positionSide 走
        var a = FutuBrokerClient.map("SPY261009P560000", "SPY", 2, 1.7, 340, 2.05, US, SHORT).derivative();
        var b = FutuBrokerClient.map("SPY261009P560000", "SPY", -2, 1.7, -340, 2.05, US, SHORT).derivative();
        for (var d : new BrokerDtos.Derivative[]{a, b}) {
            assertThat(d.quantity()).isEqualByComparingTo("-2");
            assertThat(d.marketValue()).isEqualByComparingTo("-340");
            assertThat(d.strike()).isEqualByComparingTo("560");
            assertThat(d.putCall()).isEqualTo("P");
        }
    }

    @Test
    void 美股期权市值不像含了100倍乘数_不同步_照实说() {
        var m = FutuBrokerClient.map("AAPL260116C250000", "x", 1, 9.8, 9.8, 0, US, LONG);
        assertThat(m.derivative()).isNull();
        assertThat(m.rejected()).contains("AAPL · 看涨 · 行权价 250 · 2026-01-16 到期").contains("100 倍乘数");
    }

    @Test
    void 港股期权_乘数按市值反推() {
        var d = FutuBrokerClient.map("TCH250328C400000", "腾讯 250328 400.00 购", 2, 3.5, 3500, 0, HK, LONG).derivative();
        assertThat(d.underlying()).isEqualTo("TCH");
        assertThat(d.multiplier()).isEqualByComparingTo("500");
        assertThat(d.currency()).isEqualTo("HKD");
    }

    @Test
    void 股票照旧_融券卖出的记负股数() {
        var l = FutuBrokerClient.map("AAPL", "苹果", 10, 228, 2280, 170, US, LONG).stock();
        assertThat(l.ticker()).isEqualTo("AAPL");
        assertThat(l.shares()).isEqualByComparingTo("10");
        var s = FutuBrokerClient.map("TSLA", "特斯拉", 5, 250, 1250, 260, US, SHORT).stock();
        assertThat(s.shares()).isEqualByComparingTo("-5");
        // 港股 / A 股数字代码不会被当成期权
        assertThat(FutuBrokerClient.map("00700", "腾讯", 100, 500, 50000, 0, HK, LONG).stock()).isNotNull();
        assertThat(FutuBrokerClient.map("600519", "茅台", 10, 1500, 15000, 0, SH, LONG).stock().market()).isEqualTo("CN");
    }

    @Test
    void 零股与未支持市场() {
        assertThat(FutuBrokerClient.map("AAPL", "x", 0, 1, 0, 0, US, LONG)).isNull();
        assertThat(FutuBrokerClient.map("XYZ", "x", 1, 1, 1, 0, 99, LONG).skipped()).isTrue();
    }
}
