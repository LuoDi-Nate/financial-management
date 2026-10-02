package com.family.finance.service.broker.ibkr;

import com.family.finance.service.broker.BrokerDtos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IBKR Flex 报表解析 · v1.26 · issue #24。
 *
 * <p>守的是三件「不报错、只是数字悄悄变错」的事:失败信封被当报表读、现金 / 持仓算两遍、
 * 缺栏目被当成「全卖光了」。</p>
 */
class IbkrFlexParserTest {

    /** 2026-09-24 在 beta 上用假口令实测到的原文(HTTP 200) */
    static final String REAL_1015 = """
            <FlexStatementResponse timestamp='24 September, 2026 09:27 AM EDT'>
            <Status>Fail</Status>
            <ErrorCode>1015</ErrorCode>
            <ErrorMessage>Token is invalid.</ErrorMessage>
            </FlexStatementResponse>
            """;

    static String sample() throws IOException {
        try (var in = IbkrFlexParserTest.class.getResourceAsStream("/ibkr/flex-sample-two-accounts.xml")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 失败信封是HTTP200_必须抛错而不是当回执读() {
        assertThatThrownBy(() -> IbkrFlexParser.parseSendResponse(REAL_1015))
                .isInstanceOf(IbkrFlexException.class)
                .satisfies(e -> {
                    IbkrFlexException ie = (IbkrFlexException) e;
                    assertThat(ie.code()).isEqualTo("1015");
                    assertThat(ie.retryable()).isFalse();
                    // 人话和 IBKR 原话都要在
                    assertThat(ie.getMessage()).contains("报表口令不对").contains("Token is invalid.");
                });
    }

    @Test
    void 失败信封出现在取报表那一步_同样抛错_绝不进对账() {
        assertThatThrownBy(() -> IbkrFlexParser.parseStatement(REAL_1015))
                .isInstanceOf(IbkrFlexException.class);
    }

    @Test
    void 还在生成是可重试的() {
        String env = "<FlexStatementResponse><Status>Warn</Status><ErrorCode>1019</ErrorCode>"
                + "<ErrorMessage>Statement generation in progress. Please try again shortly.</ErrorMessage></FlexStatementResponse>";
        assertThatThrownBy(() -> IbkrFlexParser.parseStatement(env))
                .isInstanceOf(IbkrFlexException.class)
                .satisfies(e -> assertThat(((IbkrFlexException) e).retryable()).isTrue());
    }

    @Test
    void 成功回执给出回执号() {
        String ok = "<FlexStatementResponse timestamp='x'><Status>Success</Status><ReferenceCode>1234567890</ReferenceCode>"
                + "<Url>https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService/GetStatement</Url></FlexStatementResponse>";
        assertThat(IbkrFlexParser.parseSendResponse(ok)).isEqualTo("1234567890");
    }

    @Test
    void 两个账户各自成一份快照() throws IOException {
        var report = IbkrFlexParser.parseStatement(sample());
        assertThat(report.accounts()).containsOnlyKeys("U1234521", "U7658830");
    }

    @Test
    void 持仓_按交易所归一_LOT明细不重复算_期权单列() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(sample()).accounts().get("U1234521").snapshot();
        assertThat(s.positions()).extracting(BrokerDtos.Position::market, BrokerDtos.Position::ticker)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("US", "AAPL"),
                        org.assertj.core.groups.Tuple.tuple("HK", "00700"),     // IBKR 给 700,我们存 00700
                        org.assertj.core.groups.Tuple.tuple("CN", "600519"));   // 沪股通
        // AAPL 只有 SUMMARY 那一行的 50 股,LOT 行的 20 股不能再加一遍
        assertThat(s.positions().get(0).shares()).isEqualByComparingTo("50");
        assertThat(s.positions().get(2).costPrice()).isEqualByComparingTo("1560.00");   // 千分位
        // v1.29 · 期权不再跳过,单列进 derivatives(以前这里是 skippedNonEquity == 1)
        assertThat(s.skippedNonEquity()).isZero();
        assertThat(s.derivatives()).hasSize(1);
        assertThat(s.derivatives().get(0).symbol()).isEqualTo("AAPL  261218C00250000");
    }

