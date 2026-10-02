package com.family.finance.service.broker;

import com.family.finance.domain.stock.Market;

import java.util.Locale;

/**
 * 券商代码 → 我们的 Market + 纯 ticker 归一(单一真相源 · v0.15)。
 * 富途:{@code HK.00700 / US.AAPL / SH.600519 / SZ.000001};老虎:market 字段 + symbol。
 */
public final class BrokerTicker {
    private BrokerTicker() {}

    public record Norm(Market market, String ticker) {}

    /** 富途持仓 code(带交易所前缀)→ 归一。无法识别返回 null(调用方跳过)。 */
    public static Norm fromFutu(String futuCode) {
        if (futuCode == null || !futuCode.contains(".")) return null;
        String[] p = futuCode.trim().toUpperCase(Locale.ROOT).split("\\.", 2);
        String pre = p[0], ticker = p[1];
        Market m = switch (pre) {
            case "US" -> Market.US;
            case "HK" -> Market.HK;
            case "SH", "SZ", "CN" -> Market.CN;
            default -> null;
        };
        return m == null ? null : new Norm(m, ticker);
    }

    /** 老虎持仓 market + symbol → 归一。无法识别返回 null。 */
    public static Norm fromTiger(String market, String symbol) {
        if (symbol == null || symbol.isBlank() || market == null) return null;
        Market m = switch (market.trim().toUpperCase(Locale.ROOT)) {
            case "US" -> Market.US;
            case "HK" -> Market.HK;
            case "CN", "SH", "SZ", "A", "CHINA_A" -> Market.CN;
            default -> null;
        };
        return m == null ? null : new Norm(m, symbol.trim().toUpperCase(Locale.ROOT));
    }

    /** IBKR 美股的上市交易所(listingExchange)· 以真实报表为准持续补;不在表里的一律不当美股 */
    private static final java.util.Set<String> IBKR_US_EXCHANGES = java.util.Set.of(
            "NASDAQ", "NYSE", "ARCA", "AMEX", "BATS", "BYX", "EDGX", "EDGEA", "IEX", "NYSENAT", "PSX", "PINK", "CBOE");

    /**
     * IBKR 持仓(Flex 报表的 listingExchange + currency + symbol)→ 归一 · v1.26。
     *
     * <p><b>按交易所判市场,不按币种</b>:伦敦上市的美元 ETF(如 VWRA)币种是 USD,按币种会被错当成美股去拉价。</p>
     * <ul>
     *   <li>美国交易所 → US</li>
     *   <li>{@code SEHK}(港交所)→ HK,代码补足 5 位(IBKR 给 {@code 700},我们存 {@code 00700})</li>
     *   <li>{@code SEHKNTL} / {@code SEHKSZSE}(沪股通 / 深股通)→ CN</li>
     *   <li>其余 → null:调用方按「报表收盘价的手动估值持仓」处理(PRD §13①)</li>
     * </ul>
     */
    public static Norm fromIbkr(String listingExchange, String currency, String symbol) {
        if (symbol == null || symbol.isBlank() || listingExchange == null || listingExchange.isBlank()) return null;
        String ex = listingExchange.trim().toUpperCase(Locale.ROOT);
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        if (IBKR_US_EXCHANGES.contains(ex)) return new Norm(Market.US, sym.replace(' ', '.'));
        if (ex.equals("SEHK")) {
            return sym.chars().allMatch(Character::isDigit) && sym.length() <= 5
                    ? new Norm(Market.HK, "0".repeat(5 - sym.length()) + sym) : null;
        }
        if (ex.equals("SEHKNTL") || ex.equals("SEHKSZSE")) {
            return sym.chars().allMatch(Character::isDigit) && sym.length() == 6 ? new Norm(Market.CN, sym) : null;
        }
        return null;
    }

    /** 是否股票(secType=STK / 空视为股票);OPT/FUT/WAR 等 v1.29 起走期权那条路(TigerBrokerClient.map),不在这里。 */
    public static boolean isEquity(String secType) {
        if (secType == null || secType.isBlank()) return true;
        String s = secType.trim().toUpperCase(Locale.ROOT);
        return s.equals("STK") || s.equals("STOCK") || s.equals("EQUITY") || s.equals("ETF") || s.equals("FUND");
    }
}
