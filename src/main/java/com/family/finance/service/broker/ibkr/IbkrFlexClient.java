package com.family.finance.service.broker.ibkr;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.service.broker.BrokerClient;
import com.family.finance.service.broker.BrokerDtos;
import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 盈透 IBKR 只读客户端 · v1.26 · issue #24。
 *
 * <p><b>只读的物理保证</b>:走 Flex Web Service,用的是 IBKR 的「报表口令」—— 它<b>只能取报表</b>,
 * 做不了任何交易。本类也只有两个 GET({@link IbkrFlexHttp})。</p>
 *
 * <p><b>一份报表分给多个账户</b>(FR-758):一个口令通常覆盖名下所有 IBKR 账户;一轮同步里关联了几个账房账户,
 * 就会被调几次 {@link #fetch}。每次都去 IBKR 取一遍会撞「每分钟 10 次」的限流,所以按(口令指纹, 查询号)
 * 缓存解析好的报表 10 分钟。口令一换指纹就变,不会拿到旧口令的报表。</p>
 *
 * <p><b>日志只记计数</b>(几个账户 · 几笔持仓 · 几个币种)和 IBKR 错误码;报表正文、完整账号、口令一律不进日志。</p>
 */
@Component
@Slf4j
public class IbkrFlexClient implements BrokerClient {

    static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final FamilyConfigService config;
    private final Function<String, IbkrFlexHttp> httpFactory;
    private final String appVersion;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(IbkrFlexParser.Report report, long at) {}

    @org.springframework.beans.factory.annotation.Autowired
    public IbkrFlexClient(FamilyConfigService config, @Value("${app.version:dev}") String appVersion) {
        this(config, appVersion, null);
    }

    /** 单测注入 http */
    IbkrFlexClient(FamilyConfigService config, String appVersion, Function<String, IbkrFlexHttp> httpFactory) {
        this.config = config;
        this.appVersion = appVersion;
        this.httpFactory = httpFactory != null ? httpFactory
                : base -> new IbkrFlexHttp(base, "FamilyFinance/" + appVersion + " Java/" + Runtime.version().feature());
    }

    @Override public BrokerVendor vendor() { return BrokerVendor.IBKR; }

    /** 测试连接:强制取一次新的(用户刚换了口令就是要验证新口令),顺带更新缓存与「已知账户」。 */
    @Override
    public BrokerDtos.TestReport testConnection(long familyId, BrokerLink link) {
        IbkrFlexParser.Report report = load(familyId, true);
        StringBuilder sb = new StringBuilder("连上了 · 找到 ").append(report.accounts().size()).append(" 个账户:");
        Map<String, BigDecimal> cashAll = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        int positions = 0;
        for (var a : report.accounts().values()) {
            var s = a.snapshot();
            int n = s.positions().size() + s.manualPositions().size();
            positions += n + s.derivatives().size();
            List<String> ccys = s.cash().stream().map(BrokerDtos.Cash::currency).toList();
            // v1.29 · 期权 / 期货 / 债券也会同步了:分开数,让人一眼看到「期权进来了几笔」(FR-949)
            java.util.Map<String, Integer> kinds = new java.util.LinkedHashMap<>();
            for (var d : s.derivatives()) kinds.merge(d.kind().getLabel(), 1, Integer::sum);
            StringBuilder deriv = new StringBuilder();
            kinds.forEach((k, c) -> deriv.append(" · ").append(k).append(" ").append(c).append(" 笔"));
            parts.add(mask(a.accountId()) + "(" + n + " 笔股票" + deriv
                    + (ccys.isEmpty() ? "" : " · " + String.join(" / ", ccys) + " 现金")
                    + (s.skippedNonEquity() > 0 ? " · 另有 " + s.skippedNonEquity() + " 笔其他品种不同步" : "")
                    + (s.rejected().isEmpty() ? "" : " · " + s.rejected().size() + " 行数据对不上、不会同步")
                    + ")");
            s.cash().forEach(c -> cashAll.merge(c.currency(), c.amount(), BigDecimal::add));
        }
        sb.append(String.join(" · ", parts));
        String target = link == null ? null : link.getBrokerAccountId();
        return new BrokerDtos.TestReport(sb.toString(),
                target == null || target.isBlank() ? null : mask(target.trim()),
                null, List.of(), positions, cashAll);
    }

    @Override
    public BrokerDtos.Snapshot fetch(long familyId, BrokerLink link) {
        IbkrFlexParser.Report report = load(familyId, false);
        String want = link == null || link.getBrokerAccountId() == null ? "" : link.getBrokerAccountId().trim();
        IbkrFlexParser.AccountReport a;
        if (want.isEmpty()) {
            if (report.accounts().size() != 1) {
                throw new IbkrFlexException(null, null, "报表里有 " + report.accounts().size()
                        + " 个 IBKR 账户,关联时要选定是哪一个(解绑后重新关联,在下拉里选账户)", false);
            }
            a = report.accounts().values().iterator().next();
        } else {
            a = report.accounts().get(want);
            if (a == null) {
                throw new IbkrFlexException(null, null, "报表里没有账户 " + mask(want)
                        + " —— 确认 Flex 报表里勾了这个账户", false);
            }
        }
        return a.snapshot();
    }

    /** 已知账户(关联页下拉用)· 来自最近一次成功取到的报表,不去等 IBKR */
    public List<String> knownAccounts(long familyId) {
        String raw = config.getString(familyId, FamilyConfigService.K_BROKER_IBKR_ACCOUNTS, "");
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** 口令到期日(用户填的);没填或填错返回 null */
    public LocalDate tokenExpiresOn(long familyId) {
        String raw = config.getString(familyId, FamilyConfigService.K_BROKER_IBKR_EXPIRES, "");
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (Exception e) { return null; }
    }

    /**
     * 距口令到期还有几天;没填到期日返回 null。≤ 0 表示已经过期。
     * 页面在 ≤ 14 天时开始提醒(FR-756)。
     */
    public Long daysToExpiry(long familyId, LocalDate today) {
        LocalDate d = tokenExpiresOn(familyId);
        return d == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, d);
    }

    public static final int EXPIRY_WARN_DAYS = 14;

    // ---------- internals ----------

    private IbkrFlexParser.Report load(long familyId, boolean fresh) {
        String token = config.getString(familyId, FamilyConfigService.K_BROKER_IBKR_TOKEN, "").trim();
        String query = config.getString(familyId, FamilyConfigService.K_BROKER_IBKR_QUERY, "").trim();
        if (token.isEmpty() || query.isEmpty()) throw IbkrErrors.notConfigured();
        String key = fingerprint(token) + "|" + query;
        if (!fresh) {
            Cached c = cache.get(key);
            if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) return c.report();
        }
        synchronized (cache) {
            if (!fresh) {
                Cached c = cache.get(key);   // 并发时第二个进来的直接用第一个取到的
                if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) return c.report();
            }
            String base = config.getString(familyId, FamilyConfigService.K_BROKER_IBKR_BASE_URL, "");
            long t0 = System.currentTimeMillis();
            IbkrFlexParser.Report report;
            try {
                report = IbkrFlexParser.parseStatement(httpFactory.apply(base).fetchStatementXml(token, query));
            } catch (IbkrFlexException e) {
                log.warn("ibkr flex fetch failed · code={} retryable={} · {}", e.code(), e.retryable(), e.human());
                throw e;
            }
            cache.put(key, new Cached(report, System.currentTimeMillis()));
            config.set(familyId, FamilyConfigService.K_BROKER_IBKR_ACCOUNTS, String.join(",", report.accounts().keySet()));
            int pos = report.accounts().values().stream()
                    .mapToInt(a -> a.snapshot().positions().size() + a.snapshot().manualPositions().size()).sum();
            log.info("ibkr flex fetched · accounts={} positions={} · {}ms",
                    report.accounts().size(), pos, System.currentTimeMillis() - t0);
            return report;
        }
    }

    /** 口令指纹:只用来当缓存键,不可逆,不落库不进日志 */
    static String fingerprint(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }

    /** U1234567 → U•••4567 */
    public static String mask(String account) {
        if (account == null || account.isBlank()) return "";
        String t = account.trim();
        if (t.length() <= 4) return "•".repeat(t.length());
        return t.charAt(0) + "•••" + t.substring(t.length() - 4);
    }

    /** 单测用 */
    void clearCache() { cache.clear(); }
}