    // ───────────── v1.29 · 期权 / 期货 / 债券(issue #26)─────────────

    static String derivSample() throws IOException {
        try (var in = IbkrFlexParserTest.class.getResourceAsStream("/ibkr/flex-sample-derivatives.xml")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BrokerDtos.Derivative deriv(BrokerDtos.Snapshot s, String symbol) {
        return s.derivatives().stream().filter(d -> d.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void 期权_买入为正_卖出为负_市值用报表的持仓市值_已含乘数() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(derivSample()).accounts().get("U5550001").snapshot();
        var longCall = deriv(s, "GOOGL 270416C00360000");
        assertThat(longCall.kind()).isEqualTo(com.family.finance.domain.stock.InstrumentKind.OPTION);
        assertThat(longCall.quantity()).isEqualByComparingTo("1");
        assertThat(longCall.marketValue()).isEqualByComparingTo("2850");
        assertThat(longCall.underlying()).isEqualTo("GOOGL");
        assertThat(longCall.putCall()).isEqualTo("C");
        assertThat(longCall.strike()).isEqualByComparingTo("360");
        assertThat(longCall.expiry()).isEqualTo(java.time.LocalDate.of(2027, 4, 16));
        assertThat(longCall.multiplier()).isEqualByComparingTo("100");
        var shortCall = deriv(s, "GOOGL 270416C00400000");
        assertThat(shortCall.quantity()).isEqualByComparingTo("-1");
        assertThat(shortCall.marketValue()).isEqualByComparingTo("-1620");   // 卖出:负数,照实减
        assertThat(deriv(s, "SPY   261009P00560000").marketValue()).isEqualByComparingTo("-340");
    }

    @Test
    void 期货记0_只带名义价值_债券按持仓市值_不加应计利息() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(derivSample()).accounts().get("U5550001").snapshot();
        var fut = deriv(s, "ESZ6");
        assertThat(fut.kind()).isEqualTo(com.family.finance.domain.stock.InstrumentKind.FUTURE);
        assertThat(fut.marketValue()).isEqualByComparingTo("0");          // 按名义价值记会多出 30 万
        assertThat(fut.notional()).isEqualByComparingTo("300000");
        var bond = deriv(s, "T 4 1/4 11/15/34");
        assertThat(bond.kind()).isEqualTo(com.family.finance.domain.stock.InstrumentKind.BOND);
        assertThat(bond.marketValue()).isEqualByComparingTo("9850");      // 不含 accruedInt 102.30
    }

    @Test
    void 数据不全或对不上的行_不同步_照实点名_不拿猜的数顶上() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(derivSample()).accounts().get("U5550001").snapshot();
        assertThat(s.derivatives()).extracting(BrokerDtos.Derivative::symbol)
                .doesNotContain("TSLA  261120C00300000", "NVDA  261218C00150000");
        assertThat(s.rejected()).hasSize(2);
        assertThat(s.rejected().get(0)).contains("TSLA · 看涨 · 行权价 300 · 2026-11-20 到期").contains("缺持仓市值");
        assertThat(s.rejected().get(1)).contains("NVDA").contains("对不上");
        assertThat(s.skippedNonEquity()).isEqualTo(1);                    // 差价合约仍跳过
        assertThat(s.positions()).extracting(BrokerDtos.Position::ticker).containsExactly("AAPL");
    }


    @Test
    void 伦敦上市的美元ETF_不当美股_按报表收盘价估值() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(sample()).accounts().get("U1234521").snapshot();
        assertThat(s.manualPositions()).hasSize(1);
        var m = s.manualPositions().get(0);
        assertThat(m.symbol()).isEqualTo("VWRA");
        assertThat(m.currency()).isEqualTo("USD");
        assertThat(m.unitPrice()).isEqualByComparingTo("131.52");
        assertThat(m.exchange()).isEqualTo("LSEETF");
    }

