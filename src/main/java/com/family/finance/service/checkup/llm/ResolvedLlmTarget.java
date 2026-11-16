package com.family.finance.service.checkup.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-local secrets; only sourceJson is allowed to leave the invocation pipeline. */
public final class ResolvedLlmTarget {
    private final long familyId;
    private final URI endpoint;
    private final String key, revision, fingerprint, name, platform;
    private final boolean local;
    private final int timeout, tokens;
    private final double temperature;
    ResolvedLlmTarget(long fid, URI endpoint, String key, String revision, String fingerprint, String name,
            String platform, boolean local, int timeout, int tokens, double temperature) {
        familyId = fid; this.endpoint = endpoint; this.key = key; this.revision = revision;
        this.fingerprint = fingerprint; this.name = name; this.platform = platform; this.local = local;
        this.timeout = timeout; this.tokens = tokens; this.temperature = temperature;
    }
    public long familyId() { return familyId; }
    public URI endpoint() { return endpoint; }
    public String apiKey() { return key; }
    public String revision() { return revision; }
    public String fingerprint() { return fingerprint; }
    public boolean allowLocalHttp() { return local; }
    public int timeout() { return timeout; }
    public int maxTokens() { return tokens; }
    public double temperature() { return temperature; }
    public boolean custom() { return platform.equals(LlmCatalog.P_CUSTOM); }
    public String sourceJson(String model, String family) {
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("platform", platform); source.put("displayName", name); source.put("model", model == null ? "auto" : model);
            source.put("modality", LlmCatalog.platform(platform).flatMap(p -> p.family(family))
                .map(f -> f.modality().name()).orElse("TEXT"));
            source.put("customRevision", revision); source.put("selectionFingerprint", fingerprint);
            return new ObjectMapper().writeValueAsString(source);
        } catch (Exception e) { throw new IllegalStateException("无法记录模型来源"); }
    }
    @Override public String toString() { return "ResolvedLlmTarget[private]"; }
}
