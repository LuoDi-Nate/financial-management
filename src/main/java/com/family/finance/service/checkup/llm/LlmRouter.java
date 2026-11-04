package com.family.finance.service.checkup.llm;

import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptTrace;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * v1.13 · <b>LLM 调用的唯一入口</b>:读配置 → 编出主备候选 → 依次调 → 谁成功用谁。
 *
 * <p>为什么必须收口:v1.12 有六处业务代码各自注入 {@code List<LlmClient>} 自己遍历,
 * 其中<b>两处忘了排序</b>(RebalanceAdvisorService / GoalLlmService)—— 它们永远按 Spring 的
 * {@code @Order} 走,也就是永远先打百炼,管理页上把主选改成 DeepSeek 对这两个功能<b>完全无效</b>。
 * 这个 bug 能活下来,是因为「主备顺序」是一段可以被复制、也可以被忘记复制的代码。
 * 现在它只有一份,而且 {@code @Order} 已经删掉了 —— 顺序只能来自配置。
 * 护栏 {@code v113-LLM-ROUTER-SINGLE-PATH} 钉死「{@code List<LlmClient>} 只许出现在本类」。</p>
 *
 * <p>候选编排规则:主选在前、备选在后,并剔掉三类不可能成功的:平台没有对应实现、
 * 配置不自洽(如方舟没填型号)、客户端当前不可用(key 没配 / 熔断中 / 型号全在额度冷却)。
 * <b>剔除发生在出网之前</b>,不让「明知会失败」的调用去占用户的等待时间。</p>
 */
@Service
@Slf4j
public class LlmRouter {

    /** 全项目唯一允许注入 {@code List<LlmClient>} 的地方 */
    private final List<LlmClient> clients;
    private final FamilyConfigService configService;
    /**
     * 每个平台最后一次调用的结果 —— 管理页拿它显示「AI 现在还好吗」。
     * 记在这里而不是各个 client 里:这里是唯一的编排点,成功与失败都从这一个 for 循环过。
     */
    private final LlmHealthTracker healthTracker;

    // 【必须标 @Autowired】—— 这个类有两个 public 构造器(另一个给单测用),
    // 不标的话 Spring 不知道挑哪个,会去找无参构造,然后整个应用起不来:
    //   NoSuchMethodException: LlmRouter.<init>()
    // 而 mvn package 与 901 个单测【全绿】——编译和单测都看不见这个问题,只有真的启动才看得见。
    @org.springframework.beans.factory.annotation.Autowired
    public LlmRouter(List<LlmClient> clients, FamilyConfigService configService,
                     LlmHealthTracker healthTracker) {
        this.clients = clients;
        this.configService = configService;
        this.healthTracker = healthTracker;
    }

    /**
     * 测试用的两参构造:自带一个独立的健康读数器。
     *
     * <p>健康读数是<b>旁路</b> —— 它只记录"最后一次调用成没成功",不参与任何路由判断。
     * 十几处单测关心的是编排顺序与降级行为,给每处都塞一个 tracker 只是噪音。
     * 生产走上面那个三参构造,由 Spring 注入全局那一个。</p>
     */
    public LlmRouter(List<LlmClient> clients, FamilyConfigService configService) {
        this(clients, configService, new LlmHealthTracker());
    }

    /**
     * v1.28 · 发给 AI 的内容记录器(PRD FR-909)。字段注入 + 可选:单测用的两参构造不带它,
     * 那时 {@link #invoke(long, PromptTrace, String, String, Handler)} 退化成不记录的老行为。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PromptRecorder promptRecorder;

    /** 单测注入用 */
    public void setPromptRecorder(PromptRecorder r) { this.promptRecorder = r; }

    /** 一次成功调用的结果 */
    public record Outcome(String text, LlmInvocation used) {}

    /**
     * 逐候选处理器。给需要「看到内容再决定收不收」的调用方用(体检要过输出校验、
     * AI 标签要能解析出白名单标签),不收就自动落到下一个候选。
     */
    public interface Handler<T> {
        /**
         * @return 接受则返回结果;<b>返回 null = 不接受这次输出</b>,路由继续试下一个候选
         */
        T onOutput(LlmInvocation invocation, String raw, long elapsedMs);

