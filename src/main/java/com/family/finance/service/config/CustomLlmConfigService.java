package com.family.finance.service.config;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.checkup.llm.CustomEndpointPolicy;
import com.family.finance.service.checkup.llm.CustomLlmFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomLlmConfigService {
    public static final String KEY = "llm_custom_service_secret";
    private final FamilyRuntimeConfigMapper mapper;
    private final FamilyConfigService config;
    private final CustomEndpointPolicy policy;
    private final ObjectMapper json;
    private final String environment;
    private final String environmentRevision = UUID.randomUUID().toString();

    public CustomLlmConfigService(FamilyRuntimeConfigMapper mapper, FamilyConfigService config,
            CustomEndpointPolicy policy, ObjectMapper json,
            @Value("${FINANCE_LLM_CUSTOM_SERVICE_JSON:}") String environment) {
        this.mapper = mapper; this.config = config; this.policy = policy; this.json = json; this.environment = environment;
    }

    /** Secret-bearing value; never a record or a view model. */
    public static final class Snapshot {
        public final String revision, editToken, displayName, baseUrl, authMode;
        private final String apiKey;
        public final boolean allowLocalHttp;
        Snapshot(String revision, String editToken, String name, String url, String mode, String key, boolean local) {
            this.revision = revision; this.editToken = editToken; displayName = name; baseUrl = url;
            authMode = mode; apiKey = key; allowLocalHttp = local;
        }
        public String apiKey() { return apiKey; }
        public boolean configured() { return !baseUrl.isBlank() && (authMode.equals("NONE") || !apiKey.isBlank()); }
        @Override public String toString() { return "CustomLlmConfig[private]"; }
    }
    public record Card(String name, String baseUrl, String authMode, boolean allowLocalHttp, String editToken,
                       String revision, boolean ready, String masked, String error) { }

    public Snapshot load(long familyId) { return from(config.getAll(familyId)); }
    public Snapshot from(Map<String, String> values) {
        String raw = values.containsKey(KEY) ? values.get(KEY) : environment;
        if (raw == null || raw.isBlank()) return empty();
        try {
            var n = json.readTree(raw);
            if (n.path("version").asInt() != 1) throw failure();
            String mode = n.path("authMode").asText();
            if (!mode.equals("NONE") && !mode.equals("BEARER")) throw failure();
            boolean local = n.path("allowLocalHttp").asBoolean(false);
            String url = policy.normalize(n.path("baseUrl").asText(), local);
            String key = mode.equals("NONE") ? "" : cleanKey(n.path("apiKey").asText());
            String revision = values.containsKey(KEY) ? UUID.fromString(n.path("revision").asText()).toString() : environmentRevision;
            String edit = values.containsKey(KEY) ? UUID.fromString(n.path("editToken").asText()).toString() : environmentRevision;
            return new Snapshot(revision, edit, name(n.path("displayName").asText()), url, mode, key, local);
        } catch (Exception e) { throw failure(); }
    }

    public Card card(long fid) {
        try {
            Snapshot s = load(fid);
            return new Card(s.displayName, s.baseUrl, s.authMode, s.allowLocalHttp, s.editToken, s.revision,
                s.configured(), FamilyConfigService.maskSecret(s.apiKey), null);
        } catch (CustomLlmFailure e) { return new Card("自有服务", "", "BEARER", false, "", "", false, "", e.getMessage()); }
    }

    @Transactional
    public void save(long fid, String displayName, String rawUrl, String authMode, String rawKey,
                     boolean allowLocalHttp, String expectedEditToken) {
        if (mapper.lockFamily(fid) == null) throw failure();
        Snapshot old;
        try { old = load(fid); } catch (CustomLlmFailure e) { old = empty(); }
        if (!Objects.equals(old.editToken, expectedEditToken == null ? "" : expectedEditToken))
            throw new IllegalArgumentException("配置已更新,请刷新后重试");
        String label = name(displayName), url = policy.normalize(rawUrl, allowLocalHttp);
        if (!"BEARER".equals(authMode) && !"NONE".equals(authMode)) throw failure();
        String key = cleanKey(rawKey);
        if (authMode.equals("NONE")) key = "";
        else if (key.isBlank()) {
            if (!old.baseUrl.equals(url) || !old.authMode.equals("BEARER") || old.apiKey.isBlank())
                throw new IllegalArgumentException("服务地址或认证已变更,请重新输入此服务的密钥");
            key = old.apiKey;
        }
        boolean connectionChanged = !old.baseUrl.equals(url) || !old.authMode.equals(authMode)
            || !old.apiKey.equals(key) || old.allowLocalHttp != allowLocalHttp;
        String revision = connectionChanged ? UUID.randomUUID().toString() : old.revision;
        try {
            String encoded = json.writeValueAsString(Map.of("version", 1, "revision", revision,
                "editToken", UUID.randomUUID().toString(), "displayName", label, "baseUrl", url,
                "authMode", authMode, "apiKey", key, "allowLocalHttp", allowLocalHttp));
            mapper.upsert(fid, KEY, encoded);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw failure(); }
    }

    private static Snapshot empty() { return new Snapshot("", "", "自有服务", "", "BEARER", "", false); }
    private static String name(String raw) {
        String value = raw == null || raw.isBlank() ? "自有服务" : raw.trim();
        if (value.length() > 50 || value.chars().anyMatch(c -> Character.isISOControl(c))) throw failure();
        return value;
    }
    private static String cleanKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) throw failure();
        return value;
    }
    private static CustomLlmFailure failure() { return new CustomLlmFailure(CustomLlmFailure.Kind.CONFIG); }
}
