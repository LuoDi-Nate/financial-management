package com.family.finance.service.expense.imports;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 把「月度分类统计页」的截图转写成「类目名 + 金额」。
 *
 * <h3>拍什么</h3>
 *
 * <p>不是拍流水,是拍<b>各渠道自己算好的月度分类统计</b>:
 * 支付宝「我的 → 账单 → 月账单」、微信「钱包 → 账单 → 统计」、
 * 银行 App 的收支统计页(招行「收支明细 / 结余月历」自带四十余种场景分类)。
 * 这些页面上的数<b>本来就是汇总好的</b> —— 正是我们要的粒度。</p>
 *
 * <h3>只转写,不算数</h3>
 *
 * <p>承 {@code feedback_llm_no_math} 与 v1.4 的同一条纪律:模型只读屏幕上肉眼可见的
 * 类目名与金额,<b>不求和、不换算、不补全看不到的值</b>。跨截图合并、类目映射、求和
 * 全在引擎里做。看不清就标 low,由用户在确认页改 —— <b>不许猜一个像样的数</b>。</p>
 *
 * <h3>为什么不复用 VisionLlmClient</h3>
 *
 * <p>那个类的注释、提示词纪律与护栏都钉着「持仓」语义(名称/代码/市值、跳过汇总行)。
 * 账单场景恰恰相反:<b>要的就是汇总行</b>。把两套提示词揉进一个类,两边的护栏都得改写,
 * 而共享的只是「dashscope 视觉调用」这个 ~40 行的 HTTP 形状 —— 复制它比抽象它便宜。</p>
 */
@Component
@Slf4j
public class ExpenseShotClient {
    @org.springframework.beans.factory.annotation.Autowired
    private com.family.finance.service.checkup.llm.LlmTestGate testGate;

    private static final long FAMILY_ID = 1L;   // 单家庭设计

    private static final String SYS =
        "你是账单统计页转写器。只转写图中肉眼可见的分类名与金额,"
        + "绝不计算、求和、推导或编造任何数值。看不清的填 null 并标 low,不要猜。"
        + "输出严格 JSON,不要 markdown 围栏。";

    private static final String PROMPT =
        "这是一张支付宝 / 微信 / 银行 app 的【月度收支统计页】截图,上面按分类列出了本月支出金额。"
        + "逐项转写,只读屏幕上肉眼可见的文字和数字。"
        + "输出一个 JSON 数组,每个元素:{\"category\":\"分类名(原样,不要翻译或归并)\","
        + "\"amount\":\"该分类的金额(原样字符串;读不到填 null)\","
        + "\"channel\":\"能判断出是哪个 app 就填(支付宝/微信/招商银行/工商银行/其它),判断不了填 null\","
        + "\"confidence\":\"high 或 low(名称或数字被遮挡、模糊、只露一半则 low)\"}。"
        + "只要【支出】相关的分类;收入、转账、理财、还款这类不是消费的不要。"
        + "不要计算、不要合计、不要把几项加起来,也不要补全看不到的值。只输出 JSON 数组本身。";

    private final FamilyConfigService config;
    private final RestTemplate rt;