        /** 调用抛异常时回调(审计用)· 默认不处理 */
        default void onFailure(LlmInvocation invocation, Exception e, long elapsedMs) {}
    }

    /** 按配置编出本次可用的候选链(主 → 备)· 已剔除不可能成功的 */
    public List<LlmInvocation> plan(long familyId) {
        LlmSettings settings = LlmSettings.load(configService, familyId);
        List<LlmInvocation> usable = new ArrayList<>();
        for (LlmInvocation inv : settings.chain()) {
            if (!inv.resolvable()) {
                log.debug("跳过候选 {} · 配置不自洽(平台/系列不存在,或该平台要求手填型号但未填)", inv.label());
                continue;
            }
            Optional<LlmClient> c = clientFor(inv.platform());
            if (c.isEmpty()) {
                log.warn("跳过候选 {} · 没有对应的客户端实现", inv.label());
                continue;
            }
            if (!c.get().available()) {
                log.debug("跳过候选 {} · 当前不可用(key 未配 / 熔断中 / 型号额度冷却)", inv.label());
                continue;
            }
            usable.add(inv);
        }
        return usable;
    }

    /**
     * 这个家庭现在有没有能用的 AI(给「AI 解读」按钮判灰用)。
     *
     * <p>v1.12 这里问的是「有没有任何一个 client 的 key 配了」。v1.13 收严成「编排后还剩候选吗」——
     * 因为多了一种新的失败态:方舟配了 key 但没填型号。按老口径按钮是亮的,点下去必然失败;
     * 按新口径直接告诉用户去管理页补配置。</p>
     */
    public boolean available(long familyId) {
        return !plan(familyId).isEmpty();
    }

    public static int timeoutSeconds(FamilyConfigService config, long familyId) {
        return Math.max(5, Math.min(120, config.getInt(familyId, FamilyConfigService.K_LLM_TIMEOUT_SECS, 25)));
    }

    public String unavailableReason(long familyId) {
        if (available(familyId)) return null;
        boolean keyPresent = false, resolvable = false;
        for (LlmInvocation inv : LlmSettings.load(configService, familyId).chain()) {
            var p = inv.platformDef();
            if (p.isEmpty()) continue;
            String key = configService.getString(familyId, p.get().keyName(), "");
            if (key == null || key.isBlank()) continue;
            keyPresent = true;
            if (inv.resolvable()) resolvable = true;
        }
        if (!keyPresent) return "还没有配置可用的大模型密钥,请到「AI 接入」配置主选或备选。";
        if (!resolvable) return "模型配置不完整,请到「AI 接入」补齐平台、系列和型号。";
        return "已配置的模型正在故障或额度冷却中,请稍后重试或到「AI 接入」检查。";
    }

    public void externalSuccess(LlmInvocation inv) {
        clientFor(inv.platform()).ifPresent(LlmClient::recordExternalSuccess);
        healthTracker.recordOk(inv.platform());
    }

    public void externalFailure(LlmInvocation inv, Exception failure) {
        clientFor(inv.platform()).ifPresent(LlmClient::recordExternalFailure);
        healthTracker.recordFail(inv.platform(), false, "工具对话调用失败");
        log.warn("本地 Agent 候选 {} 调用失败 · {}", inv.label(), failure.getClass().getSimpleName());
    }

    /** 取某平台的客户端(管理页「测试连接」用;业务调用一律走 invoke) */
    public Optional<LlmClient> clientFor(String platform) {
        if (platform == null) return Optional.empty();
        return clients.stream().filter(c -> c.platform().equalsIgnoreCase(platform)).findFirst();
    }

    /** 已装载的平台 code(诊断 / 一致性护栏用) */
    public List<String> platforms() {
        return clients.stream().map(LlmClient::platform).toList();
    }

    /**
     * 简单调用:主选失败切备选,拿到非空输出即返回。
     *
     * @return 成功则 {@link Outcome};全部候选失败或没有可用候选则 {@link Optional#empty()}
     */
    public Optional<Outcome> invoke(long familyId, String systemPrompt, String userPrompt) {
        Outcome r = invoke(familyId, systemPrompt, userPrompt,
                (inv, raw, ms) -> new Outcome(raw, inv));
        return Optional.ofNullable(r);
    }

