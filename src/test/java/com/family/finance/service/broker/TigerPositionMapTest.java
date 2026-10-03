package com.family.finance.service.broker;

import com.family.finance.domain.stock.InstrumentKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.29 · 老虎持仓(v0.15 起一直没接上,关联后只有现金)。
 *
 * <p>没有真实老虎账号跑过 —— 应答形状照 SDK 自带的 {@code PositionsItem}({@code items} → positions)
 * 与 {@code PositionDetail} 的字段;解析走的是 SDK 自己用的 fastjson 映射,不是我们手写的。</p>
 */
class TigerPositionMapTest {

    static final String DATA = """
            {"items":[
              {"account":"U0001","secType":"STK","market":"US","currency":"USD","symbol":"AAPL","identifier":"AAPL",
               "positionQty":10.0,"position":10,"positionScale":0,"averageCost":170.5,"latestPrice":228.1,"marketValue":2281.0,"multiplier":1.0},
              {"account":"U0001","secType":"STK","market":"HK","currency":"HKD","symbol":"00700","identifier":"00700",
               "positionQty":200.0,"latestPrice":512.0,"marketValue":102400.0,"multiplier":1.0},
              {"account":"U0001","secType":"OPT","market":"US","currency":"USD","symbol":"AAPL","identifier":"AAPL  261218C00250000",
               "expiry":"20261218","strike":"250.0","right":"CALL","multiplier":100.0,
               "positionQty":-2.0,"latestPrice":9.8,"marketValue":-1960.0},
              {"account":"U0001","secType":"OPT","market":"US","currency":"USD","symbol":"TSLA","identifier":"TSLA  261120C00300000",
               "expiry":"20261120","strike":"300.0","right":"CALL","multiplier":100.0,
               "positionQty":1.0,"latestPrice":12.4,"marketValue":12.4},
              {"account":"U0001","secType":"FUT","market":"US","currency":"USD","symbol":"ES","identifier":"ESmain",
               "expiry":"20261218","multiplier":50.0,"positionQty":1.0,"latestPrice":6000.0,"marketValue":300000.0},
              {"account":"U0001","secType":"CASH","market":"US","currency":"USD","symbol":"USD.HKD","positionQty":100.0}
            ]}
            """;

    @Test
    void 应答用SDK自带的结构解析() {
        assertThat(TigerBrokerClient.parse(DATA)).hasSize(6);
        assertThat(TigerBrokerClient.parse("")).isEmpty();
    }

    @Test
    void 股票_港股五位代码原样() {
        var rows = TigerBrokerClient.parse(DATA);
        var aapl = TigerBrokerClient.map(rows.get(0)).stock();
        assertThat(aapl.ticker()).isEqualTo("AAPL");
        assertThat(aapl.shares()).isEqualByComparingTo("10");
        assertThat(aapl.costPrice()).isEqualByComparingTo("170.5");
        assertThat(TigerBrokerClient.map(rows.get(1)).stock().ticker()).isEqualTo("00700");
    }

    @Test
    void 卖出的期权_市值为负_核对大小() {
        var d = TigerBrokerClient.map(TigerBrokerClient.parse(DATA).get(2)).derivative();
        assertThat(d.kind()).isEqualTo(InstrumentKind.OPTION);
        assertThat(d.symbol()).isEqualTo("AAPL  261218C00250000");
        assertThat(d.quantity()).isEqualByComparingTo("-2");
        assertThat(d.marketValue()).isEqualByComparingTo("-1960");
        assertThat(d.expiry()).isEqualTo(LocalDate.of(2026, 12, 18));
        assertThat(d.strike()).isEqualByComparingTo("250");
    }

    @Test
    void 市值对不上的期权不同步_期货记0_外汇头寸跳过() {
        var rows = TigerBrokerClient.parse(DATA);
        assertThat(TigerBrokerClient.map(rows.get(3)).rejected()).contains("TSLA").contains("对不上");
        var fut = TigerBrokerClient.map(rows.get(4)).derivative();
        assertThat(fut.kind()).isEqualTo(InstrumentKind.FUTURE);
        assertThat(fut.marketValue()).isEqualByComparingTo("0");
        assertThat(fut.notional()).isEqualByComparingTo("300000");
        assertThat(TigerBrokerClient.map(rows.get(5)).skipped()).isTrue();
    }

    @Test
    void 按品种各查一次_股票打头() {
        assertThat(TigerBrokerClient.POSITION_TYPES.get(0).name()).isEqualTo("STK");
        assertThat(TigerBrokerClient.POSITION_TYPES).extracting(Enum::name).contains("OPT", "FUT", "WAR");
    }
}
