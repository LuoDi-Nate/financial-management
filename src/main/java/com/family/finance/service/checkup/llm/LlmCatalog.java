package com.family.finance.service.checkup.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * v1.13 FR-364 · <b>模型目录唯一一份</b>:平台 → 模型系列 → 推荐型号。
 *
 * <p>在此之前「有哪些型号可选」散在四处(客户端默认池 / 控制器白名单 / 模板 JS 清单 /
 * 视觉下拉的写死 option),而且已经事实上不一致。现在服务端校验、前端级联、客户端默认值
 * 全部从这里读:<b>加一个平台 = 改这一处 + 写一个 client 子类</b>,两件事在同一个 PR 里,
 * 编译器帮忙对齐(见 {@code LlmCatalogConsistencyTest})。</p>
 *
 * <p>为什么是 Java 常量而不是 DB 表或 JSON 资源:目录是<b>代码资产</b> —— 没有对应的
 * client 实现就没有那个平台。放进 DB 会出现「库里有平台、代码里没实现」的不一致态,
 * 选中即失败;放 JSON 的唯一好处(改文件不重编译)被「加平台走版本发布」直接抵消。
 * 详见 tech-design/v1.13.md §1.3。</p>
 */
public final class LlmCatalog {

    private LlmCatalog() {}

    /** 调用形态。文本与视觉共用平台与凭据,但系列/型号各选各的(FR-362)。 */
    public enum Modality { TEXT, VISION }

    /** 一个推荐型号。{@code id} 就是发给对方 {@code model} 字段的原值。 */
    public record Model(String id, String label) {}

    /**
     * 模型系列。{@code defaultModel} 为 null 表示<b>该系列必须显式填型号</b> ——
     * 方舟就是这样:它的型号要么是控制台建的接入点 ID(`ep-` 开头),要么是带日期版本的
     * 模型 ID(如 `doubao-seed-1-6-251015`)。这两类我们<b>都不该预置</b>:前者只能从
     * 对方控制台复制,后者会随版本过期。与其塞一个迟早失效的默认值(表现成「配好了、
     * 不报错、调用一直失败」),不如显式要求填。
     */
    public record Family(String code, String label, Modality modality,
                         List<Model> models, String defaultModel) {
        public boolean requiresExplicitModel() { return defaultModel == null; }
    }

    /**
     * 平台:决定<b>端点 + 凭据 + 计费口径</b>,一个平台一把 key。
     *
     * @param modelRotation 该平台是否支持「多型号轮询摊免费额度」。<b>这是百炼专属的计费
     *                      形态</b>(免费额度按型号各给一份),不是通用能力 —— 显式标出来,
     *                      让管理页上「只有百炼多一块轮询配置」读起来是平台能力差异,
     *                      而不是另外两个平台漏配了(PRD 拍板 3)。
     */
    public record Platform(String code, String label, String baseUrl, String keyName,
                           boolean modelRotation, String keyHowTo, List<Family> families) {

        /** chat-completions 完整端点 */
        public String chatEndpoint() { return baseUrl + "/chat/completions"; }

        public String keyConsoleUrl() {
            return switch (code) {
                case P_DASHSCOPE -> "https://bailian.console.aliyun.com";
                case P_DEEPSEEK -> "https://platform.deepseek.com";
                case P_ARK -> "https://console.volcengine.com/ark";
                case P_SILICONFLOW -> "https://cloud.siliconflow.cn/account/ak";
                default -> throw new IllegalStateException("平台缺少密钥申请入口");
            };
        }

        public List<Family> families(Modality m) {
            return families.stream().filter(f -> f.modality() == m).toList();
        }

        public Optional<Family> family(String familyCode) {
            if (familyCode == null) return Optional.empty();
            return families.stream().filter(f -> f.code().equalsIgnoreCase(familyCode)).findFirst();
        }

        /** 该形态下的兜底系列(配置缺失时用):优先第一个 */
        public Optional<Family> firstFamily(Modality m) {
            return families(m).stream().findFirst();
        }
    }

    // ========== 平台定义 ==========

    public static final String P_DASHSCOPE = "dashscope";
    public static final String P_DEEPSEEK  = "deepseek";
    public static final String P_ARK       = "ark";
    public static final String P_SILICONFLOW = "siliconflow";

