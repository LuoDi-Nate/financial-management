package com.family.finance.service.broker.ibkr;

import com.family.finance.domain.stock.InstrumentKind;
import com.family.finance.service.broker.BrokerDtos;
import com.family.finance.service.broker.BrokerTicker;
import com.family.finance.service.broker.DerivativeRows;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IBKR Flex Web Service 的 XML → 我们的中性快照 · v1.26 · 纯函数。
 *
 * <h3>两种根元素,先认根再读</h3>
 * <ul>
 *   <li>{@code <FlexStatementResponse>}:SendRequest 的回执,<b>以及任何失败</b>。
 *       失败时 IBKR 照样回 <b>HTTP 200</b>(2026-09-24 在 beta 实测),只在正文里写 {@code <Status>Fail</Status>}。
 *       只看状态码的话会把一段错误信息当报表解析 → 0 笔持仓 → 对账把同步来的持仓<b>全部归档</b>,余额掉成 0。</li>
 *   <li>{@code <FlexQueryResponse>}:真正的报表,每个账户一个 {@code <FlexStatement accountId="…">}。</li>
 * </ul>
 *
 * <h3>三个会让数字悄悄变错的地方</h3>
 * <ul>
 *   <li>{@code CashReportCurrency currency="BASE_SUMMARY"} 是折成基础币种的<b>合计</b>,不跳过现金就算两遍。</li>
 *   <li>{@code OpenPosition levelOfDetail="LOT"} 是按批次拆开的明细,与 SUMMARY 行同时出现时不跳过持仓就算两遍。</li>
 *   <li>报表里<b>没有</b> Open Positions / Cash Report 栏目(用户建报表时漏勾)≠「全卖光了」—— 拒绝,不许进对账。</li>
 * </ul>
 *
 * <h3>v1.29 · 期权 / 期货 / 债券(issue #26)</h3>
 * <p>{@code assetCategory} 为 OPT / FOP / WAR / FUT / BOND 的行进 {@link BrokerDtos.Derivative}:
 * 市值用报表的 {@code positionValue}(已含乘数,卖出为负 —— 2026-10-02 @Jsonya 贴的真实报表核对过),
 * 但仍用「张数 × 标记价 × 乘数」交叉核对一遍,对不上的行不同步、照实列出({@link DerivativeRows#check})。
 * 其余品种(差价合约、外汇头寸 …)仍跳过计数。</p>
 *
 * <p><b>XXE</b>:报表来自外部网络,解析器关闭 DTD 与外部实体。</p>
 */
public final class IbkrFlexParser {
    private IbkrFlexParser() {}

    /** 一份报表里某个账户的内容 */
    public record AccountReport(String accountId, BrokerDtos.Snapshot snapshot, int lotRowsSkipped) {}

    /** 一整份报表:账户号 → 内容(保序) */
    public record Report(Map<String, AccountReport> accounts) {}

    private static XMLInputFactory safeFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return f;
    }

    /**
     * SendRequest 的回执 → 回执号(ReferenceCode)。失败信封一律抛 {@link IbkrFlexException}。
     */
    public static String parseSendResponse(String xml) {
        Map<String, String> env = readEnvelope(xml);
        if (env == null) {
            throw new IbkrFlexException(null, abbreviate(xml), "IBKR 返回的不是预期的回执格式", false);
        }
        if (!"Success".equalsIgnoreCase(env.getOrDefault("Status", ""))) {
            throw IbkrErrors.fromEnvelope(env.get("ErrorCode"), env.get("ErrorMessage"));
        }
        String ref = env.get("ReferenceCode");
        if (ref == null || ref.isBlank()) {
            throw new IbkrFlexException(null, abbreviate(xml), "IBKR 说成功了,但没有给回执号", true);
        }
        return ref.trim();
    }

    /**
     * GetStatement 的正文 → 报表。若正文其实是失败信封(1019 还在生成 / 1012 过期 …),抛 {@link IbkrFlexException}。
     */
    public static Report parseStatement(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IbkrFlexException(null, "(空响应)", "IBKR 返回了空内容", true);
        }
        Map<String, String> env = readEnvelope(xml);
        if (env != null) {
            // 取报表的请求拿到的是信封 —— 一定是失败(或还没生成好)
            throw IbkrErrors.fromEnvelope(env.get("ErrorCode"), env.getOrDefault("ErrorMessage", env.get("Status")));
        }
        Map<String, AccountReport> out = new LinkedHashMap<>();
        try {
            XMLStreamReader r = safeFactory().createXMLStreamReader(new StringReader(xml));
            boolean sawRoot = false;
            String account = null;
            List<BrokerDtos.Position> positions = null;
            List<BrokerDtos.ManualPosition> manual = null;
            List<BrokerDtos.Derivative> derivs = null;
            List<String> rejected = null;
            Map<String, BigDecimal> cash = null;
            int skipped = 0, lotSkipped = 0;
            boolean sawPositions = false, sawCash = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    switch (n) {
                        case "FlexQueryResponse" -> sawRoot = true;
                        case "FlexStatement" -> {
                            account = attr(r, "accountId");
                            positions = new ArrayList<>();
                            manual = new ArrayList<>();
                            derivs = new ArrayList<>();
                            rejected = new ArrayList<>();
                            cash = new LinkedHashMap<>();
                            skipped = 0; lotSkipped = 0;
                            sawPositions = false; sawCash = false;
                        }
                        case "OpenPositions" -> sawPositions = true;
                        case "CashReport" -> sawCash = true;
                        case "OpenPosition" -> {
                            if (positions == null) break;
                            String lvl = attr(r, "levelOfDetail");
                            if (lvl != null && !lvl.isBlank() && !"SUMMARY".equalsIgnoreCase(lvl)) { lotSkipped++; break; }
                            String cat = upper(attr(r, "assetCategory"));
                            InstrumentKind kind = kindOf(cat);
                            if (kind != null) { readDerivative(r, kind, derivs, rejected); break; }
                            if (cat != null && !cat.isEmpty() && !"STK".equals(cat)) { skipped++; break; }
                            BigDecimal shares = num(attr(r, "position"));
                            if (shares == null) { skipped++; break; }
                            String symbol = attr(r, "symbol");
                            String ex = attr(r, "listingExchange");
                            String ccy = upper(attr(r, "currency"));
                            String desc = attr(r, "description");
                            BigDecimal cost = num(attr(r, "costBasisPrice"));
                            var norm = BrokerTicker.fromIbkr(ex, ccy, symbol);
                            if (norm != null) {
                                positions.add(new BrokerDtos.Position(norm.market().name(), norm.ticker(),
                                        blankToNull(desc), shares, cost, ccy, true));
                            } else {
                                // 拉不到价的市场(伦敦 / 东京 / 新加坡 …)→ 按报表收盘价估值(PRD §13①)
                                BigDecimal mark = num(attr(r, "markPrice"));
                                if (mark == null || symbol == null || symbol.isBlank()) { skipped++; break; }
                                manual.add(new BrokerDtos.ManualPosition(symbol.trim(), blankToNull(desc),
                                        blankToNull(ex), shares, mark, cost, ccy));
                            }
                        }
                        case "CashReportCurrency" -> {
                            if (cash == null) break;
                            String ccy = upper(attr(r, "currency"));
                            if (ccy == null || "BASE_SUMMARY".equals(ccy) || ccy.length() != 3) break;
                            String lvl = attr(r, "levelOfDetail");
                            if (lvl != null && !lvl.isBlank() && !"CURRENCY".equalsIgnoreCase(lvl)) break;
                            BigDecimal amt = num(attr(r, "endingCash"));
                            if (amt != null) cash.putIfAbsent(ccy, amt);   // 同币种若出现多行,只认第一行 —— 宁缺不重
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && "FlexStatement".equals(r.getLocalName())) {
                    if (account == null || account.isBlank()) {
                        throw new IbkrFlexException(null, null, "报表里有一个账户没有账号,无法对应", false);
                    }
                    if (!sawPositions || !sawCash) {
                        throw new IbkrFlexException(null, null,
                                "这份 Flex 报表缺" + (!sawPositions ? "「Open Positions」" : "") + (!sawCash ? "「Cash Report」" : "")
                                        + "栏目 —— 在 IBKR 后台编辑报表把它勾上(否则会被当成持仓 / 现金全部清空)", false);
                    }
                    List<BrokerDtos.Cash> cashList = new ArrayList<>();
                    cash.forEach((k, v) -> cashList.add(new BrokerDtos.Cash(k, v)));
                    out.put(account.trim(), new AccountReport(account.trim(),
                            new BrokerDtos.Snapshot(List.copyOf(positions), List.copyOf(cashList), skipped,
                                    List.copyOf(manual), List.copyOf(derivs), List.copyOf(rejected)),
                            lotSkipped));
                    account = null; positions = null; manual = null; derivs = null; rejected = null; cash = null;
                }
            }
            r.close();
            if (!sawRoot) {
                throw new IbkrFlexException(null, abbreviate(xml), "IBKR 返回的不是 Flex 报表(格式要选 XML)", false);
            }
        } catch (XMLStreamException e) {
            throw new IbkrFlexException(null, e.getMessage(), "IBKR 返回的报表读不懂(格式要选 XML)", false, e);
        }
        if (out.isEmpty()) {
            // 空报表 ≠ 全卖光了:拒绝,不许进对账
            throw new IbkrFlexException(null, null, "报表里一个账户都没有 —— 确认 Flex 报表选了账户", false);
        }
        return new Report(out);
    }

    /** 若是 {@code <FlexStatementResponse>} 信封,返回它的子元素文本;否则 null。 */
    static Map<String, String> readEnvelope(String xml) {
        if (xml == null) return null;
        try {
            XMLStreamReader r = safeFactory().createXMLStreamReader(new StringReader(xml));
            Map<String, String> out = null;
            String cur = null;
            StringBuilder text = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if (out == null) {
                        if (!"FlexStatementResponse".equals(r.getLocalName())) { r.close(); return null; }
                        out = new LinkedHashMap<>();
                    } else {
                        cur = r.getLocalName();
                        text.setLength(0);
                    }
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && cur != null) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT && cur != null && cur.equals(r.getLocalName())) {
                    out.put(cur, text.toString().trim());
                    cur = null;
                }
            }
            r.close();
            return out;
        } catch (XMLStreamException e) {
            return null;
        }
    }

    /** IBKR 的 assetCategory → 我们的品种;股票 / 不同步的品种返回 null */
    static InstrumentKind kindOf(String cat) {
        if (cat == null) return null;
        return switch (cat) {
            case "OPT", "FOP" -> InstrumentKind.OPTION;   // 期货期权也是期权:一样按持仓市值记
            case "WAR" -> InstrumentKind.WARRANT;
            case "FUT" -> InstrumentKind.FUTURE;
            case "BOND" -> InstrumentKind.BOND;
            default -> null;
        };
    }

    /** 一行期权 / 期货 / 债券:读字段 → 交叉核对 → 进 derivs 或 rejected */
    private static void readDerivative(XMLStreamReader r, InstrumentKind kind,
                                       List<BrokerDtos.Derivative> derivs, List<String> rejected) {
        String symbol = blankToNull(attr(r, "symbol"));
        String desc = blankToNull(attr(r, "description"));
        String underlying = blankToNull(attr(r, "underlyingSymbol"));
        String putCall = blankToNull(attr(r, "putCall"));
        BigDecimal strike = num(attr(r, "strike"));
        java.time.LocalDate expiry = DerivativeRows.parseDate(attr(r, "expiry"));
        BigDecimal qty = num(attr(r, "position"));
        BigDecimal mark = num(attr(r, "markPrice"));
        BigDecimal mult = num(attr(r, "multiplier"));
        BigDecimal value = num(attr(r, "positionValue"));
        String ccy = upper(attr(r, "currency"));
        if (symbol == null) symbol = desc;
        String why = symbol == null ? "报表里缺代码" : DerivativeRows.check(kind, qty, mark, mult, value);
        if (why != null) {
            rejected.add(DerivativeRows.rejectLine(kind, underlying, symbol, putCall, strike, expiry, desc, why));
            return;
        }
        BigDecimal counted = kind.countsInBalance() ? value : BigDecimal.ZERO;
        BigDecimal notional = kind == InstrumentKind.FUTURE && mark != null && mult != null
                ? qty.multiply(mark).multiply(mult) : null;
        derivs.add(new BrokerDtos.Derivative(kind, symbol, underlying, putCall, strike, expiry, mult,
                qty, mark, counted, notional, ccy, desc));
    }

    // ---- 小工具 ----

    private static String attr(XMLStreamReader r, String name) {
        String v = r.getAttributeValue(null, name);
        return v == null ? null : v.trim();
    }

    /**
     * IBKR 的数字:可能带千分位,缺值写 {@code -} / {@code --} / {@code N/A} / 空。缺值返回 null,<b>不许当 0</b>。
     */
    static BigDecimal num(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty() || t.equals("-") || t.equals("--") || t.equalsIgnoreCase("N/A")) return null;
        try { return new BigDecimal(t); } catch (NumberFormatException e) { return null; }
    }

    private static String upper(String s) { return s == null ? null : s.trim().toUpperCase(Locale.ROOT); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    static String abbreviate(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
