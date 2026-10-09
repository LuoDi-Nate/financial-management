package com.family.finance.service.config;

import com.family.finance.domain.config.FamilyRuntimeConfig;
import com.family.finance.repository.FamilyRuntimeConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.4.18 · 运营级配置统一读取服务(详 prd/v0.4.md §22)。
 *
 * <h3>三层 fallback 链(§22.7.1)</h3>
 * <pre>
 *   1. DB family_runtime_config(用户在管理页改的值)
 *   2. env / yml @Value(deploy.sh 种子前的 prod env 值 · 或 application.yml 默认)
 *   3. 调用方传入的 codeDefault(代码级最终兜底)
 * </pre>
 *
 * <h3>缓存(§22.7.2)</h3>
 * 5 秒 TTL per (family, key) · 写穿透时立即 invalidate · 避免 checkup 规则/cron trigger 每命中都查 DB。
 *
 * <h3>私密红线</h3>
 * LLM API key 等敏感字段:get* 方法日常返回原值 ·
 * 调用方(LLM 客户端 —— v1.13 起统一由 AbstractOpenAiCompatibleClient.apiKey() 按平台取 —— / 短信渠道)只在出网 HTTP 调用时用 ·
 * **绝不打 log / 进 LLM prompt / 进 audit_log 明文**。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FamilyConfigService {

    private final FamilyRuntimeConfigMapper mapper;

    // ========== Key 字符串常量(避免拼写错) ==========
    // Integrations · LLM
    // v1.1 · 资产透视集中度阈值(体检 LENS-CON-1/2 · /admin/calc-tweaks 可配)
    public static final String K_LENS_INDUSTRY_CONC = "lens_industry_concentration";
    public static final String K_LENS_PLATFORM_CONC = "lens_platform_concentration";
    /** v1.1.x · 旭日环级配色方案(A-E · lens.js PALETTE_PLANS 同源)· /admin/calc-tweaks 可配 · 默认 D 莫兰迪 */
    public static final String K_LENS_PALETTE = "lens_sunburst_palette";
    /** v1.2 · 再平衡划转核销金额阈值(划转 ≥ 条目×此值即核销 · 默认 0.8)*/
    public static final String K_REBALANCE_MATCH_PCT = "rebalance_match_pct";

    public static final String K_LLM_QWEN_KEY      = "llm_qwen_api_key";
    public static final String K_LLM_DEEPSEEK_KEY  = "llm_deepseek_api_key";
    public static final String K_LLM_MAX_TOKENS    = "llm_max_tokens";
    public static final String K_LLM_TIMEOUT_SECS  = "llm_timeout_seconds";
    /** v0.6 · Qwen 多模型兜底:逗号分隔有序模型列表(≤10)· 某模型免费额度用尽自动切下一个 */
    public static final String K_LLM_QWEN_MODELS   = "llm_qwen_models";
    /** v0.14 · 主选 LLM 供应商:qwen / deepseek(默认 qwen)· 主选故障自动切备 */
    public static final String K_LLM_PRIMARY_VENDOR = "llm_primary_vendor";
    /** v0.14 · 采样温度 0.0~1.0(默认 0.5) */
    public static final String K_LLM_TEMPERATURE    = "llm_temperature";
    /** v0.14 · 选定模型(随供应商级联;"auto"=保留 Qwen 轮询/各家默认) */
    public static final String K_LLM_MODEL          = "llm_model";
    /** v1.4 · 视觉识别模型(持仓截图导入)· qwen-vl-max(默认) / qwen-vl-plus / off(关闭) */
    public static final String K_LLM_VISION_MODEL   = "llm_vision_model";

    // ---------- v1.13 FR-360/363 · 三级模型(平台 / 系列 / 型号)----------
    // 上面三个「选哪个模型」的旧键 —— K_LLM_PRIMARY_VENDOR / K_LLM_MODEL / K_LLM_VISION_MODEL ——
    // 从 v1.13 起【只读不写】:老家庭升级后由 LlmSettings 读时派生成下面这套三元组,新配置一律写新键。
    // 留着它们是为了「升级后第一次打开管理页之前」的调用照旧能工作(FR-363),以及万一回滚到 v1.12
    // 时老版本还认得自己的配置。护栏 v113-LLM-LEGACY-KEYS-KEPT 盯着:不许删,也不许有人再往里写。
    // 其余几个旧键仍然是活的:两把 key、温度 / max_tokens / timeout 照旧读写,
    // K_LLM_QWEN_MODELS 仍由 DashScopeLlmClient 读(百炼专属的多型号轮询)。
    /** 主选平台 code(LlmCatalog:dashscope / deepseek / ark) */
    public static final String K_LLM_PLATFORM        = "llm_platform";
    /** 主选模型系列 code(如 qwen / deepseek / doubao) */
    public static final String K_LLM_FAMILY          = "llm_family";
    /** 主选型号;空 = 自动(百炼轮询 / 各家默认) */
    public static final String K_LLM_MODEL_ID        = "llm_model_id";
    /** 备选平台 / 系列 / 型号 —— 备选是完整三元组,不是「另一家的默认」(PRD 拍板 1) */
    public static final String K_LLM_BACKUP_PLATFORM = "llm_backup_platform";
    public static final String K_LLM_BACKUP_FAMILY   = "llm_backup_family";
    public static final String K_LLM_BACKUP_MODEL_ID = "llm_backup_model_id";
    /** 火山方舟 API Key(v1.13 新平台) */
    public static final String K_LLM_ARK_KEY         = "llm_ark_api_key";
    /** 视觉三元组(FR-362:与文本同一套解析,但各选各的) */
    public static final String K_LLM_VISION_PLATFORM = "llm_vision_platform";
    public static final String K_LLM_VISION_FAMILY   = "llm_vision_family";
    public static final String K_LLM_VISION_MODEL_ID = "llm_vision_model_id";
    /** 视觉总开关。旧版把「关闭」编码成 llm_vision_model=off,新版拆成独立开关,免得关一次就把型号选择丢了 */
    public static final String K_LLM_VISION_ENABLED  = "llm_vision_enabled";
    // Integrations · 券商只读同步(v0.15)· 私密凭据(不回显/不入 audit 明文)
    public static final String K_BROKER_TIGER_ID       = "broker_tiger_id";
    public static final String K_BROKER_TIGER_KEY      = "broker_tiger_private_key";
    public static final String K_BROKER_TIGER_ACCOUNT  = "broker_tiger_account";
    /** v1.26 · 盈透 IBKR Flex Web Service:报表口令(只能取报表)· 页面不回显、日志与审计不记值 */
    public static final String K_BROKER_IBKR_TOKEN     = "broker_ibkr_flex_token";
    public static final String K_BROKER_IBKR_QUERY     = "broker_ibkr_flex_query_id";
    /** 口令到期日 yyyy-MM-dd(可空)· IBKR 不告诉我们口令什么时候过期,只能让用户填 —— 填了才能提前提醒 */
    public static final String K_BROKER_IBKR_EXPIRES   = "broker_ibkr_token_expires_on";
    /** 最近一次成功取到的报表里有哪些账户(逗号分隔)· 关联页下拉用,免得每次打开页面都去等 IBKR 生成报表 */
    public static final String K_BROKER_IBKR_ACCOUNTS  = "broker_ibkr_accounts";
    /** 取数基址 · 默认 IBKR 官方;只接受 *.interactivebrokers.com 的 HTTPS 或本机回环(e2e 桩)· 页面上不暴露 */
    public static final String K_BROKER_IBKR_BASE_URL  = "broker_ibkr_flex_base_url";
    /** v1.30 · e2e 用的基金数据源桩地址(只认本机回环 · 管理页不出现 · 见 EastMoneyFundClient.normalizeBase) */
    public static final String K_FUND_DATA_BASE_URL    = "fund_data_base_url";
    public static final String K_BROKER_FUTU_HOST      = "broker_futu_opend_host";
    public static final String K_BROKER_FUTU_PORT      = "broker_futu_opend_port";
    public static final String K_BROKER_SYNC_CRON      = "broker_sync_cron";
    // Integrations · 贵金属(v0.14)
    /** 贵金属默认价格源:sge(上海·CNY/克·默认) / intl(国际现货·USD/oz)· 仅作新建持仓默认 */
    public static final String K_METAL_PRICE_SOURCE = "metal_price_source";
    /** 贵金属拉价 cron */
    public static final String K_METAL_CRON        = "metal_cron";
    // Integrations · 股票拉取
    public static final String K_STOCK_ENABLED     = "stock_fetch_enabled";
    public static final String K_STOCK_CRON_US     = "stock_cron_us";
    public static final String K_STOCK_CRON_CN     = "stock_cron_cn";
    public static final String K_STOCK_CRON_HK     = "stock_cron_hk";
    public static final String K_STOCK_CRON_CRYPTO = "stock_cron_crypto";
    // Integrations · FX
    public static final String K_FX_CRON           = "fx_cron";
    // 提醒 cron
    public static final String K_REPORT_REMIND_CRON = "report_remind_cron";
    // calc-tweaks 体检阈值
    public static final String K_CHECKUP_CONCENTRATION = "checkup_concentration_threshold";
    public static final String K_CHECKUP_HIGH_RISK     = "checkup_high_risk_threshold";
    public static final String K_LIQUID_BUFFER         = "liquid_buffer_ratio";
    public static final String K_EMERGENCY_MONTHS      = "emergency_fund_months";
    // 录入 epsilon / 阈值(已有 3 项 · 之前 hardcode)
    public static final String K_SMART_TRANSFER       = "smart_transfer_threshold";
    public static final String K_LOAN_ABNORMAL        = "loan_abnormal_threshold";
    public static final String K_UNEXPLAINED_EPSILON  = "unexplained_epsilon";
    // 会话
    public static final String K_REMEMBER_ME_SECONDS  = "remember_me_validity_seconds";
    /**
     * v1.12 FR-351 · SQL 归因诊断开关(默认 false)· /admin/audit 可开关。
     * 开启后每个请求在日志里输出「mapper 方法 → 次数 / 耗时」清单,并给响应加 {@code X-Sql-Count} 头。
     * 只用于查 N+1,查完就关 —— 常开会让每个请求多一段日志。护栏 {@code v112-SQL-PROFILER-OFF}。
     */
    public static final String K_SQL_PROFILER         = "sql_profiler_enabled";

    // ========== v1.19 超级 Agent ==========
    /**
     * 用哪条 runtime 跑对话:{@code local}(本机直连,默认)或 {@code managed}(百炼托管 agent)。
     *
     * <p>默认 local 不是因为它更好,是因为它<b>不需要公网</b>。托管路线要百炼回调本实例的
     * {@code /mcp},没有公网域名和 HTTPS 的部署根本连不上 —— 把它设成默认会让多数自托管用户
     * 一进来就看到一个不可用的功能。</p>
     */
    public static final String K_ASK_RUNTIME          = "ask_runtime";

    /* v1.21 支出分类:【没有配置项】。
     *
     * 第 1 稿有过两个键(录入深度 L1/L2、某成员是否展开分类填报),第 2 稿都去掉了 ——
     * 「这个家有没有建过类目」本身就是开关,而「按大类还是细类」变成了记每一笔时的
     * 自由选择(点大类就能提交,想细分再多点一下),不需要预先决定。
     *
     * 少一个设置项,就少一整类「切换之后历史怎么办」的问题。 */
    /** 超级 Agent 总开关 · 默认关(未启用时对既有用户零感知) */
    public static final String K_ASK_ENABLED          = "ask_enabled";
    /** 托管路线:百炼业务空间 ID */
    public static final String K_ASK_MA_WORKSPACE     = "ask_ma_workspace_id";
    /** 托管路线:用户在百炼控制台注册自定义 MCP 后拿到的服务 ID(注册无公开 API,只能人工) */
    public static final String K_ASK_MA_MCP_SERVER    = "ask_ma_mcp_server_id";
    /** 托管路线:我们创建出来的 agent id 与 version(PUT 是全量替换 + 乐观锁,version 必须存) */
    public static final String K_ASK_MA_AGENT_ID      = "ask_ma_agent_id";
    public static final String K_ASK_MA_AGENT_VERSION = "ask_ma_agent_version";
    /**
     * v1.19.12 · 托管路线用哪个模型。
     *
     * <p>原来这个值<b>写死在代码里</b>(qwen-plus),而百炼的<b>子业务空间默认一个模型都调不了</b>,
     * 得由主账号逐个开通。用户开通的很可能不是 qwen-plus —— 于是他明明按提示开通了模型,
     * 创建 Agent 仍然报「模型不存在」,而页面上没有任何地方能改。所以这一项必须能配。</p>
     */
    public static final String K_ASK_MA_MODEL         = "ask_ma_model";
    /** 托管路线默认模型 —— 通用够用;工具调用更强的是 qwen-max 系列,子空间按开通的填 */
    public static final String ASK_MA_MODEL_DEFAULT   = "qwen-plus";
    /** 托管路线:本实例的公网地址,拼进给百炼的 MCP 配置 */
    public static final String K_ASK_PUBLIC_BASE_URL  = "ask_public_base_url";
    /**
     * issue #22 · 体检「值得做的事」里被标成「不适用」的提醒。
     * 值是用换行分隔的「规则编号|账户 id」,家庭级规则的账户写 {@code *}。
     */
    public static final String K_CHECKUP_ADVICE_DISMISSED = "checkup_advice_dismissed";
    /**
     * v1.27 · 家里的默认分析范围(ALL / ADJUSTABLE / FINANCIAL)。
     * 不存在 = 推导:有「不参与配置分析」的账户 → ADJUSTABLE,否则 ALL(PRD FR-822)。在「分析设置」页配。
     */
    public static final String K_ANALYSIS_SCOPE_DEFAULT = "analysis_scope_default";
    /** v1.27 · 家里的默认分析模板 key(内置 GENERAL 等,或 custom:&lt;id&gt;)· 不存在 = 综合体检(FR-845) */
    public static final String K_ANALYSIS_TEMPLATE_DEFAULT = "analysis_template_default";
    /**
     * v1.27 · 关掉了「分析角度不合适?换个模板」首次提示的成员 id(逗号分隔)。
     * 按人记:一个人关掉,不打扰家里其他人(FR-881)。
     */
    public static final String K_ANALYSIS_HINT_DISMISSED = "analysis_hint_dismissed";
    /** v1.28 · AI 结果旁显示「看 AI 收到了什么」(PRD FR-914)· 缺省 = 开 */
    public static final String K_AI_PROMPT_PEEK = "ai_prompt_peek";
    /** v1.28 · 托管 Agent:上次「更新 / 创建 Agent」推给百炼的系统提示词记录(llm_prompt_record.id) */
    public static final String K_ASK_AGENT_PROMPT_RECORD = "ask_agent_prompt_record";

    // ========== env / yml @Value fallback ==========
    @Value("${finance.llm.qwen.api-key:}")
    private String envQwenKey;
    @Value("${finance.llm.deepseek.api-key:}")
    private String envDeepseekKey;
    /** v1.13 · 火山方舟。官方 SDK 惯用环境变量名是 ARK_API_KEY,这里沿用同一条 env 通道对齐另外两家 */
    @Value("${finance.llm.ark.api-key:${ARK_API_KEY:}}")
    private String envArkKey;
    @Value("${finance.stock.fetch-enabled:false}")
    private boolean envStockEnabled;
    @Value("${app.remember-me-validity-seconds:2592000}")
    private long envRememberMeSeconds;

    // ========== Cache · 5s TTL ==========
    private static final long CACHE_TTL_MILLIS = 5_000L;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Optional<String> value, long expireAt) {
        boolean isFresh() { return System.currentTimeMillis() < expireAt; }
    }

    /** 内部:DB 查找 + cache · 返回 Optional · 不做 fallback */
    private Optional<String> dbGet(long familyId, String key) {
        String cacheKey = familyId + "|" + key;
        CacheEntry e = cache.get(cacheKey);
        if (e != null && e.isFresh()) return e.value();
        Optional<String> v = mapper.findValue(familyId, key);
        cache.put(cacheKey, new CacheEntry(v, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return v;
    }

    /** 写入并立刻 invalidate cache · 触发动态 cron 重排由调用方决定 */
    public void set(long familyId, String key, String value) {
        mapper.upsert(familyId, key, value == null ? "" : value);
        cache.remove(familyId + "|" + key);
        log.info("config updated · family={} key={}", familyId,
                key.contains("key") || key.contains("secret") ? key + "=*** (private)" : key + "=" + value);
    }

    /** 批量获取整个 family 的 config(管理页一次性 render 用)· 不走 cache */
    public Map<String, String> getAll(long familyId) {
        List<FamilyRuntimeConfig> rows = mapper.findByFamily(familyId);
        Map<String, String> result = new HashMap<>();
        for (FamilyRuntimeConfig r : rows) result.put(r.getKeyName(), r.getValueText());
        return result;
    }

    // ========== 类型化访问 ==========

    /** 字符串 · DB > env(部分 key)> codeDefault */
    public String getString(long familyId, String key, String codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) return db.get();
        // env fallback for known keys
        return switch (key) {
            case K_LLM_QWEN_KEY     -> isBlank(envQwenKey)     ? codeDefault : envQwenKey;
            case K_LLM_DEEPSEEK_KEY -> isBlank(envDeepseekKey) ? codeDefault : envDeepseekKey;
            case K_LLM_ARK_KEY      -> isBlank(envArkKey)      ? codeDefault : envArkKey;
            default -> codeDefault;
        };
    }

    /** boolean · 字符串 "true"/"false" */
    public boolean getBoolean(long familyId, String key, boolean codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) return Boolean.parseBoolean(db.get());
        return switch (key) {
            case K_STOCK_ENABLED -> envStockEnabled;
            default -> codeDefault;
        };
    }

    /** int · 整数解析失败走 codeDefault */
    public int getInt(long familyId, String key, int codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Integer.parseInt(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return codeDefault;
    }

    /** long · remember-me 等 */
    public long getLong(long familyId, String key, long codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Long.parseLong(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return switch (key) {
            case K_REMEMBER_ME_SECONDS -> envRememberMeSeconds;
            default -> codeDefault;
        };
    }

    /** double · checkup 阈值 / LIQUID buffer 等 */
    public double getDouble(long familyId, String key, double codeDefault) {
        Optional<String> db = dbGet(familyId, key);
        if (db.isPresent() && !db.get().isEmpty()) {
            try { return Double.parseDouble(db.get().trim()); }
            catch (NumberFormatException e) { /* 走 fallback */ }
        }
        return codeDefault;
    }

    /** "已配置(隐藏)" / "未配置" 状态查询(管理页显)· 不返回 value 本身 */
    public boolean isPrivateKeyConfigured(long familyId, String key) {
        String v = getString(familyId, key, "");
        return v != null && !v.isBlank();
    }

    /**
     * 密钥的可辨认掩码(v1.17.2):留头 6 尾 4,中间打码 —— 例如 {@code sk-abc••••••wxyz}。
     *
     * <p><b>为什么要露几个字符</b>:用户手上常有多把 key(不同账号 / 不同额度),页面只说"已配置"
     * 他没法确认当前跑的是哪一把,于是每次都只能整条重贴。露头尾就能一眼认出来。</p>
     *
     * <p><b>为什么只露这几个</b>:头部是平台前缀(<code>sk-</code> 之类)+ 几位,尾部 4 位 ——
     * 足够辨认、不足以拼出密钥。短到无法安全打码的(≤12 位)一律全打码,不给"看着像露了一半"的错觉。
     * 这个值只回页面,<b>不进日志、不进 audit_log</b>。</p>
     */
    public String maskedSecret(long familyId, String key) {
        String v = getString(familyId, key, "");
        return maskSecret(v);
    }

    /** 掩码纯函数(单测)。 */
    public static String maskSecret(String v) {
        if (v == null || v.isBlank()) return "";
        String t = v.trim();
        if (t.length() <= 12) return "•".repeat(Math.max(6, t.length()));   // 太短:全打码
        return t.substring(0, 6) + "••••••" + t.substring(t.length() - 4);
    }

    /** 触发 cache 全部 invalidate(deploy.sh seed 后 / 测试用) */
    public void invalidateAll() {
        int n = cache.size();
        cache.clear();
        log.info("config cache invalidated · {} entries", n);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