    /**
     * 阿里云百炼(DashScope)· 承接 v0.14 的 `qwen`。
     * 免费额度按型号各给一份 → 保留多型号轮询({@code modelRotation = true})。
     */
    public static final Platform DASHSCOPE = new Platform(
            P_DASHSCOPE, "阿里云百炼",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "llm_qwen_api_key", true,
            "bailian.console.aliyun.com → API-KEY → 创建",
            List.of(
                    new Family("qwen", "通义千问", Modality.TEXT,
                            List.of(new Model("qwen-plus", "qwen-plus · 均衡"),
                                    new Model("qwen-flash", "qwen-flash · 快且省"),
                                    new Model("qwen-max", "qwen-max · 更强")),
                            "qwen-plus"),
                    new Family("qwen-vl", "通义千问 VL · 视觉", Modality.VISION,
                            List.of(new Model("qwen-vl-max", "qwen-vl-max · 更准(推荐)"),
                                    new Model("qwen-vl-plus", "qwen-vl-plus · 省半价")),
                            "qwen-vl-max")
            ));

    /** DeepSeek 官方 · 承接 v0.14 的 `deepseek`。无视觉能力。 */
    public static final Platform DEEPSEEK = new Platform(
            P_DEEPSEEK, "DeepSeek 官方",
            "https://api.deepseek.com",
            "llm_deepseek_api_key", false,
            "platform.deepseek.com → API keys → 创建",
            List.of(
                    new Family("deepseek", "DeepSeek", Modality.TEXT,
                            List.of(new Model("deepseek-chat", "deepseek-chat · 通用"),
                                    new Model("deepseek-reasoner", "deepseek-reasoner · 推理")),
                            "deepseek-chat")
            ));

    /**
     * 火山方舟(Ark)· v1.13 新接。方舟上同时挂着豆包与第三方(DeepSeek 等)系列 ——
     * 「平台 ≠ 模型系列」这件事就是被它捅破的(PRD §0)。
     *
     * <p><b>v1.18.4 · 预置推荐型号(此前是空的)</b>。v1.13 当时的判断是「方舟的 model 只能从
     * 控制台复制接入点 ID,预置任何一个都会过期」,于是三个系列都留空 —— 结果管理页给用户的
     * 唯一提示是<b>「这一家没有可预置的型号,到控制台复制接入点 ID 填进来」</b>,连去哪个页面
     * 复制都不说。维护者的评价是「这也太差劲了」,对。</p>
     *
     * <p>重新调研(2026-08-21)后那个前提<b>已经不成立</b>:方舟现在支持<b>直接用 Model ID 调用</b>,
     * 不必再建推理接入点({@code model="doubao-seed-2-0-pro-260215"} 直接发即可)。
     * 所以这里预置一组推荐型号。</p>
     *
     * <p><b>但日期后缀确实会过期</b> —— 这个顾虑是真的,只是不该用「什么都不给」来解决。做法:</p>
     * <ul>
     *   <li>默认型号选<b>不带日期</b>的 {@code doubao-seed-evolving}(平台侧自动迭代,不会失效)</li>
     *   <li>带日期的几个作为可选项列出,并在管理页写明「会随版本更迭,失效就去控制台复制最新的」</li>
     *   <li>输入框<b>照旧可手填</b>:接入点 ID({@code ep-xxxx})与任何新型号都还能填</li>
     * </ul>
     * <p>「方舟托管的 DeepSeek」这一系列<b>仍不预置</b> —— 调研没拿到可靠的现行 ID,
     * 与其编一个,不如照实要求手填(这一条保留 {@code requiresExplicitModel})。</p>
     */
    public static final Platform ARK = new Platform(
            P_ARK, "火山方舟",
            "https://ark.cn-beijing.volces.com/api/v3",
            "llm_ark_api_key", false,
            "console.volcengine.com/ark → API Key(需先完成实名认证)",
            List.of(
                    new Family("doubao", "豆包 Doubao", Modality.TEXT,
                            List.of(new Model("doubao-seed-evolving", "doubao-seed-evolving · 不带日期,平台自动跟进(推荐)"),
                                    new Model("doubao-seed-2-0-pro-260215", "doubao-seed-2-0-pro · 更强"),
                                    new Model("doubao-seed-2-0-lite-260215", "doubao-seed-2-0-lite · 均衡"),
                                    new Model("doubao-seed-2-0-mini-260215", "doubao-seed-2-0-mini · 快且省")),
                            "doubao-seed-evolving"),
                    new Family("deepseek", "DeepSeek(方舟托管)", Modality.TEXT, List.of(), null),
                    new Family("doubao-vision", "豆包 · 视觉", Modality.VISION,
                            List.of(new Model("doubao-seed-2-0-pro-260215", "doubao-seed-2-0-pro · 图片理解更准"),
                                    new Model("doubao-seed-2-0-lite-260215", "doubao-seed-2-0-lite · 均衡(推荐)"),
                                    new Model("doubao-seed-2-0-mini-260215", "doubao-seed-2-0-mini · 快且省")),
                            "doubao-seed-2-0-lite-260215")
            ));