    public ExpenseShotClient(FamilyConfigService config, RestTemplateBuilder builder) {
        this.config = config;
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(10))
                         .setReadTimeout(Duration.ofSeconds(90)).build();
    }

    /** 转写出来的一行 */
    public record ShotRow(String category, String amountRaw, String channel, String confidence) {
        public boolean uncertain() { return !"high".equalsIgnoreCase(confidence); }
    }

    public boolean available() {
        if (testGate != null && testGate.enabled()) return true;
        LlmSettings s = LlmSettings.load(config, FAMILY_ID);
        if (!s.visionEnabled()) return false;
        LlmInvocation inv = s.vision();
        return inv.platformDef().isPresent()
                && inv.resolvedModel() != null
                && !apiKey(inv).isBlank();
    }

    public String unavailableReason() {
        LlmSettings s = LlmSettings.load(config, FAMILY_ID);
        if (!s.visionEnabled()) return "截图识别在管理页是关着的。";
        LlmInvocation inv = s.vision();
        if (inv.platformDef().isEmpty()) return "视觉平台没配好,去管理页选一下。";
        if (inv.resolvedModel() == null) return "视觉型号需要手工填(平台控制台里复制模型 ID)。";
        if (apiKey(inv).isBlank()) return "视觉服务的 API key 还没填。";
        return null;
    }

    /** 转写一张图。抛异常时调用方按「这张没认出来」处理,其余图照旧。 */
    public List<ShotRow> extract(byte[] imageBytes, String mime) {
        if (testGate != null && testGate.block(FAMILY_ID,
                com.family.finance.service.llmtrace.PromptTrace.of(com.family.finance.service.llmtrace.PromptSurface.EXPENSE_SHOT),
                "支出截图识别", "图片未发送"))
            throw new com.family.finance.service.checkup.llm.LlmTestGate.Blocked();
        LlmSettings s = LlmSettings.load(config, FAMILY_ID);
        if (!s.visionEnabled()) throw new IllegalStateException("截图识别已在管理页关闭");
        LlmInvocation inv = s.vision();
        LlmCatalog.Platform p = inv.platformDef()
                .orElseThrow(() -> new IllegalStateException("视觉平台配置无效,请到管理页重新选择"));
        String model = inv.resolvedModel();
        if (model == null) throw new IllegalStateException(p.label() + " 的视觉型号需要手工填写");
        String key = apiKey(inv);
        if (key == null || key.isBlank()) throw new IllegalStateException(p.label() + " API key 未配置");

        String dataUrl = "data:" + (mime == null ? "image/jpeg" : mime) + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        return parse(callVision(p, key, model, dataUrl));
    }

    @SuppressWarnings("unchecked")
    private String callVision(LlmCatalog.Platform p, String key, String model, String dataUrl) {
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
                "max_tokens", 1500
        );
        if (testGate != null) testGate.beforeSend("EXPENSE_VISION_CLIENT");
        java.time.LocalDateTime dispatched = java.time.LocalDateTime.now();
        Map<String, Object> resp;
        try { resp = rt.postForObject(p.chatEndpoint(), new HttpEntity<>(body, h), Map.class); }
        finally { if (testGate != null) testGate.observedSend("EXPENSE_VISION_CLIENT", dispatched); }
        if (resp == null) throw new RuntimeException("视觉服务返回空");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("视觉服务无结果");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        Object c = msg == null ? null : msg.get("content");
        if (c == null) throw new RuntimeException("视觉服务空内容");
        return c.toString();
    }

    /**
     * 抽首个 JSON 数组(容忍 ```json 围栏)。
     *
     * <p>金额<b>原样带回字符串</b>,规整交给 {@code CsvBillParser.money} ——
     * 与文件通道共用同一个清洗函数,免得两条路对「￥1,280.00」的处理不一致。</p>
     */
    @SuppressWarnings("unchecked")
    static List<ShotRow> parse(String raw) {
        List<ShotRow> out = new ArrayList<>();
        if (raw == null) return out;
        int s = raw.indexOf('[');
        int e = raw.lastIndexOf(']');
        if (s < 0 || e <= s) return out;
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw.substring(s, e + 1), List.class);
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String cat = str(m.get("category"));
                if (cat == null || cat.isBlank()) continue;
                out.add(new ShotRow(cat.trim(), str(m.get("amount")),
                        str(m.get("channel")), str(m.get("confidence"))));
            }
        } catch (Exception ex) {
            log.warn("截图转写结果解析失败(不记内容)· {}", ex.getClass().getSimpleName());
        }
        return out;
    }

    /**
     * 把转写结果变成解析器的行,好走与文件通道完全相同的归类。
     *
     * <p><b>v1.21 第 2 稿:这条通道第一期不挂入口。</b> 截图转写产出的是渠道
     * <b>算好的分类汇总数</b>(「餐饮美食 ¥1,500」),而第 2 稿的载体是<b>逐笔流水</b> ——
     * 一张截图变不成 96 笔。两者对不上,硬塞进来会得到一堆没有日期、没有商户、
     * 没有交易号的假流水,而且无法去重(重传同一张图就出双份)。</p>
     *
     * <p>类保留、编译保留,等逐笔这条线跑顺了再决定它落成什么形态
     * (可能是「一张截图 = 一笔」,也可能干脆不做)。见 PRD §3.5。</p>
     */
    public static CsvBillParser.Parsed toParsed(List<ShotRow> rows) {
        List<BillRow> bills = new ArrayList<>();
        int bad = 0;
        for (ShotRow r : rows) {
            BigDecimal amt = CsvBillParser.money(r.amountRaw());
            if (amt == null) { bad++; continue; }
            /* 日期与交易号都是 null:截图上没有这两样。
             * 这正是它接不进逐笔载体的原因 —— 没有交易号就没法去重。 */
            bills.add(new BillRow(r.category(), r.channel(), null, "支出", amt, null, r.category(),
                    null, null, null));
        }
        return new CsvBillParser.Parsed(bills, "(截图转写)", 0, 0, 0, bad);
    }

    private String apiKey(LlmInvocation inv) {
        return inv.platformDef().map(p -> config.getString(FAMILY_ID, p.keyName(), "")).orElse("");
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
