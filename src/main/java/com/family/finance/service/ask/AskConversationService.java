package com.family.finance.service.ask;

import com.family.finance.domain.ask.*;
import com.family.finance.repository.*;
import com.family.finance.service.ask.runtime.AgentRuntime;
import com.family.finance.service.ask.runtime.AskSink;
import com.family.finance.service.ask.runtime.ManagedAgentRuntime;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.family.finance.service.config.FamilyConfigService.K_ASK_ENABLED;
import static com.family.finance.service.config.FamilyConfigService.K_ASK_RUNTIME;

/**
 * v1.19 · 对话编排。
 *
 * <p>这一层<b>不知道供应商是谁</b> —— 它只认识 {@link AgentRuntime}。
 * 也<b>不做业务计算</b> —— 那些在 {@code service/ask/tools} 里,而那些又只是调既有 service。</p>
 *
 * <p>它真正负责的是一件事:<b>把模型吐出来的东西,变成一条能重放、能核对的记录</b>。
 * 正文里的 {@code {{cite:c1}}} 标记原样落库,数值单独存进 {@link AskCitation}。
 * 于是三个月后重新打开这段对话,数字仍然是当时工具返回的那一个,
 * 口径说明却是<b>今天</b>的说法 —— 因为口径文案是渲染期现取的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskConversationService {

    /** 送进模型的历史轮数上限 —— 每一轮都要重新计费,不能无限带 */
    private static final int HISTORY_TURNS = 8;
    private static final int TITLE_MAX = 20;
    /** 单条提问长度上限:再长基本是粘贴了一段文档,不是提问 */
    public static final int QUESTION_MAX = 500;

    private static final long CFG_FAMILY = 1L;

    private final AskConversationMapper conversationMapper;
    private final AskMessageMapper messageMapper;
    private final AskCitationMapper citationMapper;
    private final AskToolCallMapper toolCallMapper;
    private final AskPromptBuilder promptBuilder;
    private final AskCitationRenderer renderer;
    private final FamilyConfigService configService;
    private final com.family.finance.service.FamilyService familyService;
    private final com.family.finance.service.lens.LensQueryService lensQueryService;
    private final List<AgentRuntime> runtimes;
    /**
     * 托管模式下工具在 MCP 请求里跑,引用产在那边、落库在这边 ——
     * 这个寄存器是两者之间唯一的通道(见 {@link AskCiteBuffer})。
     */
    private final AskCiteBuffer citeBuffer;
    private final ObjectMapper json = new ObjectMapper();
    /**
     * v1.27 · 分析上下文(家里的默认范围 / 模板 / 分析偏好)· 字段注入且可缺:老测试不带它 = 没有上下文。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.analysis.AnalysisContextService analysisContextService;

    /** v1.28 · 记下每一问发出去的内容(PRD FR-919)· 可缺:缺了就不记 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.llmtrace.PromptRecorder promptRecorder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.member.MemberDirectory memberDirectory;

    // ──────────────────────── 开关与 runtime ────────────────────────

    public boolean enabled(long familyId) {
        return configService.getBoolean(CFG_FAMILY, K_ASK_ENABLED, false);
    }

    /**
     * 当前选定的 runtime。
     *
     * <p><b>默认是百炼托管(v1.19.10 起)</b>。这一版之前默认是本机直连,那与维护者的拍板相反 ——
     * PRD v1.19 开头记着原话:「走阿里云百炼 Managed Agents,<b>不自建编排</b>」「直接选 B,
     * <b>没有 A 这个方案</b>」「agent loop 不用我们写」。而本机直连正是那个 A(我们自己写的
     * tool loop),把它设成默认等于把被否掉的方案变成了所有人的默认路径。</p>
     *
     * <p>本机直连仍然保留,作为<b>明确的备选</b>(维护者 2026-09-03 决定):
     * 内网 / NAT 环境百炼回调不进来,那种情况下它是唯一能用的。但它是<b>用户主动选的</b>,
     * 不是我们替他选的。</p>
     *
     * <p>配置里写了不认识的值时,回退到<b>第一个已注册的 runtime</b>,而不是硬指本机直连 ——
     * 硬指等于又把默认偷偷改回去。</p>
     */
    public AgentRuntime runtime() {
        String want = configService.getString(CFG_FAMILY, K_ASK_RUNTIME, ManagedAgentRuntime.CODE);
        return runtimes.stream().filter(r -> r.code().equals(want)).findFirst()
                .orElseGet(() -> runtimes.stream()
                        .filter(r -> r.code().equals(ManagedAgentRuntime.CODE))
                        .findFirst().orElse(runtimes.get(0)));
    }

    public List<AgentRuntime> allRuntimes() { return runtimes; }

    /**
     * 界面顶部那行上下文:「回答是基于哪一期、哪个币种」。
     *
     * <p>用的是 {@code LensQueryService} 的锚期 —— 与 {@code pivot} 取数的那一期<b>同一个</b>。
     * 界面上写一个、工具用另一个,是这一版已经踩过的坑(账期传 null 让模型多花一轮)。</p>
     */
    public String contextLabel(long familyId) {
        String ccy = familyService.require(familyId).getBaseCurrency();
        String period = renderer.periodLabel(familyId, lensQueryService.anchorPeriodId(familyId));
        return period == null ? ccy : period + " · " + ccy;
    }

    /** 不可用的人话原因;可用则 null */
    public String blockedReason(long familyId) {
        if (!enabled(familyId)) return "「超级 Agent」还没打开。到「AI 接入」页开一下。";
        return runtime().unavailableReason(familyId);
    }

    // ──────────────────────── 会话 ────────────────────────

    public AskConversation start(long familyId, Long periodId, String currency) {
        AskConversation c = AskConversation.builder()
                .familyId(familyId)
                .title("新对话")
                .ctxPeriodId(periodId)
                .ctxCurrency(currency)
                .build();
        conversationMapper.insert(c);
        return c;
    }

    public List<AskConversation> recent(long familyId, int limit) {
        return conversationMapper.recent(familyId, limit);
    }

    /** 按 id 取,并校验归属 —— 不能靠「在最近列表里找得到」代替归属校验 */
    public AskConversation find(long familyId, long conversationId) {
        return conversationMapper.findById(familyId, conversationId);
    }

    /** 取一段会话并装配好引用块与工具摘要(一次查完,不按消息 N+1) */
    public List<AskMessage> history(long familyId, long conversationId) {
        List<AskMessage> msgs = messageMapper.byConversation(familyId, conversationId);
        Map<Long, List<AskCitation>> cites = new LinkedHashMap<>();
        for (AskCitation c : citationMapper.byConversation(familyId, conversationId)) {
            cites.computeIfAbsent(c.getMessageId(), k -> new ArrayList<>()).add(renderer.decorate(c));
        }
        Map<Long, List<AskToolCall>> calls = new LinkedHashMap<>();
        for (AskToolCall t : toolCallMapper.byConversation(familyId, conversationId)) {
            t.setLabel(renderer.toolLabel(t.getToolName()));
            calls.computeIfAbsent(t.getMessageId(), k -> new ArrayList<>()).add(t);
        }
        for (AskMessage m : msgs) {
            m.setCitations(cites.getOrDefault(m.getId(), List.of()));
            m.setToolCalls(calls.getOrDefault(m.getId(), List.of()));
        }
        return msgs;
    }

    public boolean archive(long familyId, long conversationId) {
        return conversationMapper.archive(conversationId, familyId) > 0;
    }

    /** 上下文变了(换账期 / 换币种)→ 插一条旁白,<b>不新建会话</b> */
    public void noteContextChange(long familyId, long conversationId, String text) {
        messageMapper.insertOwned(familyId, AskMessage.builder()
                .conversationId(conversationId)
                .role(AskMessage.ROLE_NOTE)
                .contentText(text)
                .seq(messageMapper.nextSeq(familyId, conversationId))
                .build());
    }

    // ──────────────────────── 一轮提问 ────────────────────────

    /**
     * 提问。<b>同步阻塞</b>,跑在 SSE 线程上;产出经 {@code out} 出去,同时攒起来落库。
     *
     * <p>落库发生在 {@code done} / {@code failed} 时。失败也落 —— <b>半截答案要留住</b>:
     * 用户看得见「查到第二步断了」,比整段消失有用,重试时也知道上次到哪。</p>
     */
    /**
     * 这一轮是怎么来的。
     *
     * <p>三种都要跑一次模型,但<b>要不要往库里追一条用户消息</b>不同 ——
     * 「重来」和「继续」如果也追一条,历史里就会出现同一个问题问了两遍、
     * 或者一条用户根本没打过的「接着说」。</p>
     */
    public enum Mode {
        /** 用户新问的 */
        NEW,
        /** 同一个问题再答一次(不追用户消息,复用上一条提问) */
        REGENERATE,
        /** 上一轮被叫停了,接着说完 */
        CONTINUE
    }

    /** 「继续」时喂给模型的指令。它不进历史、不上屏,只是这一轮的输入 */
    private static final String CONTINUE_PROMPT =
            "接着上面没说完的地方继续说完,不要重复已经说过的部分。";

    public void ask(long familyId, long conversationId, String question, AskSink out) {
        ask(familyId, conversationId, question, Mode.NEW, out);
    }

    public void ask(long familyId, long conversationId, String question, Mode mode, AskSink out) {
        AskConversation conv = conversationMapper.findById(familyId, conversationId);
        if (conv == null || conv.getFamilyId() != familyId) {
            out.failed("这段对话不在了。开一段新的吧。");
            return;
        }

        String blocked = blockedReason(familyId);
        if (blocked != null) { out.failed(blocked); return; }

        List<AskMessage> prior = messageMapper.byConversation(familyId, conversationId);
        String q;
        Long skipId = null;

        if (mode == Mode.NEW) {
            q = question == null ? "" : question.trim();
            if (q.isEmpty()) { out.failed("先写点什么再问。"); return; }
            if (q.length() > QUESTION_MAX) q = q.substring(0, QUESTION_MAX);

            // 用户这条先落库 —— 上游炸了也不能把用户打的字弄丢
            AskMessage userMsg = AskMessage.builder()
                    .conversationId(conversationId).role(AskMessage.ROLE_USER)
                    .contentText(q).seq(messageMapper.nextSeq(familyId, conversationId)).build();
            messageMapper.insertOwned(familyId, userMsg);
            skipId = userMsg.getId();
            if ("新对话".equals(conv.getTitle())) {
                String title = q.length() > TITLE_MAX ? q.substring(0, TITLE_MAX) : q;
                conversationMapper.updateTitle(familyId, conversationId, title);
            }
        } else if (mode == Mode.REGENERATE) {
            // 复用最后一条提问,**不追新的用户消息** —— 追了历史里就是同一个问题问了两遍
            q = prior.stream().filter(AskMessage::fromUser)
                    .reduce((a, b) -> b).map(AskMessage::getContentText).orElse(null);
            if (q == null) { out.failed("这段对话里还没有提问,没法重来。"); return; }
            // 上一条回答留在历史里会让模型「接着上一句说」,而重来要的是从头再答一次
            skipId = prior.stream().filter(m -> !m.fromUser() && !m.isNote())
                    .reduce((a, b) -> b).map(AskMessage::getId).orElse(null);
        } else {
            q = CONTINUE_PROMPT;
        }

        final Long skip = skipId;
        List<AgentRuntime.Msg> history = messageMapper
                .recentForContext(familyId, conversationId, HISTORY_TURNS * 2).stream()
                .filter(m -> skip == null || !m.getId().equals(skip))
                .sorted(Comparator.comparing(AskMessage::getSeq))
                .map(m -> new AgentRuntime.Msg(m.getRole(), m.getContentText()))
                .toList();

        String periodLabel = conv.getCtxPeriodId() == null ? null : renderer.periodLabel(familyId, conv.getCtxPeriodId());
        String systemPrompt = promptBuilder.build(familyId, periodLabel, conv.getCtxCurrency());

        // 【一轮开始先清】—— 语义化的引用 key(nw / ta / r0_0)每轮都会重复出现,
        // 上一轮的残留不清掉,会被这一轮的正文误命中,落库成一个【上一轮的数】。
        citeBuffer.clear(familyId);
        Collector collector = new Collector(familyId, conversationId, out);
        // v1.27 · 这一轮的分析上下文;ctx_note 记在这一问对应的用户消息上(新问 = 刚落库那条;重来 = 最后一条提问)
        String analysisContext = analysisContext(familyId);
        Long noteTarget = mode == Mode.NEW ? skipId
                : (mode == Mode.REGENERATE ? prior.stream().filter(AskMessage::fromUser)
                        .reduce((a, b) -> b).map(AskMessage::getId).orElse(null) : null);
        String noteHead = analysisNoteHead(familyId);
        AgentRuntime.AskTurn turn = new AgentRuntime.AskTurn(
                familyId, conversationId, conv.getProviderRef(), systemPrompt, history, q,
                AskScope.DETAIL,           // 产品内对话:数据本来就是用户自己的,不设限
                ref -> conversationMapper.updateProviderRef(familyId, conversationId, ref),
                analysisContext,
                how -> {
                    if (noteTarget == null) return;
                    try {
                        String note = (noteHead + " · " + how);
                        messageMapper.updateCtxNote(familyId, noteTarget, note.length() > 80 ? note.substring(0, 80) : note);
                    } catch (Exception e) {
                        log.debug("ctx_note 写入失败(不影响回答):{}", e.toString());
                    }
                },
                (system, input, vendor, managed) -> recordSent(familyId, noteTarget, system, input, vendor, managed));

        runtime().run(turn, collector);
    }

    /**
     * v1.28 FR-919 · 这一问真正发出去的内容 → 记下来,挂在提问那条消息上。
     *
     * <p>托管模式的规矩(系统提示词)在百炼那边,由「更新 / 创建 Agent」时推过去;那一刻我们存过一份
     * ({@link FamilyConfigService#K_ASK_AGENT_PROMPT_RECORD})。这里取那一份当「规矩」,并写明推送时间;
     * 取不到(Agent 是本版之前建的)就照实说。记录失败不影响回答。</p>
     */
    private void recordSent(long familyId, Long target, String system, String input, String vendor, boolean managed) {
        if (promptRecorder == null || target == null) return;
        try {
            String sys = system;
            String note = null;
            if (managed) {
                long rid = configService.getLong(familyId, FamilyConfigService.K_ASK_AGENT_PROMPT_RECORD, 0L);
                var snap = rid > 0 ? promptRecorder.find(familyId, rid) : java.util.Optional.<com.family.finance.repository.PromptRecordMapper.Row>empty();
                if (snap.isPresent()) {
                    sys = snap.get().systemText;
                    note = "托管模式:规矩在百炼 Agent 里 —— 下面是 "
                            + snap.get().createdAt.toLocalDate() + " 点「更新 Agent」时推过去的那一段";
                } else {
                    sys = "";
                    note = "托管模式:规矩在百炼 Agent 里,是本版之前推过去的,没有记录 —— 去管理 → AI 接入点一次「更新 Agent」之后就能看";
                }
            }
            java.util.Map<String, String> legend = memberDirectory == null ? java.util.Map.of()
                    : com.family.finance.service.checkup.llm.PromptBuilder
                        .buildNameMapping(memberDirectory.listAll(familyId)).codenameToReal();
            var trace = com.family.finance.service.llmtrace.PromptTrace
                    .of(com.family.finance.service.llmtrace.PromptSurface.ASK_TURN).legend(legend)
                    .settings(analysisContextService == null ? null
                            : analysisContextService.familyDefaults(familyId).settingsNote());
            Long id = promptRecorder.save(familyId, trace, sys, input, vendor,
                    com.family.finance.service.checkup.llm.LlmTestGate.MESSAGE.equals(vendor)
                            ? com.family.finance.service.llmtrace.PromptRecorder.BLOCKED
                            : com.family.finance.service.llmtrace.PromptRecorder.SENT, note);
            if (id != null) messageMapper.updatePromptRecord(familyId, target, id);
        } catch (Exception e) {
            log.debug("记录这一问发出去的内容失败(不影响回答):{}", e.toString());
        }
    }

    /**
     * v1.27 · 超级 Agent 每轮的分析上下文(FR-849 / FR-853 / FR-854)。
     * 偏好原文与补充要求先过真名映射(与其余 AI 同一层脱敏);产品内对话是 DETAIL 范围,可以写账户名。
     */
    private String analysisContext(long familyId) {
        if (analysisContextService == null) return null;
        try {
            var ctx = analysisContextService.familyDefaults(familyId);
            java.util.Map<String, String> mapping = memberDirectory == null ? java.util.Map.of()
                    : com.family.finance.service.checkup.llm.PromptBuilder
                        .buildNameMapping(memberDirectory.listAll(familyId)).realToCodename();
            return com.family.finance.service.analysis.AnalysisPromptBlocks.agentContext(ctx, mapping, true);
        } catch (Exception e) {
            log.warn("超级 Agent · 分析上下文组装失败(这一轮不带):{}", e.toString());
            return null;
        }
    }

    /** ctx_note 的前半句:偏好几条 · 模板 · 范围 */
    private String analysisNoteHead(long familyId) {
        if (analysisContextService == null) return "无分析上下文";
        try {
            var ctx = analysisContextService.familyDefaults(familyId);
            return "偏好 " + ctx.preferences().size() + " 条 · 模板 " + ctx.template().name()
                    + " · 范围 " + ctx.scope().kind().getLabel();
        } catch (Exception e) {
            return "分析上下文";
        }
    }

    // ──────────────────────── 收集器 ────────────────────────

    /**
     * 既往浏览器转发,又往库里攒。
     *
     * <p>两件事必须在<b>同一个对象</b>里做:分开做的话,SSE 送出去的和落库的会有一方漏 ——
     * 而用户刷新页面后看到的是落库那份,对不上的时候会以为答案变了。</p>
     */
    private final class Collector implements AskSink {
        private final long familyId;
        private final long conversationId;
        private final AskSink out;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, AskToolResult.Cite> cites = new LinkedHashMap<>();
        private final List<AskToolCall> calls = new ArrayList<>();
        /** toolStart 时拿到参数、toolDone 时才落库,中间存一下 */
        private final Map<String, String> pendingArgs = new LinkedHashMap<>();
        private boolean closed = false;

        Collector(long familyId, long conversationId, AskSink out) {
            this.familyId = familyId;
            this.conversationId = conversationId;
            this.out = out;
        }

        @Override public void status(String t) { out.status(t); }

        @Override
        public void toolStart(String tool, String label, String args) {
            pendingArgs.put(tool, args);
            out.toolStart(tool, label, args);
        }

        @Override
        public void toolDone(String tool, String label, int ms, boolean ok,
                             String summary, Map<String, AskToolResult.Cite> citable) {
            cites.putAll(citable);
            calls.add(AskToolCall.builder()
                    .toolName(tool).argsJson(pendingArgs.get(tool))
                    .durationMs(ms).ok(ok).summary(summary).build());
            out.toolDone(tool, label, ms, ok, summary, citable);
        }

        /** 已经推给浏览器的引用 key —— 每段文字前只推新到的那些 */
        private final java.util.Set<String> forwarded = new java.util.HashSet<>();

        /**
         * 托管模式:把 MCP 那边刚寄存的引用推给浏览器。
         *
         * <p>时序上是对的:模型要先拿到工具结果才写得出 {@code {{cite:t2_nw}}},
         * 而工具结果就是在 MCP 请求里寄存的 —— 所以写到这个标记的那段文字到达时,
         * 对应的引用已经在寄存器里了。先推引用、再推文字,前端替换时就找得到。</p>
         */
        private void forwardNewCites() {
            Map<String, AskToolResult.Cite> fresh = new LinkedHashMap<>();
            citeBuffer.snapshot(familyId).forEach((k, c) -> {
                if (forwarded.add(k)) fresh.put(k, c);
            });
            if (!fresh.isEmpty()) out.cites(fresh);
        }

        @Override
        public void textDelta(String delta) {
            forwardNewCites();
            text.append(delta);
            out.textDelta(delta);
        }

        @Override
        public void rollback(String narration) {
            // 只砍缓冲区尾部那一段 —— 按内容匹配而不是清空,
            // 万一 runtime 传来的和实际收到的对不上,宁可多留一点也不能把真答案删了
            int at = text.lastIndexOf(narration);
            if (at >= 0 && at + narration.length() == text.length()) text.setLength(at);
            out.rollback(narration);
        }

        @Override public boolean cancelled() { return out.cancelled(); }

        @Override
        public void done() {
            forwardNewCites();
            persist();
            out.done();
        }

        @Override
        public void stopped() {
            // 半截回答照样落库 —— 用户接下来多半点「继续」或「重来」,扔掉等于让他从头再等
            persist();
            // 叫停这件事本身也要留痕:不留的话重新打开这段对话,
            // 那一轮就只剩一个说了一半就断掉的回答,看不出是被谁、为什么打断的
            note(text.isEmpty() ? "你叫停了 · 这一轮还没开始作答"
                                : "你叫停了 · 上面是已经说到的部分");
            out.stopped();
        }

        private void note(String text) {
            messageMapper.insertOwned(familyId, AskMessage.builder()
                    .conversationId(conversationId).role(AskMessage.ROLE_NOTE)
                    .contentText(text).seq(messageMapper.nextSeq(familyId, conversationId)).build());
        }

        @Override
        public void failed(String msg) {
            // 已经吐出去的正文照样落库,后面补一条旁白说明断在哪
            if (!text.isEmpty()) persist();
            note(msg);
            out.failed(msg);
        }

        /** 正文里有没有引用这个 key —— 正文写法与图表写法都算 */
        private boolean referenced(String body, String key) {
            return body.contains("{{cite:" + key + "}}")
                || body.contains("\"cite\":\"" + key + "\"")
                || body.contains("\"cite\": \"" + key + "\"");
        }

        private void persist() {
            if (closed) return;
            closed = true;
            String body = text.toString();
            // 托管模式:工具是百炼回头调 MCP 执行的,引用没经过 sink,
            // 到这里才从寄存器里取回来。本机直连模式下寄存器是空的,这一行不改变任何行为。
            cites.putAll(citeBuffer.drain(familyId));
            // 没有正文就不落这条消息 —— 只有工具调用、一个字都没说的「回答」不是回答,
            // 留下来会在历史里变成一个空白轮次。工具痕迹随它一起丢掉:
            // 一轮什么都没说出来,「它查了什么」也就没有解释对象了。
            if (body.isBlank()) return;

            AskMessage m = AskMessage.builder()
                    .conversationId(conversationId).role(AskMessage.ROLE_ASSISTANT)
                    .contentText(body).seq(messageMapper.nextSeq(familyId, conversationId)).build();
            messageMapper.insertOwned(familyId, m);

            // 只存正文真的引用到的 —— 一轮里工具可能返回几十个可引用项,
            // 全存进去等于把整张透视表抄进库,而没被引用的那些没有任何用处。
            //
            // **两种引用写法都要认**:正文里是 {{cite:c3}},而图表标记里是 "cite":"c3"。
            // 只认前一种的后果是:模型只画图、不在正文点名数字时,引用一个都不落库 ——
            // 流式那一刻图是对的(数据还在内存里),刷新之后整张图消失。
            // e2e 就是这么抓到的(库里有 chart 标记、页面上却没有图表容器)。
            cites.forEach((key, c) -> {
                if (!referenced(body, key)) return;
                citationMapper.insertOwned(familyId, AskCitation.builder()
                        .messageId(m.getId()).citeKey(key).metricKey(c.metricKey()).label(c.label())
                        .periodId(c.periodId()).inProgress(c.inProgress())
                        .valueText(c.valueText()).currency(c.currency())
                        .targetHref(c.targetHref()).build());
            });
            for (AskToolCall t : calls) {
                t.setMessageId(m.getId());
                toolCallMapper.insertOwned(familyId, t);
            }
        }
    }
}