    public static final Platform SILICONFLOW = new Platform(
            P_SILICONFLOW, "硅基流动", "https://api.siliconflow.cn/v1",
            "llm_siliconflow_api_key", false, "硅基流动控制台 → API密钥 → 创建",
            List.of(
                    new Family("deepseek", "DeepSeek", Modality.TEXT,
                            List.of(new Model("deepseek-ai/DeepSeek-V4-Flash", "DeepSeek-V4-Flash · 参考型号")),
                            "deepseek-ai/DeepSeek-V4-Flash"),
                    new Family("other", "其他对话模型", Modality.TEXT, List.of(), null),
                    new Family("kimi", "Kimi · 图片理解", Modality.VISION,
                            List.of(new Model("moonshotai/Kimi-K2.7-Code", "Kimi-K2.7-Code · 图片输入参考")),
                            "moonshotai/Kimi-K2.7-Code"),
                    new Family("other-vision", "其他图片模型", Modality.VISION, List.of(), null)
            ));

    public static final List<Platform> PLATFORMS = List.of(DASHSCOPE, DEEPSEEK, ARK, SILICONFLOW);

    public static Optional<Platform> platform(String code) {
        if (code == null) return Optional.empty();
        return PLATFORMS.stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst();
    }

    public static String labelOf(String platformCode) {
        return platform(platformCode).map(Platform::label).orElse(platformCode == null ? "" : platformCode);
    }

    // ========== 型号校验(FR-360 拍板 4:手填放开) ==========

    /**
     * 型号<b>格式</b>白名单。v0.14 那套「取值白名单 + 越权静默回落 auto」在三级模型下必错:
     * 方舟的 `ep-xxxx` 既不以 qwen 也不以 deepseek 开头,会被判越权丢掉 ——
     * 表现成「存进去了、页面也显示着、实际调的是别的型号」。
     *
     * <p>放开校验的安全边界:这个字符串<b>唯一去处</b>是发给对方的 JSON body 里的
     * {@code model} 字段。不拼进 URL(无路径穿越)、不进 SQL(MyBatis 参数化)、
     * 不进未转义的 HTML 位。字符集连引号和空格都不放行。填错的后果是对方返回
     * "model not found",经 classifyLlmError 归类后显示。</p>
     */
    public static final int MAX_MODEL_LENGTH = 256;
    public static final String MODEL_PATTERN = "^[A-Za-z0-9._:-]+(?:/[A-Za-z0-9._:-]+)*$";
    private static final Pattern MODEL_RE = Pattern.compile(MODEL_PATTERN);

    public static boolean validModel(String model) {
        return model != null && model.length() <= MAX_MODEL_LENGTH && MODEL_RE.matcher(model).matches();
    }

    /** 归一化用户输入的型号:空 / "auto" → null(表示自动);其余原样 trim(不做大小写转换,型号大小写敏感)。 */
    public static String normalizeModel(String raw) {
        if (raw == null) return null;
        String m = raw.trim();
        if (m.isEmpty() || m.equalsIgnoreCase("auto")) return null;
        return m;
    }

    /** 该型号是否属于某系列的推荐清单(用于旧配置承接时判断「是不是这一家的型号」)。 */
    public static boolean inRecommended(Family family, String model) {
        if (family == null || model == null) return false;
        return family.models().stream().anyMatch(x -> x.id().equalsIgnoreCase(model));
    }

    /** 平台 code 归一化(容错大小写)· 未知返回 null */
    public static String normalizePlatform(String code) {
        return platform(code).map(Platform::code).orElse(null);
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
