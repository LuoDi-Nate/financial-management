package com.family.finance.service.llmtrace;

import com.family.finance.service.checkup.llm.LlmTargetResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service("llmSource")
public class LlmSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LlmTargetResolver targets;
    public LlmSource(LlmTargetResolver targets) { this.targets = targets; }
    public record Context(String fingerprint, boolean custom) {
        public boolean current(String raw) {
            var source = parse(raw);
            if (source == null) return !custom;
            if (!custom && !source.path("platform").asText().equals("custom")) return true;
            return fingerprint.equals(source.path("selectionFingerprint").asText());
        }
        public boolean historical(String raw) { return !current(raw); }
    }
    public Context context(long fid) { var e = targets.load(fid); return new Context(e.fingerprint,e.usesCustom()); }
    public boolean current(long fid, String raw) { return context(fid).current(raw); }
    public String cacheSalt(long fid) { var c = context(fid); return c.custom ? c.fingerprint : ""; }
    public static String label(String raw, String legacy) {
        var source = parse(raw);
        return source == null ? legacy : source.path("displayName").asText("自有服务") + " · " + source.path("model").asText();
    }
    public static String legacyVendor(String badge) {
        if (badge != null && badge.startsWith("custom")) return "custom";
        if (badge == null || badge.length() <= 20) return badge;
        String code = badge.split("[ ·/]", 2)[0];
        return code.length() <= 20 ? code : "model";
    }
    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { var value = JSON.readTree(raw); return value.isObject() ? value : null; }
        catch (Exception e) { return null; }
    }
}
