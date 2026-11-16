package com.family.finance.service.checkup.llm;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@org.springframework.stereotype.Component
public class CustomLlmClient implements LlmClient {
    private final LlmTargetResolver targets;
    private final CustomLlmTransport transport;
    private final ConcurrentHashMap<String, Instant> cooldown = new ConcurrentHashMap<>();
    public CustomLlmClient(LlmTargetResolver targets, CustomLlmTransport transport) { this.targets = targets; this.transport = transport; }
    @Override public String platform() { return LlmCatalog.P_CUSTOM; }
    @Override public boolean available() { return targets.configured(1L, platform()); }
    private String identity(LlmInvocation inv) { return inv.target().familyId() + "|" + inv.target().revision(); }
    @Override public boolean available(LlmInvocation inv) {
        if (inv.target() == null) return available();
        Instant now = Instant.now();
        return cooldown.getOrDefault(identity(inv), Instant.EPOCH).isBefore(now)
            && cooldown.getOrDefault(identity(inv) + "|" + inv.family() + "|" + inv.model(), Instant.EPOCH).isBefore(now);
    }
    @Override public void recordSuccess(LlmInvocation inv) {
        cooldown.remove(identity(inv)); cooldown.remove(identity(inv) + "|" + inv.family() + "|" + inv.model());
    }
    @Override public void recordFailure(LlmInvocation inv, Exception failure) {
        if (failure instanceof LlmTestGate.Blocked || failure instanceof CustomLlmFailure f && f.stopPlan()) return;
        String key = identity(inv) + (failure instanceof CustomLlmFailure f && f.accountFatal() ? "" : "|" + inv.family() + "|" + inv.model());
        cooldown.put(key, Instant.now().plusSeconds(60));
        if (cooldown.size() > 256) cooldown.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
    }
    @Override public String chat(LlmInvocation invocation, String system, String user) {
        LlmInvocation inv = invocation.target() == null ? targets.prepare(1L, invocation) : invocation;
        ResolvedLlmTarget t = inv.target();
        try {
            Map<String, Object> response = transport.json(t, AbstractOpenAiCompatibleClient.textRequest(inv.resolvedModel(), system, user, t.temperature(), t.maxTokens()));
            String content = AbstractOpenAiCompatibleClient.responseContent(response);
            recordSuccess(inv);
            return content;
        } catch (Exception e) {
            recordFailure(inv, e);
            throw e;
        }
    }
}
