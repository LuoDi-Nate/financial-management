package com.family.finance.service.ask.runtime;

import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.family.finance.service.config.FamilyConfigService.*;

/**
 * v1.19 · 百炼 Managed Agents 运行时(选型一的选定方案,tech-design 附录·选型二)。
 *
 * <p>Agent 跑在百炼那边,取数时<b>回调</b>本实例的 {@code /mcp}。换来的是服务端 session 持久化、
 * 中断续接、多步工具编排 —— 这些是维护者点名要的能力,自己写 loop 给不了。</p>
 *
 * <h3>前置条件(硬性,不是建议)</h3>
 * <ol>
 *   <li>本实例<b>公网可达且 HTTPS</b> —— 百炼要连得上 {@code /mcp};</li>
 *   <li>用户已在百炼控制台<b>手工注册</b>自定义 MCP 服务并拿到服务 ID ——
 *       {@code mcp_servers} 只能<b>引用已注册服务</b>,而注册<b>没有公开 API</b>。
 *       这是依赖方的结构限制,不是我们能省掉的步骤。</li>
 * </ol>
 *
 * <p><b>本类的云端往返尚未在真实环境跑通</b>:beta 只有 IP、没有域名和证书,百炼回调不到。
 * 代码按已查证的接口形态实现,但「实际跑通」这件事必须等一个有公网 HTTPS 的环境 ——
 * 在那之前不要在任何地方把它描述成已验证。没有公网的部署走 {@link LocalToolLoopRuntime}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagedAgentRuntime implements AgentRuntime {

    public static final String CODE = "managed";

    private static final long FAMILY_ID = 1L;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    private final FamilyConfigService configService;
    private final AskToolRegistry registry;
    /** v1.19.15 · 自检要看的是**我们自己的**入站记录,不是问模型 —— 模型会编 */
    private final com.family.finance.repository.AskAuditMapper auditMapper;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override public String code() { return CODE; }

    @Override public String label() { return "百炼托管 Agent(需要公网 HTTPS)"; }

    @Override
    public boolean available(long familyId) {
        return unavailableReason(familyId) == null;
    }

    @Override
    public String unavailableReason(long familyId) {
        if (apiKey().isBlank()) return "还没有配百炼的 API Key。去「数据源接入」页填一个。";
        if (workspace().isBlank()) return "还没填百炼的业务空间 ID。在「AI 接入」页填一下。";
        String base = configService.getString(FAMILY_ID, K_ASK_PUBLIC_BASE_URL, "");
        if (base.isBlank()) {
            return "还没填本站的公网地址。托管 Agent 跑在百炼那边,要能回头访问你这台机器才取得到数。";
        }
        if (!base.startsWith("https://")) {
            return "本站公网地址必须是 https —— 百炼不接受 http 回调。没有证书的话,用「本机直连」那条路线。";
        }
        if (mcpServerId().isBlank()) {
            return "还差最后一步:去百炼控制台把 MCP 服务注册好,把服务 ID 填回来。"
                 + "(这一步百炼没开放接口,只能手工)";
        }
        if (agentId().isBlank()) return "还没有创建 Agent。在「AI 接入」页点一下「创建 Agent」。";
        return null;
    }

    // ──────────────────────── 会话 ────────────────────────

    @Override
    public void run(AskTurn turn, AskSink sink) {
        String reason = unavailableReason(turn.familyId());
        if (reason != null) { sink.failed(reason); return; }

        try {
            String sessionId = turn.providerRef();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = createSession();
                turn.onProviderRef().accept(sessionId);
            }
            // 三步,缺一不可:追加事件 → 拿到它的 id → 从这个 id 之后读流。
            // 「追加」和「读答案」是两个不同的请求,这是百炼这套接口的形状(见 appendUserMessage)。
            String afterId = appendUserMessage(sessionId, turn.question());
            streamAfter(sessionId, afterId, sink);
        } catch (Exception e) {
            log.warn("超级 Agent · 托管 agent 失败:{}", e.toString());
            sink.failed(humanError(e));
        }
    }

    /**
     * v1.19.14 · 建会话的字段名是 <b>{@code agent}</b>,不是 {@code agent_id}。
     * 百炼原话:{@code Missing required field: 'agent'}。字符串和 {@code {"id":…}} 对象都收,这里用字符串。
     */
    private String createSession() throws Exception {
        JsonNode n = post(agentBase() + "/sessions", Map.of("agent", agentId()));
        String id = firstText(n, "session_id", "sessionId", "id");
        if (id == null) throw new IllegalStateException("百炼没有返回 session_id");
        return id;
    }

    /**
     * v1.19.14 · 把用户这句话作为一个 <b>message 事件</b>追加进会话,返回这个事件的 id。
     *
     * <p>形状是百炼逐条纠正出来的(每次它都明确说了缺什么):</p>
     * <ol>
     *   <li>{@code Field 'input' must be an array} —— {@code input} 是<b>事件数组</b>,不是单个对象</li>
     *   <li>{@code type must be one of: define_outcome, function_call_output, interrupt, message,
     *       tool_approval_response, tool_call_output} —— 每个事件要带 {@code type}</li>
     *   <li>{@code 'content' must be a non-empty array} —— {@code content} 是
     *       <b>内容块数组</b>({@code [{"type":"text","text":…}]}),不是字符串</li>
     * </ol>
     *
     * <p><b>这个请求不产出答案。</b>它只把事件写进会话(响应体就是刚写进去的那条的回显)。
     * 原来的代码把它当成流来读,而它的 {@code Content-Type} 始终是 {@code application/json} ——
     * 就算按官方文档加上 {@code Accept: text/event-stream} 也一样(实测)。
     * 答案要从 {@link #streamAfter} 那条独立的 SSE 端点读。</p>
     */
    private String appendUserMessage(String sessionId, String question) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "message");
        event.put("role", "user");
        event.put("content", List.of(Map.of("type", "text", "text", question)));
        JsonNode n = post(agentBase() + "/sessions/" + sessionId + "/events",
                Map.of("input", List.of(event)));
        String id = firstText(n.path("data").path(0), "id", "event_id");
        if (id == null) throw new IllegalStateException("百炼没有回显刚追加的事件 id");
        return id;
    }

    /**
     * 消费事件流。
     *
     * <p>v1.19.14 · 走 <b>{@code GET /sessions/{id}/events/stream}</b> —— 这是唯一真的会
     * 返回 {@code text/event-stream} 的端点。</p>
     *
     * <p><b>{@code after_id} 不是优化,是正确性</b>:这个流<b>默认重放会话的全部历史</b>。
     * 而我们的会话是跨轮复用的(providerRef 存着它,这正是选托管路线的理由),
     * 不带 {@code after_id} 就会把前面每一轮的答案重新吐一遍 —— 用户会看到答案里混进上一轮的话。
     * 传刚追加的那条用户事件 id,就只拿本轮的新事件。</p>
     *
     * <p>事件里的工具调用是<b>百炼那边发起的</b>(它直连我们的 {@code /mcp}),
     * 我们只是从流里看到「它调了什么」,好把进度显示给用户。所以这里没有 dispatcher ——
     * 工具已经在 {@code /mcp} 那条路径上执行过,连同鉴权和审计。</p>
     */
    private void streamAfter(String sessionId, String afterEventId, AskSink sink) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        agentBase() + "/sessions/" + sessionId + "/events/stream?after_id=" + afterEventId))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey())
                .header("Accept", "text/event-stream")
                .GET().build();

        HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new UpstreamException(resp.statusCode(),
                    new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
        }

        boolean gotAnswer = false;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sink.cancelled()) { sink.stopped(); return; }
                if (!line.startsWith("data:")) continue;   // 还有 `id:` / `event:` / `:注释` 三种行
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                JsonNode n = json.readTree(payload);
                String type = firstText(n, "type", "event", "object");
                if (type == null) continue;

                if ("session_status".equals(type)) {
                    // 终止信号藏在 content[].data.session_status 里(idle / terminated)。
                    // 读不出来时**只在已经拿到答案后**才收 —— 不然多步工具调用会被截断;
                    // 真卡住有 READ_TIMEOUT 兜底。
                    String st = sessionStatus(n);
                    if ("idle".equals(st) || "terminated".equals(st) || (st == null && gotAnswer)) break;
                    continue;
                }
                if (type.contains("tool") || type.contains("function_call")) {
                    String tool = firstText(n.path("tool_call"), "name", "tool_name");
                    if (tool == null) tool = firstText(n, "name", "tool_name");
                    if (tool == null) tool = firstText(n.path("content").path(0).path("data"), "name", "tool_name");
                    if (tool != null) {
                        String label = registry.displayName(tool);
                        if (type.contains("output") || type.contains("done")
                                || type.contains("completed") || type.contains("result")) {
                            sink.toolDone(tool, label, 0, true, null, Map.of());
                        } else {
                            sink.toolStart(tool, label, null);
                        }
                    }
                } else if ("message".equals(type) && "assistant".equals(firstText(n, "role"))) {
                    // 答案是**整条**发过来的(status=completed),不是逐字 delta
                    String text = textOf(n.path("content"));
                    if (!text.isEmpty()) { sink.textDelta(text); gotAnswer = true; }
                } else if (type.contains("delta") || type.contains("output_text")) {
                    // 百炼哪天真给逐字增量了也能收 —— 多留这一支不花什么代价
                    String piece = firstText(n, "delta", "text");
                    if (piece != null && !piece.isEmpty()) { sink.textDelta(piece); gotAnswer = true; }
                } else if (type.contains("error")) {
                    sink.failed("百炼那边报了个错:" + firstText(n, "message", "error"));
                    return;
                }
                // reasoning / model_request_start / model_request_end 是进度,正文里不展示
            }
        }
        sink.done();
    }

    /** {@code content: [{"type":"data","data":{"session_status":"idle", …}}]} */
    static String sessionStatus(JsonNode event) {
        for (JsonNode block : event.path("content")) {
            String st = firstText(block.path("data"), "session_status", "status");
            if (st != null) return st;
        }
        return firstText(event, "session_status");
    }

    /** {@code content: [{"type":"text","text":"…"}]} —— 把所有 text 块拼起来 */
    static String textOf(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String t = firstText(block, "text");
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    // ──────────────────────── Agent 生命周期(管理页触发) ────────────────────────

    /**
     * v1.19.13 · 百炼收系统提示词的字段名是 <b>{@code system}</b>,不是 {@code instructions}。
     *
     * <p>怎么发现的:创建明明成功了(HTTP 200 + 有 agent_id),但 {@code GET /agents/{id}} 回来的对象里
     * <b>{@code "system": null}</b>,而 {@code instructions} 这个键<b>根本不在响应里</b>。
     * 百炼对不认识的字段是<b>静默忽略</b>的 —— 于是线上那个 agent 挂上了 MCP、却<b>一句系统提示词都没有</b>:
     * 它能调工具,但不知道自己是谁、不知道「不许做数学 / 不许换算币种 / 拿不准先调 capabilities」这些口径纪律。</p>
     *
     * <p>这类错误<b>不报错、不降级、看起来完全成功</b>,所以下面 {@link #verifyTemplate} 会回读一次确认。</p>
     */
    private static final String PROMPT_FIELD = "system";

    private static final String AGENT_NAME = "家庭资产超级 Agent";

    /**
     * 创建与更新共用的请求体。
     *
     * <p>合成一份是必须的:百炼的更新是<b>全量替换</b>(缺省字段视为清空),
     * 而这两处原来各写一份 —— v1.19.11 的两个形状 bug 就是「改了一处漏了另一处」的同型风险。</p>
     *
     * <p>{@code mcp_servers} 里<b>只能写引用</b>({@code type} + {@code name}),
     * 不能内联 url 和 headers —— 这就是为什么用户必须先去控制台注册。</p>
     */
    private Map<String, Object> agentBody(String systemPrompt, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", AGENT_NAME);
        // v1.19.11 · model 是**对象**不是字符串。百炼原话:
        //   Cannot construct instance of `DashModelConfigDTO` … from String value ('qwen-plus')
        //   (through reference chain: DashCreateAgentRequest["model"])
        body.put("model", Map.of("id", model == null || model.isBlank() ? configuredModel() : model));
        body.put(PROMPT_FIELD, systemPrompt);
        // v1.19.11 · type 是 **customer** 不是 custom。百炼直接给了合法值:
        //   mcpServers[0].type 取值非法: custom,合法值: [official, customer]
        body.put("mcp_servers", List.of(Map.of("type", "customer", "name", mcpServerId())));
        return body;
    }

    /** 创建 Agent */
    public String createAgent(String systemPrompt, String model) throws Exception {
        JsonNode n = post(agentBase() + "/agents", agentBody(systemPrompt, model));
        String id = firstText(n, "agent_id", "agentId", "id");
        if (id == null) throw new IllegalStateException("百炼没有返回 agent_id");
        String ver = firstText(n, "version", "agent_version");
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_ID, id);
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver == null ? "1" : ver);
        // id 先落库再回读:万一回读这一步失败(网络/超时),agent 已经建出来了,
        // 下次点按钮要走「更新」而不是再建一个。
        verifyTemplate(id);
        return id;
    }

    /**
     * 更新 Agent 模板。
     *
     * <p>v1.19.13 · 动词是 <b>{@code POST /agents/{id}}</b>。原来写的 {@code PUT} 百炼直接回
     * <b>405 请求方法不支持</b>({@code PATCH} 也一样)—— 而这条 405 出现在用户已经创建成功之后,
     * 于是页面上写着「创建失败」,他以为整条路线还没通,其实 agent 早就建好了。</p>
     *
     * <p><b>全量替换</b>:缺省字段视为清空,所以每次都把 name/model/{@value #PROMPT_FIELD}/
     * mcp_servers 全带上 —— 少带一个就是把它删了,而且删得静悄悄。{@code version} 是必填(乐观锁),
     * 缺了百炼回「version 不能为空」。</p>
     *
     * <p>已存在的会话<b>锁定创建时的 version</b>,不受本次更新影响。</p>
     */
    public void updateAgent(String systemPrompt, String model) throws Exception {
        Map<String, Object> body = agentBody(systemPrompt, model);
        body.put("version", configService.getString(FAMILY_ID, K_ASK_MA_AGENT_VERSION, "1"));
        JsonNode n = post(agentBase() + "/agents/" + agentId(), body);
        String ver = firstText(n, "version", "agent_version");
        if (ver != null) configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver);
        verifyTemplate(agentId());
    }

    /**
     * v1.19.13 · 回读确认模板真的存住了。
     *
     * <p>加它的直接原因见 {@link #PROMPT_FIELD}:字段名发错时百炼<b>静默忽略</b>,
     * 创建返回 200、有 agent_id,一切看起来都成功 —— 而 agent 是个空壳。
     * <b>「上游收下了」不等于「上游存住了」</b>,凡是靠字段名约定的写入都得回读一次。</p>
     */
    private void verifyTemplate(String id) throws Exception {
        JsonNode a = get(agentBase() + "/agents/" + id);
        boolean hasPrompt = !a.path(PROMPT_FIELD).asText("").isBlank();
        boolean hasMcp = a.path("mcp_servers").isArray() && !a.path("mcp_servers").isEmpty();
        if (hasPrompt && hasMcp) return;
        throw new IllegalStateException(
                "百炼收下了(HTTP 200)但没存住:系统提示词" + (hasPrompt ? "在" : "是空的")
                + " · MCP 引用" + (hasMcp ? "在" : "是空的")
                + "。这通常意味着请求里的字段名和百炼当前的约定对不上 —— 它对不认识的字段是静默忽略的。");
    }

    /**
     * v1.19.15 · 一键自检:<b>百炼到底连上我的账房了吗?</b>
     *
     * <p>加它的理由是一次真实的踩坑:整条链路全部打通(会话建得起来、答案流得回来),
     * 但智能体说「我这边只有文件类工具,没有数据查询入口」。
     * 百炼引用 MCP 服务这件事<b>是被校验的</b>(填个不存在的名字会直接
     * {@code 400 MCP Server 校验失败}),所以引用是对的;可它<b>运行时连不上也不报错</b> ——
     * 只是让模型在没有工具的情况下作答。用户看到的是一段像模型犯傻的话。</p>
     *
     * <p>判据不问模型(它会编),而是<b>看我们自己的审计表</b>:
     * 这一问期间有没有来自百炼的入站调用。有 = 通了;没有 = 它根本没来过。</p>
     *
     * @return 给用户看的一句话结论
     */
    public String testMcpLink() {
        if (agentId().isBlank()) return "还没创建 Agent,先点上面那个按钮。";
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        StringBuilder answer = new StringBuilder();
        boolean[] sawTool = {false};
        try {
            String sid = createSession();
            String afterId = appendUserMessage(sid,
                    "只做一件事:调用 capabilities 工具。然后只回工具名,不要别的,不要报任何金额。");
            streamAfter(sid, afterId, new ProbeSink(answer, sawTool));
        } catch (Exception e) {
            log.warn("MCP 连通自检失败", e);
            return "自检没跑完:" + humanError(e);
        }
        int inbound = auditMapper.countUpstreamCallsSince(FAMILY_ID, since);
        if (inbound > 0) {
            return "通了。这一问期间百炼访问了你的账房 " + inbound + " 次"
                 + (sawTool[0] ? "(流里也看到了工具调用)" : "")
                 + " —— 说明 MCP 那条线是活的。";
        }
        // flash 按纯文本渲染,文案里不能有 markdown 记号 —— 会原样显示成星号。
        // v1.19.12 的 hint() 刚踩过一次,所以这条也进了护栏。
        return "不通:这一问期间百炼一次都没有访问你的账房。"
             + "会话建起来了、答案也回来了,所以不是凭据、地址或 Agent 的问题;"
             + "MCP 引用也是对的 —— 名字填错百炼会直接拒绝创建 Agent。"
             + "剩下的只有一处:那个 MCP 服务不在「部署成功」状态。"
             + "去百炼「MCP 管理 → 自定义服务」看它的状态;改过配置的话必须"
             + "「停止部署 → 重新部署」才生效。不要去动服务 ID。";
    }

    /** 自检用的最小 sink:只关心「有没有工具调用」和「答案回来了没有」 */
    private record ProbeSink(StringBuilder text, boolean[] sawTool) implements AskSink {
        @Override public void status(String t) { }
        @Override public void toolStart(String toolName, String label, String args) { sawTool[0] = true; }
        @Override public void toolDone(String toolName, String label, int durationMs, boolean ok,
                                       String summary,
                                       Map<String, com.family.finance.service.ask.AskToolResult.Cite> citable) {
            sawTool[0] = true;
        }
        @Override public void textDelta(String delta) { text.append(delta); }
        @Override public void rollback(String narration) { }
        @Override public boolean cancelled() { return false; }
        @Override public void done() { }
        @Override public void stopped() { }
        @Override public void failed(String humanMessage) { text.append("[失败] ").append(humanMessage); }
    }

    /** 给用户去百炼控制台粘贴的 MCP 配置 —— 明文口令只在生成那一屏出现一次 */
    public String mcpConfigJson(String baseUrl, String plaintextToken) {
        // 逐个 put,不用 Map.of —— Map.of 不保证顺序,生成出来 headers 会跑到 type 前面。
        // 这段是给人复制粘贴的,字段顺序乱掉虽然不影响解析,但读起来像是随手拼的。
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "streamableHttp");
        entry.put("url", baseUrl + "/mcp");
        entry.put("headers", Map.of("Authorization", "Bearer " + plaintextToken));
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("family-finance", entry);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mcpServers", servers);
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ──────────────────────── 底层 ────────────────────────

    private JsonNode post(String url, Object body) throws Exception { return send("POST", url, body); }

    /** 回读用。不带 body —— 有些网关对带 body 的 GET 会直接拒 */
    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new UpstreamException(resp.statusCode(), resp.body());
        return json.readTree(resp.body());
    }

    private JsonNode send(String method, String url, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey())
                .method(method, HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new UpstreamException(resp.statusCode(), resp.body());
        return json.readTree(resp.body());
    }

    /**
     * 从几个候选字段名里取第一个有值的。
     *
     * <p>不是偷懒 —— 百炼这套接口还在演进(Assistant API 已经「下线中」),
     * 字段名在文档和实际返回之间出现过不一致。钉死一个名字的代价是接口一变就静默返回 null,
     * 而那会表现成「会话创建成功但 id 是空的」这种很难查的样子。</p>
     */
    private static String firstText(JsonNode n, String... names) {
        if (n == null || n.isMissingNode()) return null;
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asText();
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }

    private String apiKey() {
        return configService.getString(FAMILY_ID, LlmCatalog.DASHSCOPE.keyName(), "");
    }
    private String workspace() { return configService.getString(FAMILY_ID, K_ASK_MA_WORKSPACE, ""); }
    private String mcpServerId() { return configService.getString(FAMILY_ID, K_ASK_MA_MCP_SERVER, ""); }
    private String agentId() { return configService.getString(FAMILY_ID, K_ASK_MA_AGENT_ID, ""); }

    /** v1.19.12 · 模型从配置读,不再写死 —— 子业务空间开通的往往不是默认那个 */
    private String configuredModel() {
        String m = configService.getString(FAMILY_ID, K_ASK_MA_MODEL, ASK_MA_MODEL_DEFAULT);
        return m == null || m.isBlank() ? ASK_MA_MODEL_DEFAULT : m.trim();
    }

    /** 业务空间是子域,不是路径参数 */
    private String agentBase() {
        return "https://" + workspace() + ".cn-beijing.maas.aliyuncs.com/api/v1/agentstudio";
    }

    private String humanError(Exception e) {
        if (e instanceof UpstreamException u) {
            if (u.status == 401 || u.status == 403) return "百炼那边说凭据不对或者没权限。检查 API Key 和业务空间 ID。";
            if (u.status == 404) return "百炼那边找不到这个 Agent。可能被删了 —— 在「AI 接入」页重新创建一个。";
            if (u.status == 429) return "问得太频繁,百炼限流了。等一会儿再问。";
            // v1.19.14 · 把百炼原话带出来。这是同一个病的**第三次**复发:
            // v1.19.4 是「识别失败,请重试」盖住额度耗尽,v1.19.11 是「upstream 400」盖住字段错,
            // 这次是「百炼返回了错误(400)」盖住 `Missing required field: 'agent'` ——
            // 那句话直接指出了 bug 在哪,却只进了日志,用户看到的是一句无信息量的话。
            return "百炼返回了错误(" + u.status + ")。原话:" + UpstreamException.brief(u.body);
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "等百炼回话超时了。这个问题可能有点大,拆小一点再问试试。";
        }
        return "连不上百炼。检查一下服务器能不能出网。";
    }

    /**
     * 上游(百炼)返回的非 2xx。
     *
     * <p>v1.19.11 · <b>message 里必须带上百炼说了什么</b>。原来 {@code super("upstream " + status)}
     * 把 body 存进字段却不放进 message,而调用方用的正是 {@code getMessage()} ——
     * 于是用户只看到「upstream 400」,再配一句我们猜的「先确认业务空间 ID、MCP 服务 ID 都对」。</p>
     *
     * <p><b>代价是真实的</b>:2026-09-03 用户卡在创建 Agent,提示让他去查那两个 ID,
     * 而那两个 ID 本来就是对的;百炼其实明确说了 {@code model} 字段类型不对、
     * 以及 {@code mcpServers[0].type} 的合法值是什么。<b>最有用的一句话被我们丢掉了</b>,
     * 排查因此绕了一大圈。与 v1.19.4「识别失败,请重试」是同一类错误。</p>
     */
    private static final class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("upstream " + status + (body == null || body.isBlank() ? "" : " · " + brief(body)));
            this.status = status;
            this.body = body;
        }
        /** 百炼的错误体是 JSON,里面那句 message 才是人能看懂的部分;取不出来就退回原文截断 */
        static String brief(String body) {
            try {
                JsonNode n = new ObjectMapper().readTree(body);
                JsonNode m = n.path("error").path("message");
                if (m.isTextual() && !m.asText().isBlank()) return trim(m.asText());
                if (n.path("message").isTextual()) return trim(n.path("message").asText());
            } catch (Exception ignored) { }
            return trim(body);
        }
        private static String trim(String s) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > 300 ? one.substring(0, 300) + "…" : one;
        }
    }
}
