package com.family.finance.service.holdingimport;

import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmSettings;
import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.4 · 持仓截图视觉转写客户端 · v1.13 改造为<b>按平台解析</b>(原 {@code QwenVisionClient})。
 *
 * <p>v1.12 之前这里把端点、凭据、型号三样全写死在百炼上({@code API} 常量 + 直接读 qwen key
 * + {@code DEFAULT_MODEL="qwen-vl-max"}),等于<b>截图导入被钉死在一家平台</b>。现在三样都来自
 * {@link LlmSettings#vision()} 这一个三元组:平台决定端点与用哪把 key,系列决定默认型号,
 * 型号可手填(方舟这类必须手填)。开关是<b>独立的</b> {@code llm_vision_enabled},
 * 不再把 "off" 塞进型号字段当假型号(FR-362)。</p>
 *
 * <p><b>视觉不做 failover</b>(tech-design v1.13 §1.6):视觉调用发生在用户等待中的交互路径上
 * (传图 → 等结果),再来一轮备选会把最坏等待翻倍。所以这里只有一个三元组,失败就如实报错。</p>
 *
 * <p><b>只转写屏幕可见的名称+市值+代码,绝不计算/推导</b>(承 feedback_llm_no_math)。
 * 打标(资产类型/行业/平台)交给 {@code LensAiTagService},此处不判。</p>
 */
@Component
@Slf4j
public class VisionLlmClient {
    @org.springframework.beans.factory.annotation.Autowired
    private com.family.finance.service.checkup.llm.LlmTestGate testGate;

    private static final long FAMILY_ID = 1L;   // 单家庭设计

    private static final String SYS =
        "你是持仓截图转写器。只转写图中肉眼可见的持仓,绝不计算、推导、反推或编造任何数值。"
        + "跳过分类汇总行(如「基金」「多宝理财」这种把下面几支加总的标题行),只要真正的单支持仓。输出严格 JSON,不要 markdown 围栏。";
    private static final String PROMPT =
        "这是一张理财/基金/券商 app 的持仓列表截图。逐支持仓转写,只读屏幕上肉眼可见的文字和数字。"
        + "输出一个 JSON 数组,每个元素:{\"name\":\"持仓名称(原样)\",\"code\":\"基金代码(6位数字,没有则 null)\","
        + "\"marketValue\":\"持仓市值/金额(原样字符串;读不到填 null)\",\"confidence\":\"high 或 low(名称/数字被遮挡或模糊则 low)\"}。"
        + "不要计算、不要合计、不要补全看不到的值。只输出 JSON 数组本身。";

    private final FamilyConfigService config;
    private final RestTemplate rt;

    public VisionLlmClient(FamilyConfigService config, RestTemplateBuilder builder) {
        this.config = config;
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(10)).setReadTimeout(Duration.ofSeconds(90)).build();
    }

    /** 当前生效的视觉三元组(读时派生 · 旧配置自动映射成 百炼/通义千问 VL) */
    public LlmInvocation invocation() {
        return LlmSettings.load(config, FAMILY_ID).vision();
    }

    private String apiKey(LlmInvocation inv) {
        return inv.platformDef()
                .map(p -> config.getString(FAMILY_ID, p.keyName(), ""))
                .orElse("");
    }

    /**
     * 本次实际发给对方的型号。
     *
     * <p>仍然只返回型号本身(不带平台前缀)—— {@code holding_import.vision_model} 是 VARCHAR(32),
     * 而方舟的接入点 ID 就能吃掉二十几个字符,加前缀会顶到列宽上;历史行里存的也是裸型号,
     * 混两种格式在详情页上更难读。平台信息在导入页由 {@link #platformLabel()} 单独展示。</p>
     */
    public String model() {
        String m = invocation().resolvedModel();
        return m == null ? "" : m;
    }

    /** 平台中文名(导入页展示用 · 让用户知道这次截图发去了哪家) */
    public String platformLabel() {
        return LlmCatalog.labelOf(invocation().platform());
    }

    /** 视觉可用 = 开关打开 且 该平台 key 已配 且 型号能定下来(方舟没填型号 → 不可用) */
    public boolean available() {
        if (testGate != null && testGate.enabled()) return true;
        LlmSettings s = LlmSettings.load(config, FAMILY_ID);
        if (!s.visionEnabled()) return false;
        LlmInvocation inv = s.vision();
        if (!inv.resolvable()) return false;
        return !apiKey(inv).isBlank();
    }

    /** 一张图 → 若干持仓转写行。失败抛 RuntimeException(不泄 key)。 */
    public List<ParsedRow> extract(byte[] imageBytes, String mime) {
        if (testGate != null && testGate.block(FAMILY_ID,
                com.family.finance.service.llmtrace.PromptTrace.of(com.family.finance.service.llmtrace.PromptSurface.VISION_IMPORT),
                SYS, PROMPT + "\n图片未发送"))
            throw new com.family.finance.service.checkup.llm.LlmTestGate.Blocked();
        LlmSettings s = LlmSettings.load(config, FAMILY_ID);
        if (!s.visionEnabled()) throw new IllegalStateException("截图识别已在管理页关闭");
        LlmInvocation inv = s.vision();
        LlmCatalog.Platform p = inv.platformDef()
                .orElseThrow(() -> new IllegalStateException("视觉平台配置无效,请到管理页重新选择"));
        String model = inv.resolvedModel();
        if (model == null) {
            throw new IllegalStateException(p.label() + " 的视觉型号需要手工填写(控制台复制接入点/模型 ID)");
        }
        String key = apiKey(inv);
        if (key == null || key.isBlank()) throw new IllegalStateException(p.label() + " API key 未配置");

        String dataUrl = "data:" + (mime == null ? "image/jpeg" : mime) + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(imageBytes);
        String content = callVision(p, key, model, dataUrl);
        return parse(content);
    }

    @SuppressWarnings("unchecked")
    private String callVision(LlmCatalog.Platform p, String key, String model, String dataUrl) {
        if (testGate != null) testGate.beforeSend("VISION_CLIENT");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        Map<String, Object> userMsg = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", PROMPT),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
        ));
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", SYS), userMsg),
                "temperature", 0.1,
                "max_tokens", 1800
        );
        if (testGate != null) testGate.beforeSend("VISION_CLIENT");
        java.time.LocalDateTime dispatched = java.time.LocalDateTime.now();
        Map<String, Object> resp;
        try { resp = rt.postForObject(p.chatEndpoint(), new HttpEntity<>(body, h), Map.class); }
        finally { if (testGate != null) testGate.observedSend("VISION_CLIENT", dispatched); }
        if (resp == null) throw new RuntimeException("视觉服务返回空");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("视觉服务无结果");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        Object c = msg == null ? null : msg.get("content");
        if (c == null) throw new RuntimeException("视觉服务空内容");
        return c.toString();
    }

    /** 抽首个 JSON 数组(容忍 ```json 围栏)· 逐项取字段 · 市值只做格式规整(去逗号/币符),不做运算 */
    @SuppressWarnings("unchecked")
    static List<ParsedRow> parse(String raw) {
        List<ParsedRow> out = new ArrayList<>();
        if (raw == null) return out;
        int lb = raw.indexOf('['), rb = raw.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return out;
        String json = raw.substring(lb, rb + 1);
        List<Map<String, Object>> arr;
        try {
            arr = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            log.warn("vision JSON 解析失败: {}", e.toString());
            return out;
        }
        for (Map<String, Object> m : arr) {
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) continue;
            out.add(new ParsedRow(
                    name.trim(),
                    str(m.get("code")),
                    parseMoney(str(m.get("marketValue"))),
                    normConfidence(str(m.get("confidence")))));
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String normConfidence(String c) {
        return "low".equalsIgnoreCase(c) ? "low" : "high";
    }

    private static final Pattern NUM = Pattern.compile("-?[0-9][0-9,]*(\\.[0-9]+)?");

    /** 「¥42,318.60」-> 42318.60 · 只去逗号/币符取数字(格式规整,非运算);读不到返回 null */
    static BigDecimal parseMoney(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) return null;
        Matcher mt = NUM.matcher(s);
        if (!mt.find()) return null;
        try {
            return new BigDecimal(mt.group().replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** 一支持仓的视觉转写结果(只名称+代码+市值+置信度,不含标签) */
    public record ParsedRow(String name, String code, BigDecimal marketValue, String confidence) {}
}
