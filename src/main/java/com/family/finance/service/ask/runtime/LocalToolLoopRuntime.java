package com.family.finance.service.ask.runtime;

import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolDispatcher;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.ask.AskToolResult;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.19 · <b>本地工具循环</b>:模型出网,工具在本进程里跑。
 *
 * <h3>为什么需要它</h3>
 * <p>选型定的是 Managed Agents({@link ManagedAgentRuntime}),那条路线上 agent 跑在百炼那边,
 * 要<b>回调</b>我们的 {@code /mcp} 取数 —— 于是实例必须<b>公网可达 + HTTPS</b>。
 * 而这是个自托管应用:相当一部分用户装在家里的 NAS、软路由后面、公司内网里,没有公网 IP,
 * 也没有域名和证书。对他们来说 Managed Agent 不是「配起来麻烦」,是<b>物理上不可能</b>。</p>
 *
 * <p>这条路线把方向反过来:我们主动出网调模型,工具调用在<b>本进程</b>里执行,
 * <b>零入网需求</b>。代价是 agent loop 得自己写(就是下面这几十行),
 * 以及没有服务端 session 持久化 —— 多轮靠我们自己带历史。</p>
 *
 * <h3>循环为什么是 5 轮</h3>
 * <p>不是拍的。成本测算(tech-design §六.4)里这个参数被证伪过一次:原定 8 轮,
 * 实测每多一轮就要把「之前所有工具返回」重新送一遍,token 是<b>累加</b>的 ——
 * 8 轮的单次成本是 5 轮的 6 倍多,而第 6 轮之后新增信息量已经很小。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalToolLoopRuntime implements AgentRuntime {

    public static final String CODE = "local";

    /** 工具调用轮数上限 —— 成本测算定的值,改之前先看 tech-design §六.4 */
    public static final int MAX_ROUNDS = 5;
    private final FamilyConfigService configService;
    private final AskToolRegistry registry;
    private final AskToolDispatcher dispatcher;
    private final LlmRouter router;
    private final ObjectMapper json = new ObjectMapper();

    @Override public String code() { return CODE; }

    @Override public String label() { return "本机直连(不需要公网)"; }

    @Override
    public boolean available(long familyId) {
        return router.available(familyId);
    }

    @Override
    public String unavailableReason(long familyId) {
        return router.unavailableReason(familyId);
    }

    // ──────────────────────── 主循环 ────────────────────────

    @Override
    public void run(AskTurn turn, AskSink target) {
        TrackingSink sink = new TrackingSink(target);
        if (!available(turn.familyId())) {
            sink.failed(unavailableReason(turn.familyId()));
            return;
        }

        // 对话上下文:system + 历史 + 本轮提问,之后每轮把工具结果追加进去
        List<Map<String, Object>> messages = new ArrayList<>();
        // v1.27 · 分析上下文拼在系统提示词后(本机模式每轮都发系统提示词,改了偏好下一句就生效)
        String system = turn.hasAnalysisContext()
                ? turn.systemPrompt() + "\n\n" + turn.analysisContext() : turn.systemPrompt();
        messages.add(Map.of("role", "system", "content", system));
        if (turn.hasAnalysisContext() && turn.onContextNote() != null) {
            turn.onContextNote().accept("本机 · 随系统提示词发出");
        }
        for (Msg m : turn.history()) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        messages.add(Map.of("role", "user", "content", turn.question()));
        List<Map<String, Object>> tools = registry.openAiToolList();
        int citeSeq = 0;

        try {
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                Completion c = routedStream(turn, system, messages, tools, sink);

                if (sink.cancelled()) { sink.stopped(); return; }

                if (c.toolCalls.isEmpty()) {
                    sink.done();
                    return;
                }

                // 这一轮的文字是调工具前的旁白,不是答案 —— 撤回它,移进思考过程那一栏。
                // 时机放在**确认有工具调用的那一刻**(而不是整轮工具都跑完之后):
                // 用户看到的是「话说完 → 立刻转成思考记录 → 开始查」,而不是干等几秒才跳一下。
                if (!c.text.isBlank()) sink.rollback(c.text);

                // 把模型这一步的 tool_calls 原样放回上下文(不放回去,下一轮它会重复调同一个工具)
                Map<String, Object> assistantTurn = new LinkedHashMap<>();
                assistantTurn.put("role", "assistant");
                assistantTurn.put("content", c.text.isEmpty() ? null : c.text);
                assistantTurn.put("tool_calls", c.rawToolCalls);
                messages.add(assistantTurn);

                for (ToolCall tc : c.toolCalls) {
                    if (sink.cancelled()) { sink.stopped(); return; }
                    AskTool def = registry.find(tc.name).orElse(null);
                    String label = def == null ? tc.name : registry.displayName(tc.name);
                    Map<String, Object> args = parseArgs(tc.argsJson);
                    sink.toolStart(tc.name, label, argsBrief(args));

                    long t0 = System.currentTimeMillis();
                    AskToolResult r = dispatcher.call(turn.familyId(), tc.name, args, turn.scope());
                    int ms = (int) (System.currentTimeMillis() - t0);

                    // 给每个可引用数字发一个会话内唯一的 key —— 不同工具的 key 会撞(都叫 nw/ta)
                    Map<String, AskToolResult.Cite> citable = new LinkedHashMap<>();
                    for (AskToolResult.Cite cite : r.citations()) {
                        citable.put("c" + (++citeSeq), cite);
                    }
                    sink.toolDone(tc.name, label, ms, r.ok(),
                            r.ok() ? r.summary() : r.error(), citable);

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", tc.id,
                            "content", toolPayload(r, citable)));
                }

                if (round == MAX_ROUNDS) {
                    // 查够了就该说话了。硬止住并要求它用已有材料作答,好过静默截断
                    messages.add(Map.of("role", "user", "content",
                            "已经查了 " + MAX_ROUNDS + " 轮,不要再调工具了。"
                          + "用现在手上的数据回答;确实没查到的部分,如实说没查到。"));
                    Completion last = routedStream(turn, system, messages, List.of(), sink);
                    if (sink.cancelled()) { sink.stopped(); return; }
                    if (last.text.isBlank()) sink.textDelta("这个问题我查了几轮还是没凑齐材料,换个问法试试?");
                    sink.done();
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("超级 Agent · 本地循环失败:{}", e.toString());
            if (sink.cancelled()) sink.stopped();
            else sink.failed(humanError(e));
        }
    }

    private Completion routedStream(AskTurn turn, String system, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, TrackingSink sink) throws Exception {
        Exception last = null;
        for (LlmInvocation inv : router.plan(turn.familyId())) {
            if (sink.cancelled()) return new Completion();
            if (router.clientFor(inv.platform()).filter(c -> c.available()).isEmpty()) continue;
            LlmCatalog.Platform platform = inv.platformDef().orElseThrow();
            String key = configService.getString(turn.familyId(), platform.keyName(), "");
            try {
                if (turn.onPromptSent() != null)
                    turn.onPromptSent().sent(system, turn.question(), inv.badge(), false);
            } catch (Exception e) {
                log.debug("记录 Agent 提示词失败:{}", e.getClass().getSimpleName());
            }
            try {
                Completion result = streamOnce(platform, key, inv.resolvedModel(), messages, tools, sink,
                        LlmRouter.timeoutSeconds(configService, turn.familyId()));
                if (!sink.cancelled()) router.externalSuccess(inv);
                return result;
            } catch (Exception e) {
                if (sink.cancelled()) throw e;
                router.externalFailure(inv, e);
                if (sink.delivered) throw e;
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IllegalStateException("没有可用的模型候选");
    }

    /** 只要这轮对话已有用户可见产出就不重放,工具结果同样算产出。 */
    private static final class TrackingSink implements AskSink {
        private final AskSink target;
        private boolean delivered;
        TrackingSink(AskSink target) { this.target = target; }
        public void status(String text) { target.status(text); }
        public void toolStart(String name, String label, String args) { delivered = true; target.toolStart(name, label, args); }
        public void toolDone(String name, String label, int ms, boolean ok, String summary,
                             Map<String, AskToolResult.Cite> cites) { delivered = true; target.toolDone(name, label, ms, ok, summary, cites); }
        public void textDelta(String text) { if (!text.isEmpty()) delivered = true; target.textDelta(text); }
        public void rollback(String text) { target.rollback(text); }
        public boolean cancelled() { return target.cancelled(); }
        public void done() { target.done(); }
        public void stopped() { target.stopped(); }
        public void failed(String message) { target.failed(message); }
    }

    // ──────────────────────── 单次流式请求 ────────────────────────

    /** 一次 chat/completions 的产出:正文 + 它要调的工具 */
    private static final class Completion {
        final StringBuilder textBuf = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final List<Map<String, Object>> rawToolCalls = new ArrayList<>();
        String text = "";
    }

    private record ToolCall(String id, String name, String argsJson) {}

    private Completion streamOnce(LlmCatalog.Platform platform, String key, String model,
                                  List<Map<String, Object>> messages,
                                  List<Map<String, Object>> tools, AskSink sink, int timeoutSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", 0.3);        // 讲数字的场合,发挥空间越小越好
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        var connection = (java.net.HttpURLConnection) URI.create(endpoint(platform)).toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(timeoutSeconds * 1000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);
        Completion c = new Completion();
        // 累积中的 tool_call:流式协议按 index 分片送达,name 和 arguments 分开来
        Map<Integer, String[]> pending = new LinkedHashMap<>();   // index → [id, name, argsBuf]

        boolean complete = false;
        try {
            try (var output = connection.getOutputStream()) {
                output.write(json.writeValueAsBytes(body));
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                String detail = "";
                if (connection.getErrorStream() != null) try (var error = connection.getErrorStream()) {
                    detail = new String(error.readNBytes(16_384), StandardCharsets.UTF_8);
                }
                throw new UpstreamException(status, detail);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // 每一行都问一次:按下停止到真的停下,最多差一个 token 的时间。
                // 放在外层循环检查的话,用户得等这一整轮流完 —— 那正是他想跳过的东西。
                if (sink.cancelled()) break;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) { complete = true; break; }

                var node = json.readTree(payload);
                if (node.has("error")) throw new UpstreamException(502, "流式回答返回错误");
                var choices = node.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;
                if (choices.get(0).hasNonNull("finish_reason")) complete = true;
                var delta = choices.get(0).path("delta");

                String piece = delta.path("content").asText("");
                if (!piece.isEmpty()) {
                    c.textBuf.append(piece);
                    sink.textDelta(piece);
                }

                var tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    for (var tc : tcs) {
                        int idx = tc.path("index").asInt(0);
                        String[] slot = pending.computeIfAbsent(idx, k -> new String[]{null, null, ""});
                        if (tc.hasNonNull("id")) slot[0] = tc.get("id").asText();
                        var fn = tc.path("function");
                        if (fn.hasNonNull("name")) slot[1] = fn.get("name").asText();
                        if (fn.hasNonNull("arguments")) slot[2] = slot[2] + fn.get("arguments").asText();
                    }
                }
            }
            }
        } finally {
            connection.disconnect();
        }
        if (!sink.cancelled() && (!complete || (c.textBuf.isEmpty() && pending.isEmpty())))
            throw new java.io.IOException("模型回答未完整结束");

        c.text = c.textBuf.toString();
        for (var e : pending.entrySet()) {
            String[] s = e.getValue();
            if (s[1] == null) continue;
            String id = s[0] == null ? ("call_" + e.getKey()) : s[0];
            c.toolCalls.add(new ToolCall(id, s[1], s[2]));
            c.rawToolCalls.add(Map.of("id", id, "type", "function",
                    "function", Map.of("name", s[1], "arguments", s[2])));
        }
        return c;
    }

    // ──────────────────────── 辅助 ────────────────────────

    /**
     * 工具结果送回模型的形态。
     *
     * <p>关键是 {@code citable} 那段:把「这些数字你要引用就写 {@code {{cite:c1}}}」
     * 明确交代给模型。它不需要、也不应该自己把数字抄进正文 —— 抄写就是出错的机会,
     * 而这个功能唯一不能出的错就是数字错。</p>
     */
    private String toolPayload(AskToolResult r, Map<String, AskToolResult.Cite> citable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        if (!r.ok()) {
            out.put("error", r.error());
            if (r.meta().containsKey("allowed")) out.put("allowed", r.meta().get("allowed"));
        } else {
            out.put("data", r.data());
            out.put("meta", r.meta());
            if (!citable.isEmpty()) {
                Map<String, Object> cs = new LinkedHashMap<>();
                citable.forEach((k, c) -> cs.put(k, Map.of(
                        "label", c.label(), "value", c.valueText(),
                        "period", c.periodId() == null ? "" : String.valueOf(c.periodId()),
                        "inProgress", c.inProgress())));
                out.put("citable", cs);
                out.put("citeHowTo",
                        "要在正文里说这些数字,必须写成 {{cite:key}}(例如 {{cite:"
                      + citable.keySet().iterator().next() + "}}),"
                      + "系统会替换成带出处的数值。不要自己把数字抄进正文。");
            }
        }
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }

    /**
     * 参数摘要,给思考过程那一栏看。
     *
     * <p>只取键值、不做嵌套展开 —— 这一行是给人扫一眼的,不是给人调试的。
     * 太长会把思考区撑成一片 JSON,反而看不出重点。</p>
     */
    private static String argsBrief(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            String v = String.valueOf(e.getValue());
            if (v.length() > 40) v = v.substring(0, 40) + "…";
            sb.append(e.getKey()).append('=').append(v);
            if (sb.length() > 120) { sb.append(" …"); break; }
        }
        return sb.toString();
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(argsJson, Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();   // 交给 dispatcher 走「参数不对」那条路,把可用取值回给模型
        }
    }

    protected String endpoint(LlmCatalog.Platform platform) { return platform.chatEndpoint(); }

    /** 上游错误翻成人话 —— 直接展示给用户,不能是一串状态码 */
    private String humanError(Exception e) {
        if (e instanceof UpstreamException u) {
            String b = u.body == null ? "" : u.body.toLowerCase(java.util.Locale.ROOT);
            if (u.status == 401 || b.contains("invalid_api_key")) {
                return "大模型密钥不对。去「AI 接入」页确认一下。";
            }
            if (u.status == 402 || b.contains("arrearage") || b.contains("insufficient_quota")
                    || b.contains("allocationquota")) {
                return "大模型这边额度用完了或者欠费了。充值后再试,或者在「AI 接入」换一个模型。";
            }
            if (u.status == 429) return "问得太频繁,模型那边限流了。等一会儿再问。";
            return "模型那边返回了错误(" + u.status + ")。稍后再试试。";
        }
        if (e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.SocketTimeoutException) {
            return "等模型回话超时了。这个问题可能有点大,拆小一点再问试试。";
        }
        return "连不上大模型。检查一下服务器能不能出网。";
    }

    private static final class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("upstream " + status);
            this.status = status;
            this.body = body;
        }
    }
}
