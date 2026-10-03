package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.domain.stock.InstrumentKind;
import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.trade.item.PositionDetail;
import com.tigerbrokers.stock.openapi.client.https.domain.trade.item.PositionsItem;
import com.tigerbrokers.stock.openapi.client.https.request.TigerHttpRequest;
import com.tigerbrokers.stock.openapi.client.https.request.trade.PrimeAssetRequest;
import com.tigerbrokers.stock.openapi.client.https.response.TigerHttpResponse;
import com.tigerbrokers.stock.openapi.client.https.response.trade.PrimeAssetResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.MethodName;
import com.tigerbrokers.stock.openapi.client.struct.enums.SecType;
import com.tigerbrokers.stock.openapi.client.util.builder.AccountParamBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 老虎 Tiger 只读客户端 · v0.15。
 *
 * <p><b>只读铁律</b>:只调 {@code PrimeAssetRequest}(资产/现金)与 {@code PositionsRequest}(持仓)查询,
 * 绝不构造 / 执行任何下单请求。</p>
 *
 * <p><b>接线状态</b>:凭据装配 + 现金(PrimeAsset segments)v0.15 写好。
 * <b>持仓 v1.29 才接上</b>(issue #26 顺带查出):v0.15 时没定出持仓请求的构造方式,{@code buildPositionsRequest}
 * 一直返回 null —— 老虎关联后<b>只同步现金,股票一笔都没进来</b>。现在按 SDK 官方示例走
 * {@code TigerHttpRequest(MethodName.POSITIONS)} + {@code AccountParamBuilder} 的 bizContent,
 * 应答 {@code data} 用 SDK 自带的 {@link PositionsItem}(fastjson 映射 {@code items})解析。
 * 老虎持仓接口<b>按品种查</b>(不传默认只回股票),所以股票、期权、权证、期货各查一次。
 * <b>没有真实老虎账号跑过</b>,只有单测按 SDK 的数据结构核对 —— 见 tech-design/v1.29.md §零。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TigerBrokerClient implements BrokerClient {

    private final FamilyConfigService config;

    @Override public BrokerVendor vendor() { return BrokerVendor.TIGER; }

    private TigerHttpClient client(long familyId) {
        String tigerId = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ID, "");
        String privateKey = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_KEY, "");
        String account = config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, "");
        if (tigerId.isBlank() || privateKey.isBlank()) {
            throw new IllegalStateException("老虎凭据未配置(tiger_id / RSA 私钥)");
        }
        ClientConfig cc = ClientConfig.DEFAULT_CONFIG;
        cc.tigerId = tigerId;
        cc.privateKey = privateKey;
        if (!account.isBlank()) cc.defaultAccount = account;
        return new TigerHttpClient().clientConfig(cc);
    }

    /** 账户号解析:link.brokerAccountId 优先,回落全局默认(老虎一个开发者身份可管多资金账户)。 */
    private String accountFor(long familyId, com.family.finance.domain.broker.BrokerLink link) {
        if (link != null && link.getBrokerAccountId() != null && !link.getBrokerAccountId().isBlank()) {
            return link.getBrokerAccountId().trim();
        }
        return config.getString(familyId, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, "");
    }

    @Override
    public BrokerDtos.TestReport testConnection(long familyId, com.family.finance.domain.broker.BrokerLink link) {
        String account = accountFor(familyId, link);
        PrimeAssetResponse resp = client(familyId).execute(PrimeAssetRequest.buildPrimeAssetRequest(account));
        if (resp == null || !resp.isSuccess()) {
            throw new IllegalStateException("老虎连接失败:" + (resp == null ? "无响应" : resp.getMessage()));
        }
        java.util.Map<String, BigDecimal> cash = new java.util.LinkedHashMap<>();
        java.util.List<String> markets = new ArrayList<>();
        if (resp.getItem() != null && resp.getItem().getSegments() != null) {
            resp.getItem().getSegments().forEach(seg -> {
                if (seg.getCurrency() != null && seg.getCashBalance() != null) {
                    cash.merge(seg.getCurrency(), BigDecimal.valueOf(seg.getCashBalance()), BigDecimal::add);
                }
            });
        }
        String masked = account.isBlank() ? "默认" : (account.length() <= 4 ? account : "…" + account.substring(account.length() - 4));
        // v1.29 · 持仓也查一次,让人看到「股票几笔 · 期权几笔」;持仓查不动不算连接失败(资产已经拉到了)
        try {
            BrokerDtos.Snapshot s = positions(client(familyId), account);
            int n = s.positions().size() + s.derivatives().size();
            String summary = "老虎连接正常 · 已拉到账户资产 · 持仓 " + n + " 笔"
                    + (s.derivatives().isEmpty() ? "" : "(含期权等 " + s.derivatives().size() + " 笔)")
                    + (s.rejected().isEmpty() ? "" : " · " + s.rejected().size() + " 笔数据对不上、不会同步");
            return new BrokerDtos.TestReport(summary, masked, null, markets, n, cash);
        } catch (RuntimeException e) {
            log.warn("tiger test · positions failed: {}", e.toString());
            return new BrokerDtos.TestReport("老虎连接正常 · 已拉到账户资产 · 持仓没查到(" + e.getMessage() + ")",
                    masked, null, markets, -1, cash);
        }
    }

    @Override
    public BrokerDtos.Snapshot fetch(long familyId, com.family.finance.domain.broker.BrokerLink link) {
        TigerHttpClient c = client(familyId);
        String account = accountFor(familyId, link);

        // ---- 现金(PrimeAsset · segment 按币种)----
        // v1.29 · 两处顺手修:
        //   ① 查询失败原来静默当成「没有现金」→ 对账把同步来的现金行全部归档,余额掉一截(与 v1.26 IBKR 失败信封同一类)。现在抛出,本次不对账。
        //   ② 证券 / 期货两个 segment 可能是同一币种 → 原来各记一条,对账时后一条覆盖前一条。现在按币种相加(与测试连接一致)。
        PrimeAssetResponse ar = c.execute(PrimeAssetRequest.buildPrimeAssetRequest(account));
        if (ar == null || !ar.isSuccess()) {
            throw new IllegalStateException("老虎资产查询失败:" + (ar == null ? "无响应" : ar.getMessage()));
        }
        Map<String, BigDecimal> cashByCcy = new LinkedHashMap<>();
        if (ar.getItem() != null && ar.getItem().getSegments() != null) {
            ar.getItem().getSegments().forEach(seg -> {
                if (seg.getCurrency() != null && seg.getCashBalance() != null) {
                    cashByCcy.merge(seg.getCurrency(), BigDecimal.valueOf(seg.getCashBalance()), BigDecimal::add);
                }
            });
        }
        List<BrokerDtos.Cash> cash = new ArrayList<>();
        cashByCcy.forEach((k, v) -> cash.add(new BrokerDtos.Cash(k, v)));

        // ---- 持仓(v1.29 接上)----
        BrokerDtos.Snapshot p = positions(c, account);
        return new BrokerDtos.Snapshot(p.positions(), cash, p.skippedNonEquity(), List.of(),
                p.derivatives(), p.rejected());
    }

    /** 老虎持仓接口不传品种只回股票 → 按品种各查一次 */
    static final List<SecType> POSITION_TYPES =
            List.of(SecType.STK, SecType.OPT, SecType.WAR, SecType.IOPT, SecType.FUT, SecType.FOP);

    /** 拉持仓(不含现金);股票那一查失败就抛 —— 否则会被当成「全卖光了」,把同步来的持仓全部归档 */
    private BrokerDtos.Snapshot positions(TigerHttpClient c, String account) {
        List<BrokerDtos.Position> positions = new ArrayList<>();
        Map<String, BrokerDtos.Derivative> derivs = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        int skipped = 0;
        for (SecType t : POSITION_TYPES) {
            TigerHttpRequest req = new TigerHttpRequest(MethodName.POSITIONS);
            AccountParamBuilder b = AccountParamBuilder.instance().secType(t);
            if (account != null && !account.isBlank()) b.account(account);
            req.setBizContent(b.buildJson());
            TigerHttpResponse resp = c.execute(req);
            if (resp == null || !resp.isSuccess()) {
                String msg = resp == null ? "无响应" : resp.getMessage();
                if (t == SecType.STK) throw new IllegalStateException("老虎持仓查询失败:" + msg);
                // 没开期权 / 期货权限的账户查这些品种可能直接报错 —— 不是故障,记一行日志跳过
                log.info("tiger positions · secType={} 未取到: {}", t, msg);
                continue;
            }
            for (PositionDetail d : parse(resp.getData())) {
                Mapped m = map(d);
                if (m == null) continue;
                if (m.skipped()) { skipped++; continue; }
                if (m.rejected() != null) { rejected.add(m.rejected()); continue; }
                if (m.derivative() != null) { derivs.putIfAbsent(m.derivative().symbol(), m.derivative()); continue; }
                positions.add(m.stock());
            }
        }
        return new BrokerDtos.Snapshot(positions, List.of(), skipped, List.of(), new ArrayList<>(derivs.values()), rejected);
    }

    /** 应答 data → 持仓列表(SDK 自带的 PositionsItem 把 JSON 的 items 映射成 positions) */
    static List<PositionDetail> parse(String data) {
        if (data == null || data.isBlank()) return List.of();
        PositionsItem item = com.alibaba.fastjson.JSON.parseObject(data, PositionsItem.class);
        return item == null || item.getPositions() == null ? List.of() : item.getPositions();
    }

    /** 一笔老虎持仓归到哪儿:股票 / 期权等 / 没同步(带原因)/ 跳过计数 */
    record Mapped(BrokerDtos.Position stock, BrokerDtos.Derivative derivative, String rejected, boolean skipped) {}

    /**
     * 一笔老虎持仓 → 归类 · v1.29(包可见供单测)。
     *
     * <p>期权 / 权证:市值用老虎给的 {@code marketValue},再用「张数 × 最新价 × 乘数」核对(FR-950);
     * 老虎文档没写卖出时市值带不带负号 → 符号一律跟张数走,只核对大小。期货记 0、展示名义价值。</p>
     */
    static Mapped map(PositionDetail d) {
        if (d == null) return null;
        BigDecimal qty = d.getPositionQty() != null ? BigDecimal.valueOf(d.getPositionQty())
                : BigDecimal.valueOf(d.getPosition()).movePointLeft(Math.max(0, d.getPositionScale()));
        if (qty.signum() == 0) return null;
        String sec = d.getSecType() == null ? "" : d.getSecType().trim().toUpperCase(Locale.ROOT);
        InstrumentKind kind = switch (sec) {
            case "OPT", "FOP" -> InstrumentKind.OPTION;
            case "WAR", "IOPT" -> InstrumentKind.WARRANT;
            case "FUT" -> InstrumentKind.FUTURE;
            default -> null;
        };
        if (kind == null) {
            if (!BrokerTicker.isEquity(sec)) return new Mapped(null, null, null, true);
            var n = BrokerTicker.fromTiger(d.getMarket(), d.getSymbol());
            if (n == null) return new Mapped(null, null, null, true);
            return new Mapped(new BrokerDtos.Position(n.market().name(), n.ticker(), blankToNull(d.getName()), qty,
                    d.getAverageCost() == null ? null : BigDecimal.valueOf(d.getAverageCost()),
                    d.getCurrency(), true), null, null, false);
        }
        String code = blankToNull(d.getIdentifier()) != null ? d.getIdentifier().trim()
                : String.join(" ", nz(d.getSymbol()), nz(d.getExpiry()), nz(d.getRight()), nz(d.getStrike())).trim();
        String und = blankToNull(d.getSymbol());
        String pc = blankToNull(d.getRight());
        BigDecimal strike = num(d.getStrike());
        java.time.LocalDate exp = DerivativeRows.parseDate(d.getExpiry());
        BigDecimal mark = d.getLatestPrice() == null ? null : BigDecimal.valueOf(d.getLatestPrice());
        BigDecimal mult = d.getMultiplier() == null ? null : BigDecimal.valueOf(d.getMultiplier());
        BigDecimal value = d.getMarketValue() == null ? null
                : BigDecimal.valueOf(Math.abs(d.getMarketValue())).multiply(BigDecimal.valueOf(qty.signum()));
        String why = DerivativeRows.check(kind, qty, mark, mult, value);
        if (why != null) {
            return new Mapped(null, null, DerivativeRows.rejectLine(kind, und, code, pc, strike, exp, d.getName(), why), false);
        }
        BigDecimal notional = kind == InstrumentKind.FUTURE && mark != null && mult != null
                ? qty.multiply(mark).multiply(mult) : null;
        return new Mapped(null, new BrokerDtos.Derivative(kind, code, und, pc, strike, exp, mult, qty, mark,
                kind.countsInBalance() ? value : BigDecimal.ZERO, notional, d.getCurrency(), d.getName()),
                null, false);
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static BigDecimal num(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
