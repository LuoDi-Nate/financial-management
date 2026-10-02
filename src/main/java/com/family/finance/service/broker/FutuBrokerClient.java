package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.service.config.FamilyConfigService;
import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Trd;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Trd;
import com.futu.openapi.pb.TrdCommon;
import com.futu.openapi.pb.TrdGetAccList;
import com.futu.openapi.pb.TrdGetFunds;
import com.futu.openapi.pb.TrdGetPositionList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 富途 Futu 只读客户端 · v0.15(真机接线版 · 对接 FutuOpenD 网关)。
 *
 * <p><b>只读铁律</b>:只连 OpenD 的查询上下文,仅调 getAccList / getPositionList / getFunds 三个查询接口;
 * 永不解锁交易 → 物理上不能下单 / 划转。</p>
 *
 * <p>连接参数按<b>关联颗粒度</b>解析(v0.15.x 决策 M):link.opendHost/Port 优先,NULL 回落全局默认 ——
 * 多个富途账号 = 多个 OpenD 实例 = 多条关联各连各的。</p>
 *
 * <p>SDK 是异步 protobuf 回调 → {@link FutuSession} 用 CompletableFuture 把回调包成同步;
 * 每次调用开一条连接、顺序单请求(无 serial 竞态)、用完即关。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FutuBrokerClient implements BrokerClient {

    static final int TRD_ENV_REAL = TrdCommon.TrdEnv.TrdEnv_Real_VALUE;   // 1
    private static final int AWAIT_SECONDS = 15;

    private final FamilyConfigService config;
    /** v1.17 · 取网关容器共享卷里的 API 私钥(只有容器通道才有) */
    private final com.family.finance.service.broker.opend.FutuOpendManager opendManager;
    private static volatile boolean sdkInited = false;

    @Override public BrokerVendor vendor() { return BrokerVendor.FUTU; }

    // ---------- 连接参数(per-link 优先,回落全局) ----------

    private String hostFor(long familyId, BrokerLink link) {
        if (link != null && link.getOpendHost() != null && !link.getOpendHost().isBlank()) return link.getOpendHost().trim();
        String h = config.getString(familyId, FamilyConfigService.K_BROKER_FUTU_HOST, "");
        if (h.isBlank()) throw new IllegalStateException("富途 OpenD 未配置(本关联未填,且无全局默认;可用 OpenD 安装向导一键托管)");
        return h.trim();
    }

    private int portFor(long familyId, BrokerLink link) {
        if (link != null && link.getOpendPort() != null && link.getOpendPort() > 0) return link.getOpendPort();
        try { return Integer.parseInt(config.getString(familyId, FamilyConfigService.K_BROKER_FUTU_PORT, "11111").trim()); }
        catch (NumberFormatException e) { return 11111; }
    }

    private static synchronized void initSdkOnce() {
        if (!sdkInited) { FTAPI.init(); sdkInited = true; }
    }

    private FutuSession openSession(long familyId, BrokerLink link) {
        initSdkOnce();
        FutuSession s = new FutuSession();
        s.connect(hostFor(familyId, link), portFor(familyId, link), apiRsaKey());
        return s;
    }

    /**
     * API 通道加密用的私钥(v1.17)。
     *
     * <p>只在走<b>网关容器</b>那条路时才有:密钥由网关容器首启生成在共享卷里,两边用同一把。
     * 为什么要加密 —— 只锁住那个无鉴权的 telnet 控制口是<b>假安全</b>:11111 本身不加密的话,
     * 同一个 compose 网络里的其它容器照样能读走全部持仓。</p>
     *
     * <p>拿不到密钥(原生托管 / OpenD 在别处 / 网关没启用)就走明文 —— 那些拓扑下
     * OpenD 只绑 127.0.0.1 或本来就在别人的机器上,强行要求加密只会把现有能用的连接打断。</p>
     */
    private String apiRsaKey() {
        try {
            java.nio.file.Path key = opendManager.apiRsaKeyFile();
            if (key == null) return null;
            return java.nio.file.Files.readString(key, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[futu] 读 API 私钥失败,本次走明文连接: {}", e.toString());
            return null;
        }
    }

    // ---------- BrokerClient ----------

    @Override
    public BrokerDtos.TestReport testConnection(long familyId, BrokerLink link) {
        Collected c = collect(familyId, link);
        int deriv = c.snapshot.derivatives().size();
        String summary = "OpenD 已连通 · 账户尾号 " + c.accountMasked + " · 持仓 " + (c.snapshot.positions().size() + deriv)
                + " 笔" + (deriv > 0 ? "(含期权 " + deriv + " 笔)" : "")
                + " · 现金 " + c.snapshot.cash().size() + " 种币"
                + (c.snapshot.rejected().isEmpty() ? "" : " · " + c.snapshot.rejected().size() + " 笔数据对不上、不会同步");
        Map<String, BigDecimal> cashMap = new LinkedHashMap<>();
        c.snapshot.cash().forEach(x -> cashMap.put(x.currency(), x.amount()));
        return new BrokerDtos.TestReport(summary, c.accountMasked, c.accountType,
                new ArrayList<>(c.markets), c.snapshot.positions().size() + deriv, cashMap);
    }

    @Override
    public BrokerDtos.Snapshot fetch(long familyId, BrokerLink link) {
        return collect(familyId, link).snapshot;
    }

    /** 拉取结果 + 账户元信息(testConnection 富卡片与 fetch 共用一次采集)。 */
    private record Collected(BrokerDtos.Snapshot snapshot, String accountMasked, String accountType, Set<String> markets) {}

    private Collected collect(long familyId, BrokerLink link) {
        String brokerAccountId = link == null ? null
                : (link.getBrokerAccountId() == null || link.getBrokerAccountId().isBlank() ? null : link.getBrokerAccountId().trim());
        FutuSession s = openSession(familyId, link);
        try {
            List<TrdCommon.TrdAcc> accs = s.accList().getS2C().getAccListList().stream()
                    .filter(a -> a.getTrdEnv() == TRD_ENV_REAL)
                    .filter(a -> brokerAccountId == null || brokerAccountId.equals(String.valueOf(a.getAccID())))
                    .toList();
            if (accs.isEmpty()) {
                throw new IllegalStateException("未找到实盘交易账户"
                        + (brokerAccountId != null ? "(accID=" + brokerAccountId + " 不匹配,留空用全部)" : ""));
            }

            Map<String, BrokerDtos.Position> posByKey = new LinkedHashMap<>();
            Map<String, BrokerDtos.Derivative> derivByCode = new LinkedHashMap<>();
            List<String> rejected = new ArrayList<>();
            Map<String, BigDecimal> cashByCcy = new LinkedHashMap<>();
            Set<String> marketBadges = new LinkedHashSet<>();
            int skipped = 0;
            String accountMasked = mask(accs.get(0).getAccID());
            String accountType = accs.get(0).getAccType() == 2 ? "保证金账户" : "现金账户";

            for (TrdCommon.TrdAcc acc : accs) {
                List<Integer> auth = acc.getTrdMarketAuthListList();
                log.info("futu acc · accID={} accType={} auth={}", acc.getAccID(), acc.getAccType(), auth);
                // 综合账户通常持仓最多 → 用市场授权最多的账户号做展示尾号
                if (auth.size() > 1) { accountMasked = mask(acc.getAccID()); accountType = acc.getAccType() == 2 ? "保证金账户" : "现金账户"; }
                List<Integer> markets = auth.stream()
                        .filter(m -> m == TrdCommon.TrdMarket.TrdMarket_HK_VALUE
                                  || m == TrdCommon.TrdMarket.TrdMarket_US_VALUE
                                  || m == TrdCommon.TrdMarket.TrdMarket_CN_VALUE).toList();
                markets.forEach(m -> marketBadges.add(m == 1 ? "港股" : m == 2 ? "美股" : "A股"));
                int fundsMarket = !auth.isEmpty() ? auth.get(0) : TrdCommon.TrdMarket.TrdMarket_HK_VALUE;

                TrdCommon.Funds funds = s.funds(acc.getAccID(), fundsMarket).getS2C().getFunds();
                log.info("futu funds · accID={} cashInfoCount={}", acc.getAccID(), funds.getCashInfoListCount());
                if (funds.getCashInfoListCount() > 0) {
                    for (TrdCommon.AccCashInfo ci : funds.getCashInfoListList()) {
                        String ccy = currencyCode(ci.getCurrency());
                        if (ccy != null && ci.getCash() != 0) {
                            cashByCcy.merge(ccy, BigDecimal.valueOf(ci.getCash()), BigDecimal::add);
                        }
                    }
                } else if (funds.getCash() != 0) {
                    String ccy = currencyCode(funds.getCurrency());
                    if (ccy != null) cashByCcy.merge(ccy, BigDecimal.valueOf(funds.getCash()), BigDecimal::add);
                }

                for (int m : (markets.isEmpty() ? List.of(fundsMarket) : markets)) {
                    List<TrdCommon.Position> plist = s.positions(acc.getAccID(), m).getS2C().getPositionListList();
                    log.info("futu positions · accID={} trdMarket={} count={}", acc.getAccID(), m, plist.size());
                    for (TrdCommon.Position p : plist) {
                        Mapped mp = map(p.getCode(), p.getName(), p.getQty(), p.getPrice(), p.getVal(),
                                p.getCostPrice(), p.getSecMarket(), p.getPositionSide());
                        if (mp == null) continue;
                        if (mp.skipped()) { skipped++; continue; }
                        if (mp.rejected() != null) { rejected.add(mp.rejected()); continue; }
                        if (mp.derivative() != null) { derivByCode.putIfAbsent(mp.derivative().symbol(), mp.derivative()); continue; }
                        posByKey.putIfAbsent(mp.stock().market() + "|" + mp.stock().ticker(), mp.stock());
                    }
                }
            }

            List<BrokerDtos.Cash> cash = new ArrayList<>();
            cashByCcy.forEach((ccy, amt) -> cash.add(new BrokerDtos.Cash(ccy, amt)));
            log.info("futu fetch · positions={} derivatives={} cashCcy={} skipped={} rejected={}",
                    posByKey.size(), derivByCode.size(), cash.size(), skipped, rejected);
            return new Collected(new BrokerDtos.Snapshot(new ArrayList<>(posByKey.values()), cash, skipped,
                    List.of(), new ArrayList<>(derivByCode.values()), rejected),
                    accountMasked, accountType, marketBadges);
        } finally { s.close(); }
    }

    private static String mask(long accId) {
        String s = String.valueOf(accId);
        return s.length() <= 4 ? s : "…" + s.substring(s.length() - 4);
    }

    // ---------- 归一 ----------

    /** 一笔富途持仓归到哪儿:股票 / 期权 / 没同步(带原因)/ 跳过计数。四个里只有一个非空。 */
    record Mapped(BrokerDtos.Position stock, BrokerDtos.Derivative derivative, String rejected, boolean skipped) {}

    /**
     * 富途期权代码:标的 + 到期日(yyMMdd)+ C/P + 行权价 × 1000,例如美股 {@code AAPL260116C250000}、
     * 港股 {@code TCH250328C400000}。股票代码(美股字母、港股 / A 股数字)不会长成这样。
     */
    static final java.util.regex.Pattern OPTION_CODE =
            java.util.regex.Pattern.compile("^([A-Z][A-Z0-9.]{0,11}?)(\\d{6})([CP])(\\d{3,})$");

    /** 美股期权的标准乘数;富途不给乘数,用「市值 ÷(张数 × 价格)」反推出来核对 */
    static final BigDecimal US_OPTION_MULTIPLIER = BigDecimal.valueOf(100);

    /**
     * 一笔富途持仓 → 归类 · v1.29(issue #26 · 包可见供单测)。
     *
     * <p><b>v1.28 以前的两个洞</b>:</p>
     * <ul>
     *   <li>没判断品种:买入的期权被当成股票、拿期权代码去拉股价(拉不到 → 按 0 计);美股期权代码 17 个字符,
     *       比当时 16 字符的列长 → 严格模式下插入失败,<b>整次同步失败</b>。</li>
     *   <li>空头一律跳过:卖出的期权 / 融券卖出的股票都没减掉,账户余额偏高。</li>
     * </ul>
     *
     * <p>现在:期权按富途给的<b>持仓市值</b>记(卖出为负);美股期权用「市值 ÷(张数 × 价格)≈ 100」核对一遍,
     * 对不上就这一行不同步、照实说(富途文档没写清市值含不含乘数,不赌)。空头股票记负股数,估值照常拉价。</p>
     */
    static Mapped map(String code, String name, double qty, double price, double val, double costPrice,
                      int secMarket, int positionSide) {
        if (qty == 0 || code == null || code.isBlank()) return null;
        String mk = marketOf(secMarket);
        if (mk == null) return new Mapped(null, null, null, true);   // 未支持的市场:跳过计数
        int sign = positionSide == TrdCommon.PositionSide.PositionSide_Short_VALUE ? -1 : 1;
        BigDecimal q = BigDecimal.valueOf(Math.abs(qty)).multiply(BigDecimal.valueOf(sign));
        String c = code.trim().toUpperCase(Locale.ROOT);
        String ccy = currencyOfMarket(mk);
        java.util.regex.Matcher m = OPTION_CODE.matcher(c);
        if (m.matches()) {
            String und = m.group(1);
            java.time.LocalDate exp;
            try {
                exp = java.time.LocalDate.parse("20" + m.group(2), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            } catch (java.time.format.DateTimeParseException e) { exp = null; }
            String pc = m.group(3);
            BigDecimal strike = new BigDecimal(m.group(4)).movePointLeft(3).stripTrailingZeros();
            BigDecimal value = BigDecimal.valueOf(Math.abs(val)).multiply(BigDecimal.valueOf(sign));
            BigDecimal mark = price > 0 ? BigDecimal.valueOf(price) : null;
            BigDecimal implied = mark == null || val == 0 ? null
                    : value.abs().divide(q.abs().multiply(mark), 4, java.math.RoundingMode.HALF_EVEN);
            if ("US".equals(mk) && implied != null
                    && implied.subtract(US_OPTION_MULTIPLIER).abs().compareTo(BigDecimal.valueOf(2)) > 0) {
                return new Mapped(null, null, DerivativeRows.rejectLine(
                        com.family.finance.domain.stock.InstrumentKind.OPTION, und, c, pc, strike, exp, name,
                        "富途给的市值 ÷(张数 × 价格)= " + implied.stripTrailingZeros().toPlainString()
                                + ",不是美股期权的 100 倍乘数,核对不了"), false);
            }
            BigDecimal mult = implied == null ? ("US".equals(mk) ? US_OPTION_MULTIPLIER : null)
                    : implied.setScale(0, java.math.RoundingMode.HALF_EVEN);
            return new Mapped(null, new BrokerDtos.Derivative(
                    com.family.finance.domain.stock.InstrumentKind.OPTION, c, und, pc, strike, exp, mult,
                    q, mark, value, null, ccy, name), null, false);
        }
        return new Mapped(new BrokerDtos.Position(mk, c, name, q,
                costPrice > 0 ? BigDecimal.valueOf(costPrice) : null, ccy, true), null, null, false);
    }

    /** TrdSecMarket → 我们的 Market 名;未支持返回 null(跳过计数)。 */
    static String marketOf(int secMarket) {
        return switch (secMarket) {
            case 1 -> "HK";            // TrdSecMarket_HK
            case 2 -> "US";            // TrdSecMarket_US
            case 31, 32 -> "CN";       // CN_SH / CN_SZ
            default -> null;
        };
    }

    static String currencyOfMarket(String market) {
        return switch (market) { case "HK" -> "HKD"; case "US" -> "USD"; default -> "CNY"; };
    }

    /** TrdCommon.Currency 枚举值 → 币种代码;CNH(离岸)归 CNY;未知返回 null(跳过)。 */
    static String currencyCode(int currency) {
        return switch (currency) {
            case 1 -> "HKD"; case 2 -> "USD"; case 3 -> "CNY"; case 4 -> "JPY";
            case 5 -> "SGD"; case 6 -> "AUD"; case 7 -> "CAD"; case 8 -> "MYR";
            default -> null;
        };
    }

    // ---------- 会话:异步回调 → 同步(顺序单请求) ----------

    static final class FutuSession implements FTSPI_Conn, FTSPI_Trd {
        private final FTAPI_Conn_Trd trd = new FTAPI_Conn_Trd();
        private final CompletableFuture<Long> connected = new CompletableFuture<>();
        private volatile CompletableFuture<TrdGetAccList.Response> fAcc;
        private volatile CompletableFuture<TrdGetPositionList.Response> fPos;
        private volatile CompletableFuture<TrdGetFunds.Response> fFunds;

        void connect(String host, int port, String rsaPrivateKey) {
            trd.setClientInfo("family-finance", 1);
            trd.setConnSpi(this);
            trd.setTrdSpi(this);
            boolean encrypt = rsaPrivateKey != null && !rsaPrivateKey.isBlank();
            if (encrypt) trd.setRSAPrivateKey(rsaPrivateKey);   // 与 OpenD 的 rsa_private_key 同一把
            if (!trd.initConnect(host, port, encrypt)) {
                throw new IllegalStateException("无法发起到 OpenD 的连接 " + host + ":" + port
                        + (encrypt ? "(加密通道)" : ""));
            }
            await(connected, "连接 OpenD");
        }

        @Override
        public void onInitConnect(FTAPI_Conn client, long errCode, String desc) {
            if (errCode == 0) connected.complete(errCode);
            else connected.completeExceptionally(new IllegalStateException("OpenD 连接失败(" + errCode + "):" + desc));
        }

        @Override
        public void onDisconnect(FTAPI_Conn client, long errCode) {
            IllegalStateException e = new IllegalStateException("OpenD 连接断开(" + errCode + ")");
            connected.completeExceptionally(e);
            CompletableFuture<?>[] fs = {fAcc, fPos, fFunds};
            for (CompletableFuture<?> f : fs) if (f != null) f.completeExceptionally(e);
        }

        TrdGetAccList.Response accList() {
            fAcc = new CompletableFuture<>();
            // needGeneralSecAccount=true 关键:富途已把用户迁到「综合证券账户」,不设 true 列表里没有它 → 拉到的全是空的旧账户
            TrdGetAccList.Request req = TrdGetAccList.Request.newBuilder()
                    .setC2S(TrdGetAccList.C2S.newBuilder().setUserID(0).setNeedGeneralSecAccount(true)).build();
            if (trd.getAccList(req) == 0) throw new IllegalStateException("发送账户列表请求失败");
            return checkRet(await(fAcc, "获取账户列表"));
        }

        TrdGetPositionList.Response positions(long accId, int trdMarket) {
            fPos = new CompletableFuture<>();
            TrdGetPositionList.Request req = TrdGetPositionList.Request.newBuilder()
                    .setC2S(TrdGetPositionList.C2S.newBuilder().setHeader(header(accId, trdMarket))).build();
            if (trd.getPositionList(req) == 0) throw new IllegalStateException("发送持仓请求失败");
            return checkRet(await(fPos, "获取持仓"));
        }

        TrdGetFunds.Response funds(long accId, int trdMarket) {
            fFunds = new CompletableFuture<>();
            // 综合账户必填 currency(仅决定 totalAssets 等汇总字段的展示币种);各币种现金仍从 cashInfoList 逐币种读
            TrdGetFunds.Request req = TrdGetFunds.Request.newBuilder()
                    .setC2S(TrdGetFunds.C2S.newBuilder().setHeader(header(accId, trdMarket))
                            .setCurrency(TrdCommon.Currency.Currency_HKD_VALUE)).build();
            if (trd.getFunds(req) == 0) throw new IllegalStateException("发送资金请求失败");
            return checkRet(await(fFunds, "获取资金"));
        }

        @Override public void onReply_GetAccList(FTAPI_Conn c, int serial, TrdGetAccList.Response rsp) {
            CompletableFuture<TrdGetAccList.Response> f = fAcc; if (f != null) f.complete(rsp);
        }
        @Override public void onReply_GetPositionList(FTAPI_Conn c, int serial, TrdGetPositionList.Response rsp) {
            CompletableFuture<TrdGetPositionList.Response> f = fPos; if (f != null) f.complete(rsp);
        }
        @Override public void onReply_GetFunds(FTAPI_Conn c, int serial, TrdGetFunds.Response rsp) {
            CompletableFuture<TrdGetFunds.Response> f = fFunds; if (f != null) f.complete(rsp);
        }

        private static TrdCommon.TrdHeader header(long accId, int trdMarket) {
            return TrdCommon.TrdHeader.newBuilder()
                    .setTrdEnv(TRD_ENV_REAL).setAccID(accId).setTrdMarket(trdMarket).build();
        }

        private static <T> T await(CompletableFuture<T> f, String what) {
            try { return f.get(AWAIT_SECONDS, TimeUnit.SECONDS); }
            catch (TimeoutException e) { throw new IllegalStateException(what + "超时(" + AWAIT_SECONDS + "s)· OpenD 在运行吗?"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(what + "被中断"); }
            catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                throw (c instanceof RuntimeException re) ? re : new IllegalStateException(what + "失败:" + c.getMessage());
            }
        }

        void close() { try { trd.close(); } catch (Exception ignored) {} }
    }

    /** OpenD 应答 retType != 0 即失败,抛 retMsg(脱敏在 controller 层)。 */
    private static <T> T checkRet(T rsp) {
        try {
            int ret = (int) rsp.getClass().getMethod("getRetType").invoke(rsp);
            if (ret != 0) {
                String msg = (String) rsp.getClass().getMethod("getRetMsg").invoke(rsp);
                throw new IllegalStateException("OpenD 返回错误:" + msg);
            }
        } catch (ReflectiveOperationException ignored) { /* pb 结构不符则跳过校验 */ }
        return rsp;
    }
}
