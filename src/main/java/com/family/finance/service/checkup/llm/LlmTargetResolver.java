package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.CustomLlmConfigService;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.llmtrace.PromptRecorder;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Map;
import static com.family.finance.service.config.FamilyConfigService.*;

@Service
public class LlmTargetResolver {
    private final FamilyConfigService config;
    private final CustomLlmConfigService custom;
    public LlmTargetResolver(FamilyConfigService config, CustomLlmConfigService custom) { this.config = config; this.custom = custom; }
    public static final class Execution {
        public final LlmSettings settings;
        private final Map<String, String> values;
        private final CustomLlmConfigService.Snapshot service;
        public final String fingerprint;
        Execution(LlmSettings settings, Map<String, String> values, CustomLlmConfigService.Snapshot service, String fingerprint) {
            this.settings = settings; this.values = values; this.service = service; this.fingerprint = fingerprint;
        }
        @Override public String toString() { return "LlmExecution[private]"; }
    }
    public Execution load(long fid) {
        Map<String, String> values = config.llmSnapshot(fid);
        LlmSettings settings = LlmSettings.from(values);
        CustomLlmConfigService.Snapshot service = null;
        boolean selected = settings.chain().stream().anyMatch(i -> i.platform().equals(LlmCatalog.P_CUSTOM))
            || settings.visionEnabled() && settings.vision().platform().equals(LlmCatalog.P_CUSTOM);
        try { service = custom.from(values); } catch (CustomLlmFailure ignored) { }
        String identity = settings.primary().label() + "|" + settings.backup().map(LlmInvocation::label).orElse("") + "|"
            + settings.visionEnabled() + "|" + settings.vision().label() + "|" + values.getOrDefault(K_LLM_MAX_TOKENS, "2000")
            + "|" + values.getOrDefault(K_LLM_TEMPERATURE, "0.5") + "|" + values.getOrDefault(K_LLM_TIMEOUT_SECS, "25")
            + (selected ? "|" + (service == null ? "invalid" : service.revision) : "");
        return new Execution(settings, values, service, PromptRecorder.sha256(identity));
    }
    public boolean configured(long fid, String platform) { return configured(load(fid), platform); }
    public boolean configured(Execution e, String platform) {
        if (LlmCatalog.P_CUSTOM.equals(platform)) return e.service != null && e.service.configured();
        return LlmCatalog.platform(platform).map(p -> !e.values.getOrDefault(p.keyName(), "").isBlank()).orElse(false);
    }
    public LlmInvocation prepare(long fid, LlmInvocation inv) { return prepare(fid, inv, load(fid)); }
    public LlmInvocation prepare(long fid, LlmInvocation inv, Execution e) {
        if (!inv.resolvable() || !configured(e, inv.platform())) throw new CustomLlmFailure(CustomLlmFailure.Kind.CONFIG);
        boolean c = inv.platform().equals(LlmCatalog.P_CUSTOM);
        var p = inv.platformDef().orElseThrow();
        var s = e.service;
        String key = c ? s.apiKey() : e.values.getOrDefault(p.keyName(), "");
        return inv.withTarget(new ResolvedLlmTarget(fid, URI.create((c ? s.baseUrl : p.baseUrl()) + "/chat/completions"), key,
            c ? s.revision : "", e.fingerprint, c ? s.displayName : p.label(), p.code(), c && s.allowLocalHttp,
            integer(e.values, K_LLM_TIMEOUT_SECS, 25, 5, 120), integer(e.values, K_LLM_MAX_TOKENS, 2000, 500, 8000),
            temperature(e.values)));
    }
    public void assertCurrent(ResolvedLlmTarget target) {
        if (!load(target.familyId()).fingerprint.equals(target.fingerprint())) throw new CustomLlmFailure(CustomLlmFailure.Kind.CONFIG_CHANGED);
    }
    public String fingerprint(long fid) { return load(fid).fingerprint; }
    public boolean usesCustom(long fid) {
        var s = load(fid).settings;
        return s.chain().stream().anyMatch(i -> i.platform().equals(LlmCatalog.P_CUSTOM))
            || s.visionEnabled() && s.vision().platform().equals(LlmCatalog.P_CUSTOM);
    }
    private static int integer(Map<String, String> m, String key, int d, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(m.getOrDefault(key, String.valueOf(d))))); }
        catch (Exception e) { return d; }
    }
    private static double temperature(Map<String, String> m) {
        try { return Math.max(0, Math.min(1, Double.parseDouble(m.getOrDefault(K_LLM_TEMPERATURE, "0.5")))); }
        catch (Exception e) { return 0.5; }
    }
}