    @Test
    void 现金_BASE_SUMMARY合计行不算_只算各币种() throws IOException {
        BrokerDtos.Snapshot s = IbkrFlexParser.parseStatement(sample()).accounts().get("U1234521").snapshot();
        assertThat(s.cash()).extracting(BrokerDtos.Cash::currency).containsExactly("USD", "HKD");
        assertThat(s.cash().get(0).amount()).isEqualByComparingTo(new BigDecimal("3200.00"));
    }

    @Test
    void 缺值写作横杠时是null_不是0() throws IOException {
        var s = IbkrFlexParser.parseStatement(sample()).accounts().get("U7658830").snapshot();
        assertThat(s.positions()).hasSize(1);
        assertThat(s.positions().get(0).ticker()).isEqualTo("BRK.B");
        assertThat(s.positions().get(0).costPrice()).isNull();
    }

    @Test
    void 报表缺CashReport栏目_拒绝_不许当成现金清零() {
        String noCash = """
                <FlexQueryResponse><FlexStatements count="1"><FlexStatement accountId="U1">
                <OpenPositions><OpenPosition assetCategory="STK" symbol="AAPL" listingExchange="NASDAQ" position="1" currency="USD"/></OpenPositions>
                </FlexStatement></FlexStatements></FlexQueryResponse>""";
        assertThatThrownBy(() -> IbkrFlexParser.parseStatement(noCash))
                .isInstanceOf(IbkrFlexException.class)
                .hasMessageContaining("Cash Report");
    }

    @Test
    void 报表缺OpenPositions栏目_拒绝_不许当成全卖光了() {
        String noPos = """
                <FlexQueryResponse><FlexStatements count="1"><FlexStatement accountId="U1">
                <CashReport><CashReportCurrency currency="USD" endingCash="1"/></CashReport>
                </FlexStatement></FlexStatements></FlexQueryResponse>""";
        assertThatThrownBy(() -> IbkrFlexParser.parseStatement(noPos))
                .isInstanceOf(IbkrFlexException.class)
                .hasMessageContaining("Open Positions");
    }

    @Test
    void 一个账户都没有的报表_拒绝() {
        assertThatThrownBy(() -> IbkrFlexParser.parseStatement("<FlexQueryResponse><FlexStatements count=\"0\"/></FlexQueryResponse>"))
                .isInstanceOf(IbkrFlexException.class);
    }

    @Test
    void 外部实体不解析() throws IOException {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("xxe-probe", ".txt");
        java.nio.file.Files.writeString(f, "SECRET-FROM-DISK");
        try {
            String xxe = "<?xml version=\"1.0\"?>\n<!DOCTYPE r [ <!ENTITY x SYSTEM \"" + f.toUri() + "\"> ]>\n"
                    + "<FlexQueryResponse><FlexStatements count=\"1\"><FlexStatement accountId=\"&x;\">"
                    + "<OpenPositions/><CashReport/></FlexStatement></FlexStatements></FlexQueryResponse>";
            try {
                var r = IbkrFlexParser.parseStatement(xxe);
                assertThat(r.accounts().keySet()).noneMatch(k -> k.contains("SECRET-FROM-DISK"));
            } catch (IbkrFlexException refused) {
                // 拒绝解析也可以 —— 要守的是「不去读本机文件」
                assertThat(refused.getMessage()).doesNotContain("SECRET-FROM-DISK");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    @Test
    void 数字解析_千分位与缺值写法() {
        assertThat(IbkrFlexParser.num("12,845.30")).isEqualByComparingTo("12845.30");   // 合成数(已登记在 qa-run 的 QA111_SYNTH)
        assertThat(IbkrFlexParser.num("-")).isNull();
        assertThat(IbkrFlexParser.num("--")).isNull();
        assertThat(IbkrFlexParser.num("N/A")).isNull();
        assertThat(IbkrFlexParser.num("")).isNull();
        assertThat(IbkrFlexParser.num("-12.5")).isEqualByComparingTo("-12.5");
    }
}
