package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.13 · OpenAI 兼容平台的公共实现:请求体 / Bearer / 响应解析 / 熔断 / 多型号重试骨架。
 *
 * <p>接方舟之前,这段逻辑在 Qwen 和 DeepSeek 两个类里各写了一份 —— 已经漂移了:
 * DeepSeek 那份没有 {@code finish_reason == "length"} 的截断告警。再复制第三份只会让漂移变三向。</p>
 *
 * <p>子类要填的只有「这一家哪里不一样」:
 * <ul>
 *   <li>{@link #platformDef()} —— 端点 / key 名(必填)</li>
 *   <li>{@link #attemptModels} —— 一次调用要依次尝试哪些型号(默认只试一个;百炼覆写成轮询)</li>
 *   <li>{@link #classify} —— 错误归类(默认按 HTTP 状态;百炼覆写成看 body 认额度码)</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {
    @org.springframework.beans.factory.annotation.Autowired
    private LlmTestGate testGate;

    /** 单家庭模式 · 见 prd §22.3 类 A · 与 backup.sh FAMILY_ID=1 一致 */
    protected static final long FAMILY_ID = 1L;

    private static final int FAIL_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000L;

    protected final FamilyConfigService configService;
    protected final RestTemplate restTemplate;

    /** 连续失败 → 短熔断,防雪崩(备选平台也失败时不至于每次体检都干等两轮超时) */
    private volatile int consecutiveFailures = 0;
    private volatile Instant breakerOpenedAt = Instant.EPOCH;

    protected AbstractOpenAiCompatibleClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        this.configService = configService;
        this.restTemplate = builder
                .requestFactory(() -> new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                            throws java.io.IOException {
                        super.prepareConnection(connection, method);
                        connection.setConnectTimeout(5_000);
                        connection.setReadTimeout(LlmRouter.timeoutSeconds(configService, FAMILY_ID) * 1000);
                    }
                })
                .build();
    }

    /** 该实现对应的平台定义(端点 / key 名 / 系列清单) */
    protected abstract LlmCatalog.Platform platformDef();

    @Override
    public String platform() { return platformDef().code(); }

    protected String currentApiKey() {
        return configService.getString(FAMILY_ID, platformDef().keyName(), "");
    }

    protected int currentMaxTokens() {
        return configService.getInt(FAMILY_ID, FamilyConfigService.K_LLM_MAX_TOKENS, 2000);
    }

    /** 采样温度(管理页可配 · 夹到 0~1 · 默认 0.5)· 平台无关 */
    protected double currentTemperature() {
        double t = configService.getDouble(FAMILY_ID, FamilyConfigService.K_LLM_TEMPERATURE, 0.5);
        return Math.max(0.0, Math.min(1.0, t));
    }

    protected boolean breakerOpen() {
        if (consecutiveFailures < FAIL_THRESHOLD) return false;
        return Duration.between(breakerOpenedAt, Instant.now()).toMillis() < COOLDOWN_MS;
    }

    @Override
    public boolean available() {
        return !currentApiKey().isBlank() && !breakerOpen();
    }

    @Override public synchronized void recordExternalSuccess() { consecutiveFailures = 0; }
    @Override public void recordExternalFailure() { recordFailure(); }

    // ========== 子类可覆写的差异点 ==========

    /** 故障归类:账户级(立刻切备选)/ 单型号额度用尽(切下一型号)/ 其它(可重试) */
    protected enum Fault { ACCOUNT_FATAL, MODEL_QUOTA, TRANSIENT }

    /**
     * 默认:401/402/403 视为账户级(key 错 / 欠费 / 无权限)—— 换型号没用,直接交给路由切备选;
     * 其余算瞬时。百炼覆写这一条,因为它把「单模型免费额度用尽」也放在 403 里。
     */
    protected Fault classify(int status, String body) {
        return (status == 401 || status == 402 || status == 403) ? Fault.ACCOUNT_FATAL : Fault.TRANSIENT;
    }

    /**
     * 这次调用要依次尝试的型号。默认只有一个:显式填的型号,或系列默认型号。
     * 百炼覆写成「auto 时打乱轮询整个池子」(它的免费额度按型号各给一份)。
     */
    protected List<String> attemptModels(LlmInvocation inv) {
        String m = inv.resolvedModel();
        return m == null ? List.of() : List.of(m);
    }

    /** 该型号当前可不可用(百炼用来跳过额度冷却中的型号) */
    protected boolean modelAvailable(String model) { return true; }

    /** 记下「该型号额度用尽」(仅百炼有意义) */
    protected void markModelExhausted(String model) { /* 默认不记 */ }

    // ========== 主流程 ==========

    @Override
    public String chat(LlmInvocation inv, String systemPrompt, String userPrompt) {
        if (testGate != null) testGate.beforeSend("TEXT_CLIENT");
        String label = platformDef().label();
        String key = currentApiKey();
        if (key.isBlank()) throw new IllegalStateException(label + " API key 未配置");

        List<String> models = new ArrayList<>(attemptModels(inv));
        if (models.isEmpty()) {
            // 到这一步说明路由的可用性判断和客户端的型号解析不一致 —— 属于配置自洽性 bug,要显式炸出来
            throw new IllegalStateException(label + " 未指定型号(该平台的型号需从控制台复制后手工填写)");
        }

        RuntimeException lastTransient = null;
        boolean anyTried = false;

        for (String model : models) {
            if (!modelAvailable(model)) continue;
            anyTried = true;
            try {
                String content = callOnce(key, model, systemPrompt, userPrompt);
                consecutiveFailures = 0;      // 成功 → 复位熔断
                return content;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                Fault fault = classify(status, e.getResponseBodyAsString());
                switch (fault) {
                    case ACCOUNT_FATAL -> {
                        recordFailure();
                        log.warn("{} 账户级故障 status={} · 立刻切备选", label, status);
                        throw new RuntimeException(label + " 账户级故障(凭据/欠费/权限)status=" + status, e);
                    }
                    case MODEL_QUOTA -> {
                        markModelExhausted(model);
                        log.warn("{} 型号[{}] 额度用尽(status={}) · 切下一型号", label, model, status);
                    }
                    default -> {
                        lastTransient = new RuntimeException(
                                label + " 型号[" + model + "] 调用失败 status=" + status, e);
                        recordFailure();
                    }
                }
            } catch (RuntimeException e) {
                if (e instanceof LlmTestGate.Blocked) throw e;
                // 超时 / 解析错 / 空 content 等非 HTTP 错误
                lastTransient = e;
                recordFailure();
            }
        }

        if (!anyTried) throw new RuntimeException(label + " 所有型号均处于额度冷却,切备选");
        if (lastTransient != null) throw lastTransient;
        throw new RuntimeException(label + " 所有型号额度均已用尽,切备选");
    }

    /** 单型号单次调用 · 返回 content;HTTP 错误抛 {@link RestClientResponseException} */
    protected String callOnce(String key, String model, String systemPrompt, String userPrompt) {
        String label = platformDef().label();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", currentTemperature(),
                "max_tokens", currentMaxTokens()
        );

        if (testGate != null) testGate.beforeSend("TEXT_CLIENT");
        java.time.LocalDateTime dispatched = java.time.LocalDateTime.now();
        Map<String, Object> resp;
        try {
            resp = restTemplate.postForObject(platformDef().chatEndpoint(), new HttpEntity<>(body, h), Map.class);
        } finally {
            if (testGate != null) testGate.observedSend("TEXT_CLIENT", dispatched);
        }
        if (resp == null) throw new RuntimeException(label + " 返回 null");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException(label + " 无 choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        if (msg == null) throw new RuntimeException(label + " 无 message");
        String content = (String) msg.get("content");
        if (content == null || content.isBlank()) throw new RuntimeException(label + " 空 content");

        if ("length".equals(choices.get(0).get("finish_reason"))) {
            log.warn("{}[{}] 输出因 max_tokens 截断 · content.len={}", label, model, content.length());
        }
        return content;
    }

    private synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            breakerOpenedAt = Instant.now();
            log.warn("{} 连续 {} 次失败 · 短熔断 {}s", platformDef().label(), consecutiveFailures, COOLDOWN_MS / 1000);
        }
    }
}
