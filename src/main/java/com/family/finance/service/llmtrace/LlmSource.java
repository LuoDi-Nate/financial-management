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
        return source == null || source.path("platform").asText().isBlank() ? legacy : source.path("displayName").asText("自有服务") + " · " + source.path("model").asText();
    }
    public static String withRecord(String raw, Long id) {
        if(raw==null && id==null) return null;
        try {
            var value=raw==null ? JSON.createObjectNode() : (com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(raw);
            if(id != null) value.put("promptRecordId",id);
            return JSON.writeValueAsString(value);
        } catch(Exception e) { return raw; }
    }
    public static Long recordId(String raw) {
        var source=parse(raw); if(source==null || !source.path("promptRecordId").canConvertToLong()) return null;
        return source.path("promptRecordId").asLong();
    }
    public java.util.Map<Long,String> forAsk(java.util.List<com.family.finance.domain.ask.AskMessage> messages) {
        java.util.Map<Long,String> labels=new java.util.HashMap<>();String source=null;
        if(messages==null) return labels;
        for(var m:messages) {
            if("user".equals(m.getRole())) source=m.getSourceJson();
            else if("assistant".equals(m.getRole()) && source!=null) labels.put(m.getId(),label(source,"未记录完整模型来源"));
        }
        return labels;
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