    /** v1.28 · 简单调用 + 记录发出去的内容 */
    public Optional<Outcome> invoke(long familyId, PromptTrace trace, String systemPrompt, String userPrompt) {
        return Optional.ofNullable(invoke(familyId, trace, systemPrompt, userPrompt,
                (inv, raw, ms) -> new Outcome(raw, inv)));
    }

    /**
     * v1.28 · 带处理器的调用 + <b>把真正交给客户端的两个字符串存下来</b>(tech-design v1.28 选型一)。
     *
     * <p>为什么在这里存:这是唯一的编排点 —— 发出去的原文、实际用的模型、失败时的上游原话都在这个循环里。
     * 让 9 个调用方各自存,迟早有一处存成拼接前的中间串,页面上就会显示「不是发出去的那段」。</p>
     *
     * <p>结果三种:有候选接受 = OK;有输出但都被处理器拒收 = REJECTED(原因取 {@link PromptTrace#rejectReason()});
     * 全部调用失败 = FAILED(原因是最后一个候选的上游原话,不改写)。<b>没有可用候选 = 什么都没发,不记</b>。</p>
     */
    public <T> T invoke(long familyId, PromptTrace trace, String systemPrompt, String userPrompt, Handler<T> handler) {
        if (trace == null) return invoke(familyId, systemPrompt, userPrompt, handler);
        if (plan(familyId).isEmpty()) {
            trace.failed(unavailableReason(familyId));
            return null;
        }
        String[] vendor = {null};
        String[] lastError = {null};
        boolean[] gotOutput = {false};
        T result = invoke(familyId, systemPrompt, userPrompt, new Handler<T>() {
            @Override
            public T onOutput(LlmInvocation inv, String raw, long ms) {
                vendor[0] = inv.badge();
                gotOutput[0] = true;
                return handler.onOutput(inv, raw, ms);
            }

            @Override
            public void onFailure(LlmInvocation inv, Exception e, long ms) {
                vendor[0] = inv.badge();
                lastError[0] = e.getMessage();
                handler.onFailure(inv, e, ms);
            }
        });
        String outcome = result != null ? PromptRecorder.OK : (gotOutput[0] ? PromptRecorder.REJECTED : PromptRecorder.FAILED);
        String note = result != null ? null
                : gotOutput[0] ? (trace.rejectReason() == null ? "回答没通过校验" : trace.rejectReason())
                : lastError[0];
        if (result == null) trace.failed(gotOutput[0] ? "AI 回答未通过校验,请重试。"
                : "已尝试可用模型,本次调用失败。请稍后重试或到「AI 接入」检查连接与额度。");
        if (promptRecorder != null) promptRecorder.save(familyId, trace, systemPrompt, userPrompt, vendor[0], outcome, note);
        return result;
    }

    /**
     * 带处理器的调用:每个候选调完把原始输出交给 {@code handler} 判收不收,不收就试下一个。
     *
     * @return handler 第一次接受的结果;无人接受 / 全部失败 / 无可用候选 → null
     */
    public <T> T invoke(long familyId, String systemPrompt, String userPrompt, Handler<T> handler) {
        for (LlmInvocation inv : plan(familyId)) {
            LlmClient client = clientFor(inv.platform()).orElse(null);
            if (client == null || !client.available()) continue;
            long start = System.currentTimeMillis();
            try {
                String raw = client.chat(inv, systemPrompt, userPrompt);
                long ms = System.currentTimeMillis() - start;
                if (raw == null || raw.isBlank()) {
                    handler.onFailure(inv, new IllegalStateException("空输出"), ms);
                    continue;
                }
                healthTracker.recordOk(inv.platform());
                T accepted = handler.onOutput(inv, raw, ms);
                if (accepted != null) return accepted;
                log.warn("候选 {} 的输出未被接受 · 试下一个", inv.label());
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - start;
                // 账户级故障(凭据/欠费/权限)不会自己好,必须有人去处理 —— 单独标出来,
                // 好让管理页只在这一类上报警,不被偶发超时淹掉。
                boolean accountFatal = String.valueOf(e.getMessage()).contains("账户级故障");
                healthTracker.recordFail(inv.platform(), accountFatal, e.getMessage());
                log.warn("候选 {} 调用失败 · {}", inv.label(), e.toString());
                handler.onFailure(inv, e, ms);
            }
        }
        return null;
    }
}
