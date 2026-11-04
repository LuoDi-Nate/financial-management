package com.family.finance.service.expense.imports;

import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 FR-562③ · 归类三层里的<b>最后一层</b>:把认不出来的商户名交给模型猜。
 *
 * <h3>只送商户名,不送金额、不送时间(FR-563)</h3>
 *
 * <p>这是硬红线,有两个独立的理由:</p>
 * <ul>
 *   <li><b>隐私</b> —— 账单是整月的消费流水,把「9 月 3 日花了 32.5 元」送出去,
 *       和把「在瑞幸买过东西」送出去,是完全不同量级的暴露。</li>
 *   <li><b>{@code feedback_llm_no_math}</b> —— 模型一旦拿到金额就会自己算占比、
 *       做汇总、给建议。那条「LLM 严禁做数学」的铁律是生产事故换来的。
 *       它这里的任务只有一件:<b>这个名字属于哪一类</b>。</li>
 * </ul>
 *
 * <p>所以入参是 {@code List<String>} 商户名,而不是 {@code List<BillRow>} ——
 * <b>类型上就拿不到金额</b>。护栏 {@code v1210-AI-ONLY-MERCHANT} 扫这条:
 * 靠自觉不行,签名里没有那个字段才行。</p>
 *
 * <h3>没配 key 也要能用</h3>
 *
 * <p>{@link #available()} 为 false 时整层跳过,那些笔落「其他」等用户手改。
 * AI 是<b>加速器不是前置条件</b> —— 自建用户里相当一部分不会去配 key,
 * 让功能依赖它等于把这些人排除在外。</p>
 *
 * <h3>为什么不复用 ExpenseShotClient</h3>
 *
 * <p>那个是<b>视觉</b>通道(转写截图),这个是纯文本通道;提示词纪律也不同 ——
 * 那边是「只转写不计算」,这边是「只在给定清单里选一个」。共享的只是 HTTP 形状。</p>
 */
@Component
@Slf4j
public class MerchantAiClassifier {

    private static final long FAMILY_ID = 1L;   // 单家庭设计

    /** 一次最多送这么多个商户名。再多就分批 —— 单次请求过长时模型会开始丢条目。 */
    static final int BATCH = 40;

    /** 去重后仍超过这个数就不送了 —— 一次导入烧掉几十次调用不值得,剩下的落「其他」 */
    static final int MAX_TOTAL = 200;

    private static final String SYS =
            "你是消费分类器。用户会给你一份【分类清单】和一批【商户名】,"
            + "你只能为每个商户名从清单里挑一个最合适的分类。"
            + "绝不计算、求和、推导任何数值 —— 你看不到金额,也不需要金额。"
            + "拿不准就返回 null,不要硬猜。输出严格 JSON,不要 markdown 围栏。";

    private final LlmRouter router;
    private static final ObjectMapper JSON = new ObjectMapper();

    public MerchantAiClassifier(LlmRouter router) { this.router = router; }

    public boolean available() {
        return router.available(FAMILY_ID);
    }

    /**
     * 猜。
     *
     * @param merchants 商户名(<b>只有名字</b>)
     * @param catNames  可选分类名清单 —— 模型只能在这里面挑,挑不到返回 null
     * @return 商户名 → 分类名;猜不出来的<b>不出现在结果里</b>(而不是给一个兜底值,
     *         那样调用方就分不清「AI 猜的」和「兜底」了,而确认页要把这两类分开标)
     */
    public Map<String, String> classify(List<String> merchants, List<String> catNames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (merchants == null || merchants.isEmpty() || catNames == null || catNames.isEmpty()) return out;
        if (!available()) return out;

        List<String> uniq = merchants.stream().distinct().limit(MAX_TOTAL).toList();
        for (int i = 0; i < uniq.size(); i += BATCH) {
            List<String> batch = uniq.subList(i, Math.min(uniq.size(), i + BATCH));
            try {
                out.putAll(callOnce(batch, catNames));
            } catch (Exception e) {
                /* 一批失败不影响其余批 —— 那些商户落「其他」等手改,比整个导入失败好得多。
                 * 只记「哪一步 / 什么原因」,不记商户名本身(账单内容不进日志)。 */
                log.warn("商户 AI 归类失败(第 {} 批 · {} 个)· {}", i / BATCH + 1, batch.size(), e.toString());
            }
        }
        return out;
    }

    private Map<String, String> callOnce(List<String> merchants, List<String> catNames) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("分类清单(只能从这里面挑):\n");
        for (String c : catNames) prompt.append("- ").append(c).append('\n');
        prompt.append("\n商户名:\n");
        for (int i = 0; i < merchants.size(); i++) {
            prompt.append(i + 1).append(". ").append(merchants.get(i)).append('\n');
        }
        prompt.append("\n为每个商户名挑一个分类。输出 JSON 数组,每个元素:")
              .append("{\"merchant\":\"原样的商户名\",\"category\":\"清单里的分类名,拿不准填 null\"}。")
              .append("不要输出金额、不要计算、不要解释,只输出 JSON 数组本身。");

        var trace = PromptTrace.of(PromptSurface.MERCHANT_CLASSIFY);
        Map<String, String> result = router.invoke(FAMILY_ID, trace, SYS, prompt.toString(), (inv, raw, ms) -> {
            Map<String, String> parsed = parse(raw, catNames);
            parsed.keySet().retainAll(merchants);
            if (parsed.isEmpty() && !raw.trim().matches("(?s).*\\[\\s*].*")) {
                trace.rejected("分类回答无法解析或不在允许清单中");
                return null;
            }
            return parsed;
        });
        return result == null ? Map.of() : result;
    }

    /** 解析。模型给的分类名<b>必须在清单里</b>,否则丢弃 —— 它偶尔会自己发明一个类目名。 */
    static Map<String, String> parse(String raw, List<String> catNames) {
        Map<String, String> out = new LinkedHashMap<>();
        String t = raw == null ? "" : raw.trim();
        // 去掉 ```json 围栏(交代过不要,但模型时不时还是会加)
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        int lb = t.indexOf('['), rb = t.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return out;
        try {
            JsonNode arr = JSON.readTree(t.substring(lb, rb + 1));
            List<String> allow = new ArrayList<>(catNames);
            for (JsonNode n : arr) {
                String m = text(n.get("merchant"));
                String cat = text(n.get("category"));
                if (m == null || cat == null) continue;
                if (!allow.contains(cat)) continue;   // 模型发明的类目名,丢掉
                out.put(m, cat);
            }
        } catch (Exception e) {
            // 解析失败等于这一批没猜出来 —— 调用方会让它们落「其他」
        }
        return out;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String v = n.asText("").trim();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : v;
    }

}
