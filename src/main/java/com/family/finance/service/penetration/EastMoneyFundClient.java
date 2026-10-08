package com.family.finance.service.penetration;

import com.family.finance.domain.lens.IndustryTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.5 · 东财天天基金穿透数据客户端(境内直连 · spike 已验证 2026-07-24)。
 *
 * <p>四路数据:①名↔码表(fundcode_search)②资产配置 股/债/现金(pingzhongdata)
 * ③前十大重仓股(fundf10 FundArchivesDatas)④个股→东财细行业(push2 f127)。
 * 全 best-effort:失败返回空 / null,不抛给上层(承穿透是增强非核心)。</p>
 *
 * <p><b>v1.30 加两路</b>(场外基金按净值估值 · tech-design/v1.30.md §零 / 选型二):
 * ⑤ 单位净值 {@link #latestNav}(lsjz 主、pingzhongdata 备)⑥ 货币基金逐日万份收益 {@link #mmfIncome}(lsjz 按日期区间翻页)。
 * lsjz 有四个「HTTP 200 但没数据 / 数据换了含义」的坑,全在解析层判成失败并带原因,见 {@link #parseLsjzLatest}。
 * 代码表(①)改成完整条目缓存 24 小时,穿透的名↔码索引从同一份数据派生(匹配行为不变)。</p>
 */
@Slf4j
@Component
public class EastMoneyFundClient {

    private final RestTemplate rt;
    /** v1.30 · 只用来读 e2e 测试基址(fund_data_base_url);单测里可为 null */
    private final com.family.finance.service.config.FamilyConfigService config;

    static final String CATALOG_URL = "https://fund.eastmoney.com/js/fundcode_search.js";
    static final String LSJZ_URL = "https://api.fund.eastmoney.com/f10/lsjz";
    static final String LSJZ_REFERER = "https://fundf10.eastmoney.com/";
    static final String PZ_URL = "https://fund.eastmoney.com/pingzhongdata/";
    static final java.time.ZoneId CN = java.time.ZoneId.of("Asia/Shanghai");
    /** lsjz 不管要多少条,一页最多给 20(静默封顶,实测 2026-10-08) */
    static final int LSJZ_PAGE = 20;
    /** 货币基金一次最多补 120 天(6 页)—— 超过就不结,请用户先核对(选型六 约定 3) */
    public static final int MMF_MAX_DAYS = 120;

    @org.springframework.beans.factory.annotation.Autowired
    public EastMoneyFundClient(RestTemplateBuilder b, com.family.finance.service.config.FamilyConfigService config) {
        this.rt = b.setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofSeconds(12)).build();
        this.config = config;
    }

    /** 测试 / 旧调用用 */
    public EastMoneyFundClient(RestTemplateBuilder b) { this(b, null); }

    /**
     * v1.30 · e2e 用的数据源基址(管理页不出现,只由 e2e 写进家庭配置)。
     * 只认本机回环 —— 这一格能从家庭配置改,不能让它变成「让服务器替人去请求任意地址」的口子;别的值一律走东财。
     */
    String baseFor(long familyId) {
        if (config == null) return "";
        return normalizeBase(config.getString(familyId,
                com.family.finance.service.config.FamilyConfigService.K_FUND_DATA_BASE_URL, ""));
    }

    static String normalizeBase(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String b = raw.trim().replaceAll("/+$", "");
        try {
            URI u = URI.create(b);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean loopback = "http".equalsIgnoreCase(u.getScheme()) && (host.equals("127.0.0.1") || host.equals("localhost"));
            return loopback ? b : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private HttpEntity<Void> headers(String referer) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 finance-self-hosted");
        if (referer != null) h.add(HttpHeaders.REFERER, referer);
        h.setAccept(List.of(MediaType.ALL));
        return new HttpEntity<>(h);
    }

    private String get(String url, String referer) {
        try {
            ResponseEntity<byte[]> r = rt.exchange(URI.create(url), HttpMethod.GET, headers(referer), byte[].class);
            if (r.getStatusCode().isError() || r.getBody() == null) return null;
            return new String(r.getBody(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("eastmoney GET 失败 · {} · {}", url, e.toString());
            return null;
        }
    }

    // ---------- ① 代码表(v1.30 起缓存完整条目 · 名 → 码索引从它派生) ----------

    /** 代码表一条:[代码, 拼音缩写, 名称, 类型, 拼音全拼] */
    public record FundInfo(String code, String abbr, String name, String type, String pinyin) {}

    private record Catalog(List<FundInfo> list, Map<String, FundInfo> byCode, Map<String, String> normNameToCode,
                           long loadedAt) {}

    /** 按基址分开缓存(e2e 桩与真实东财互不污染);24 小时过期,取失败不缓存、下次重试 */
    private final Map<String, Catalog> catalogs = new ConcurrentHashMap<>();
    static final long CATALOG_TTL_MS = 24L * 3600 * 1000;

    private Catalog catalogAt(String base) {
        Catalog c = catalogs.get(base);
        if (c != null && System.currentTimeMillis() - c.loadedAt() < CATALOG_TTL_MS) return c;
        synchronized (catalogs) {
            c = catalogs.get(base);
            if (c != null && System.currentTimeMillis() - c.loadedAt() < CATALOG_TTL_MS) return c;
            String js = get(base.isEmpty() ? CATALOG_URL : base + "/js/fundcode_search.js", null);
            List<FundInfo> list = js == null ? List.of() : parseCatalog(js);
            log.info("基金代码表载入 {} 条{}", list.size(), base.isEmpty() ? "" : " · 基址 " + base);
            if (list.isEmpty()) return c;   // 取失败:有旧的用旧的,没有就空;不缓存空表
            Map<String, FundInfo> byCode = new HashMap<>();
            for (FundInfo f : list) byCode.putIfAbsent(f.code(), f);
            Catalog fresh = new Catalog(list, byCode, buildNameIndex(list), System.currentTimeMillis());
            catalogs.put(base, fresh);
            return fresh;
        }
    }

    /** v1.30 · 全量代码表(搜索 / 分类用);取不到返回空 */
    public List<FundInfo> catalog(long familyId) {
        Catalog c = catalogAt(baseFor(familyId));
        return c == null ? List.of() : c.list();
    }

    /** v1.30 · 按代码查一条 */
    public FundInfo find(long familyId, String code) {
        if (code == null) return null;
        Catalog c = catalogAt(baseFor(familyId));
        return c == null ? null : c.byCode().get(code.trim());
    }

    /** 解析 fundcode_search.js:每项 ["000001","HXCZHH","华夏成长混合","混合型-灵活","HUAXIA…"] */
    static List<FundInfo> parseCatalog(String js) {
        List<FundInfo> out = new ArrayList<>();
        int a = js.indexOf('['), z = js.lastIndexOf(']');
        if (a < 0 || z <= a) return out;
        Matcher em = Pattern.compile("\\[\"(\\d{6})\",\"([^\"]*)\",\"([^\"]*)\",\"([^\"]*)\",\"([^\"]*)\"\\]")
                .matcher(js.substring(a, z + 1));
        while (em.find()) out.add(new FundInfo(em.group(1), em.group(2), em.group(3), em.group(4), em.group(5)));
        return out;
    }

    /**
     * 名 → 码索引。<b>与 v1.5 的行为逐条一致</b>(护栏 v130-CATALOG-PARITY):名称非空才进、
     * 按代码表顺序 putIfAbsent(同名取第一个)。
     */
    static Map<String, String> buildNameIndex(List<FundInfo> list) {
        Map<String, String> m = new HashMap<>();
        for (FundInfo f : list) {
            if (f.name() == null || f.name().isEmpty()) continue;
            m.putIfAbsent(normName(f.name()), f.code());
        }
        return m;
    }

    private Map<String, String> nameIndex() {
        Catalog c = catalogAt("");
        return c == null ? Map.of() : c.normNameToCode();
    }

    /** 归一化:去空格 / 括号内容 / 份额后缀 / 公司别名 · 与 spike 一致 */
    static String normName(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", "");
        x = x.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "");
        x = x.replaceAll("(型证券投资基金|证券投资基金|集合资产管理计划|基金)", "");
        x = x.replace("施罗德", "");
        x = x.replaceAll("[ABCDE]$", "");
        return x;
    }

    /** 名 → 6 位代码;命中返回代码,否则 null(交给人工核对) */
    public String resolveCode(String parsedCode, String name) {
        if (parsedCode != null && parsedCode.matches("\\d{6}")) return parsedCode;
        Map<String, String> idx = nameIndex();
        String k = normName(name);
        if (k.isEmpty()) return null;
        String hit = idx.get(k);
        if (hit != null) return hit;
        // 前缀/包含兜底(≥4 字)
        if (k.length() >= 4) {
            for (Map.Entry<String, String> e : idx.entrySet()) {
                if (e.getKey().length() >= 4 && (e.getKey().contains(k) || k.contains(e.getKey()))) return e.getValue();
            }
        }
        return null;
    }

    // ---------- ② 资产配置 股/债/现金 ----------

    public record AssetAlloc(BigDecimal stockPct, BigDecimal bondPct, BigDecimal cashPct, String period) {}

    public AssetAlloc assetAllocation(String code) {
        String js = get("https://fund.eastmoney.com/pingzhongdata/" + code + ".js", null);
        if (js == null) return null;
        try {
            String seg = between(js, "Data_assetAllocation", ";");
            if (seg == null) return null;
            BigDecimal stock = lastOf(seg, "股票占净比");
            BigDecimal bond = lastOf(seg, "债券占净比");
            BigDecimal cash = lastOf(seg, "现金占净比");
            String period = lastCategory(seg);
            if (stock == null && bond == null && cash == null) return null;
            return new AssetAlloc(stock, bond, cash, period);
        } catch (Exception e) {
            log.warn("穿透 · 资产配置解析失败 {} · {}", code, e.toString());
            return null;
        }
    }

    private static BigDecimal lastOf(String seg, String seriesName) {
        Matcher m = Pattern.compile("\"name\":\"" + seriesName + "\"[^}]*?\"data\":\\[([^\\]]*)\\]").matcher(seg);
        if (!m.find()) return null;
        String[] arr = m.group(1).split(",");
        for (int i = arr.length - 1; i >= 0; i--) {
            String v = arr[i].trim();
            if (!v.isEmpty() && !"null".equals(v)) { try { return new BigDecimal(v); } catch (Exception ignored) {} }
        }
        return null;
    }

    private static String lastCategory(String seg) {
        Matcher m = Pattern.compile("\"categories\":\\[([^\\]]*)\\]").matcher(seg);
        if (!m.find()) return null;
        String[] arr = m.group(1).replace("\"", "").split(",");
        return arr.length == 0 ? null : arr[arr.length - 1].trim();
    }

    // ---------- ③ 前十大重仓股(最新季度) ----------

    public record TopHolding(String stockCode, BigDecimal pctOfNav) {}
    public record TopHoldings(List<TopHolding> stocks, String period) {}

    public TopHoldings topHoldings(String code) {
        String html = get("https://fundf10.eastmoney.com/FundArchivesDatas.aspx?type=jjcc&code=" + code
                + "&topline=10&year=&month=&rt=0.1", "https://fundf10.eastmoney.com/ccmx_" + code + ".html");
        if (html == null || !html.contains("unify/r/")) return null;
        try {
            int first = html.indexOf("boxitem");
            int second = html.indexOf("boxitem", first + 10);
            String block = second > first ? html.substring(first, second) : html.substring(first);
            String period = firstMatch(block, "截止至[：:]\\s*(?:<font[^>]*>)?(\\d{4}-\\d{2}-\\d{2})");
            List<TopHolding> out = new ArrayList<>();
            // 2026 版结构:股票代码在 unify/r/{market}.{code},占净值比在其后首个 <td class='tor'>N%
            Matcher m = Pattern.compile("unify/r/\\d\\.(\\d{6})'[\\s\\S]*?<td class='tor'>([\\d.]+)%").matcher(block);
            while (m.find() && out.size() < 10) {
                try { out.add(new TopHolding(m.group(1), new BigDecimal(m.group(2)))); } catch (Exception ignored) {}
            }
            return out.isEmpty() ? null : new TopHoldings(out, period);
        } catch (Exception e) {
            log.warn("穿透 · 前十大解析失败 {} · {}", code, e.toString());
            return null;
        }
    }

    // ---------- ④ 个股 → 东财细行业 → IndustryTag ----------

    private final Map<String, String> stockIndustryCache = new ConcurrentHashMap<>();

    /** 个股代码(6位)→ 东财细行业名(f127),缓存 */
    public String stockIndustry(String stockCode) {
        return stockIndustryCache.computeIfAbsent(stockCode, sc -> {
            String secid = sc.startsWith("6") || sc.startsWith("9") || sc.startsWith("5")
                    ? "1." + sc : "0." + sc;   // 沪 1. / 深 0.(近似 · ETF/科创以 68 起归沪)
            if (sc.startsWith("688") || sc.startsWith("60") || sc.startsWith("11")) secid = "1." + sc;
            else if (sc.startsWith("00") || sc.startsWith("30") || sc.startsWith("12") || sc.startsWith("15")) secid = "0." + sc;
            String j = get("https://push2.eastmoney.com/api/qt/stock/get?secid=" + secid + "&fields=f127", null);
            if (j == null) return "";
            Matcher m = Pattern.compile("\"f127\":\"([^\"]*)\"").matcher(j);
            return m.find() ? m.group(1) : "";
        });
    }

    /** 东财细行业名 → IndustryTag(关键词映射 · 未命中回 OTHER) */
    public IndustryTag mapIndustry(String east) {
        if (east == null || east.isBlank()) return IndustryTag.OTHER;
        String s = east;
        if (has(s, "白酒", "饮料", "食品", "乳品", "调味", "啤酒", "肉制品", "烘焙")) return IndustryTag.FOOD_BEVERAGE;
        if (has(s, "家电", "白色家电", "厨卫", "小家电", "黑色家电", "照明")) return IndustryTag.HOME_APPLIANCE;
        if (has(s, "半导体", "芯片", "集成电路", "分立器件")) return IndustryTag.SEMICONDUCTOR;
        if (has(s, "消费电子", "电子", "元件", "面板", "光学", "PCB", "被动元件", "印制电路")) return IndustryTag.ELECTRONICS;
        if (has(s, "光伏", "电池", "风电", "储能", "电网", "电源设备", "电机", "逆变器", "新能源")) return IndustryTag.POWER_EQUIP;
        if (has(s, "银行")) return IndustryTag.BANK;
        if (has(s, "证券", "保险", "多元金融", "期货", "信托", "金融控股")) return IndustryTag.NONBANK_FIN;
        if (has(s, "医", "药", "生物", "疫苗", "器械", "CXO", "血制品", "中药")) return IndustryTag.PHARMA_BIO;
        if (has(s, "汽车", "乘用车", "商用车", "汽车零部件", "汽配", "摩托")) return IndustryTag.AUTO_MOBILITY;
        if (has(s, "软件", "计算机", "云", "IT服务", "信息技术", "数据")) return IndustryTag.COMPUTER;
        if (has(s, "互联网", "游戏", "影视", "广告", "出版", "传媒", "院线", "数字媒体")) return IndustryTag.MEDIA;
        if (has(s, "通信", "运营商", "通信设备", "光模块", "光纤")) return IndustryTag.TELECOM;
        if (has(s, "房地产", "地产", "物业")) return IndustryTag.REAL_ESTATE_IND;
        if (has(s, "建筑", "装饰", "水泥", "建材", "工程", "玻璃")) return IndustryTag.CONSTRUCTION;
        if (has(s, "有色", "黄金", "铜", "铝", "稀土", "锂", "钴", "贵金属", "金属新材料")) return IndustryTag.NONFERROUS;
        if (has(s, "化工", "化学", "农化", "塑料", "橡胶", "化纤", "涂料", "氟化工")) return IndustryTag.CHEMICALS;
        if (has(s, "钢铁", "特钢")) return IndustryTag.STEEL;
        if (has(s, "煤炭", "焦炭")) return IndustryTag.COAL;
        if (has(s, "石油", "石化", "油气", "炼化")) return IndustryTag.PETROCHEM;
        if (has(s, "机械", "设备", "仪器", "工程机械", "机床", "自动化", "机器人")) return IndustryTag.MACHINERY;
        if (has(s, "军工", "国防", "航空", "航天", "船舶", "兵器")) return IndustryTag.DEFENSE;
        if (has(s, "养殖", "种植", "饲料", "农业", "林", "牧", "渔", "种业")) return IndustryTag.AGRICULTURE;
        if (has(s, "纺织", "服装", "服饰", "鞋帽")) return IndustryTag.TEXTILE;
        if (has(s, "造纸", "包装", "家居", "轻工", "文娱用品")) return IndustryTag.LIGHT_MFG;
        if (has(s, "运输", "物流", "航运", "机场", "高速", "铁路", "港口", "航空运输", "快递")) return IndustryTag.TRANSPORT;
        if (has(s, "电力", "燃气", "水务", "公用", "热力")) return IndustryTag.UTILITIES;
        if (has(s, "环保", "环境", "水处理", "固废", "节能")) return IndustryTag.ENVIRONMENT;
        if (has(s, "美容", "化妆", "个护", "护理")) return IndustryTag.BEAUTY_CARE;
        if (has(s, "旅游", "酒店", "餐饮", "教育", "景区", "社会服务", "人力资源")) return IndustryTag.SOCIAL_SVC;
        if (has(s, "零售", "百货", "超市", "商贸", "商业", "免税")) return IndustryTag.COMMERCE_RETAIL;
        if (has(s, "综合")) return IndustryTag.CONGLOMERATE;
        return IndustryTag.OTHER;
    }

    private static boolean has(String s, String... kws) {
        for (String k : kws) if (s.contains(k)) return true;
        return false;
    }

    // ---------- ⑤ 单位净值(v1.30 · lsjz 主 / pingzhongdata 备) ----------

    /** 一次取净值的结果:拿到了就带日期与单位净值;没拿到就带「人话原因」(页面与刷新结果直接用) */
    public record NavFetch(boolean ok, java.time.LocalDate navDate, BigDecimal unitNav, String source, String error) {
        static NavFetch fail(String why) { return new NavFetch(false, null, null, null, why); }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 最新单位净值。主源 lsjz 失败(超时 / 拒绝 / 查不到 / 字段换义)才走备源 pingzhongdata;
     * 两个都失败,返回<b>主源</b>的原因(那是更具体的那一个)。<b>货币基金不该走到这里</b>(见 FundNavService)。
     */
    public NavFetch latestNav(long familyId, String code) {
        String base = baseFor(familyId);
        String lsjzUrl = (base.isEmpty() ? LSJZ_URL : base + "/f10/lsjz") + "?fundCode=" + code + "&pageIndex=1&pageSize=1";
        NavFetch primary = parseLsjzLatest(get(lsjzUrl, LSJZ_REFERER));
        if (primary.ok()) return primary;
        String pzUrl = (base.isEmpty() ? PZ_URL : base + "/pingzhongdata/") + code + ".js";
        NavFetch backup = parsePzLatest(get(pzUrl, "https://fund.eastmoney.com/"));
        if (backup.ok()) return backup;
        log.warn("基金净值没拿到 · {} · lsjz:{} · pingzhongdata:{}", code, primary.error(), backup.error());
        return primary;
    }

    /**
     * 解析 lsjz 的「最新一条」。四种 HTTP 200 都要判成失败(tech-design/v1.30.md §零 实测):
     * 不带 Referer → {@code ErrCode:-999};查无此码 → {@code ErrCode:0} + 空列表;
     * 货币基金 → {@code DWJZ} 其实是每万份收益({@code SYType} 非空);单位净值 ≤ 0 / 解析不了。
     */
    static NavFetch parseLsjzLatest(String body) {
        if (body == null) return NavFetch.fail("连不上数据源");
        try {
            var root = JSON.readTree(body);
            int err = root.path("ErrCode").asInt(0);
            if (err != 0) return NavFetch.fail("数据源拒绝了请求(ErrCode " + err + ")");
            var data = root.path("Data");
            if (!data.isObject()) return NavFetch.fail("数据源返回了空数据");
            var list = data.path("LSJZList");
            if (!list.isArray() || list.isEmpty()) return NavFetch.fail("数据源查不到这只基金");
            String syType = data.path("SYType").isNull() ? null : data.path("SYType").asText(null);
            if (syType != null && !syType.isBlank()) {
                return NavFetch.fail("这只基金公布的是「" + syType + "」,不是单位净值(货币基金按金额记)");
            }
            var row = list.get(0);
            java.time.LocalDate d = java.time.LocalDate.parse(row.path("FSRQ").asText());
            BigDecimal nav = new BigDecimal(row.path("DWJZ").asText());
            if (nav.signum() <= 0) return NavFetch.fail("数据源给的单位净值不是正数");
            return new NavFetch(true, d, nav, "eastmoney-lsjz", null);
        } catch (Exception e) {
            return NavFetch.fail("数据源的格式看不懂(" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 解析 pingzhongdata 的最新净值:{@code ishb=true}(货币基金)不认;{@code x} = 净值日期东八区 0 点的毫秒数 */
    static NavFetch parsePzLatest(String js) {
        if (js == null || js.isBlank()) return NavFetch.fail("连不上数据源");
        if (js.contains("var ishb=true")) return NavFetch.fail("这是货币基金,没有单位净值序列");
        String seg = between(js, "Data_netWorthTrend", ";");
        if (seg == null) return NavFetch.fail("数据源里没有净值序列");
        Matcher m = Pattern.compile("\\{\"x\":(\\d+),\"y\":([\\d.]+)").matcher(seg);
        String x = null, y = null;
        while (m.find()) { x = m.group(1); y = m.group(2); }
        if (x == null) return NavFetch.fail("数据源里没有净值序列");
        try {
            java.time.LocalDate d = java.time.Instant.ofEpochMilli(Long.parseLong(x)).atZone(CN).toLocalDate();
            BigDecimal nav = new BigDecimal(y);
            if (nav.signum() <= 0) return NavFetch.fail("数据源给的单位净值不是正数");
            return new NavFetch(true, d, nav, "eastmoney-pz", null);
        } catch (Exception e) {
            return NavFetch.fail("数据源的格式看不懂(" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------- ⑥ 货币基金逐日万份收益(v1.30 · lsjz 按日期区间翻页) ----------

    public record IncomeDay(java.time.LocalDate date, BigDecimal per10k, BigDecimal yield7d) {}

    /** ok = 区间内的数全拿到了(按 TotalCount 核过);days 按日期升序 */
    public record IncomeFetch(boolean ok, List<IncomeDay> days, String error) {
        static IncomeFetch fail(String why) { return new IncomeFetch(false, List.of(), why); }
    }

    /** 一页的解析结果(单测用) */
    record IncomePage(List<IncomeDay> days, int total, String error) {}

    /**
     * (fromExclusive, toInclusive] 的逐日万份收益。lsjz 的 {@code pageSize} 静默封顶 20 条 →
     * 按日期区间翻页,直到拿到的条数 == {@code TotalCount};拿不全就判失败,<b>不拿半截去结转</b>。
     */
    public IncomeFetch mmfIncome(long familyId, String code, java.time.LocalDate fromExclusive, java.time.LocalDate toInclusive) {
        if (!toInclusive.isAfter(fromExclusive)) return new IncomeFetch(true, List.of(), null);
        String base = baseFor(familyId);
        String url0 = (base.isEmpty() ? LSJZ_URL : base + "/f10/lsjz") + "?fundCode=" + code + "&pageSize=" + LSJZ_PAGE
                + "&startDate=" + fromExclusive.plusDays(1) + "&endDate=" + toInclusive + "&pageIndex=";
        List<IncomeDay> all = new ArrayList<>();
        int total = -1;
        int maxPages = (MMF_MAX_DAYS + LSJZ_PAGE - 1) / LSJZ_PAGE + 1;
        for (int page = 1; page <= maxPages; page++) {
            IncomePage p = parseLsjzIncomePage(get(url0 + page, LSJZ_REFERER));
            if (p.error() != null) return IncomeFetch.fail(p.error());
            if (total < 0) total = p.total();
            all.addAll(p.days());
            if (p.days().isEmpty() || all.size() >= total) break;
        }
        if (all.size() < total) return IncomeFetch.fail("数据源只给了 " + all.size() + " / " + total + " 天,没拿全");
        all.sort(java.util.Comparator.comparing(IncomeDay::date));
        return new IncomeFetch(true, all, null);
    }

    static IncomePage parseLsjzIncomePage(String body) {
        if (body == null) return new IncomePage(List.of(), 0, "连不上数据源");
        try {
            var root = JSON.readTree(body);
            int err = root.path("ErrCode").asInt(0);
            if (err != 0) return new IncomePage(List.of(), 0, "数据源拒绝了请求(ErrCode " + err + ")");
            var data = root.path("Data");
            if (!data.isObject()) return new IncomePage(List.of(), 0, "数据源返回了空数据");
            String syType = data.path("SYType").isNull() ? null : data.path("SYType").asText(null);
            var list = data.path("LSJZList");
            int total = root.path("TotalCount").asInt(0);
            if (list.isArray() && !list.isEmpty() && !"每万份收益".equals(syType)) {
                return new IncomePage(List.of(), 0, "这只基金公布的不是每万份收益,不能按货币基金结转");
            }
            List<IncomeDay> days = new ArrayList<>();
            if (list.isArray()) {
                for (var row : list) {
                    java.time.LocalDate d = java.time.LocalDate.parse(row.path("FSRQ").asText());
                    BigDecimal per10k = new BigDecimal(row.path("DWJZ").asText());
                    String y7 = row.path("LJJZ").asText("");
                    days.add(new IncomeDay(d, per10k, y7.isBlank() ? null : new BigDecimal(y7)));
                }
            }
            return new IncomePage(days, total, null);
        } catch (Exception e) {
            return new IncomePage(List.of(), 0, "数据源的格式看不懂(" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------- 小工具 ----------

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start);
        if (a < 0) return null;
        int b = s.indexOf(end, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private static String firstMatch(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
