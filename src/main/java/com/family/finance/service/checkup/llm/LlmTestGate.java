package com.family.finance.service.checkup.llm;

import com.family.finance.repository.LlmTestMeterMapper;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LlmTestGate {
    public static final String MESSAGE = "测试环境 · 未调用";
    /** Instance-wide operational state belongs to the single household; QA families inherit it. */
    public static final long OWNER_FAMILY_ID = 1L;
    private final FamilyConfigService config;
    private final LlmTestMeter meter;
    private final LlmTestMeterMapper mapper;
    private final PromptRecorder recorder;
    private final ObjectMapper json = new ObjectMapper();

    public record Mode(boolean enabled, String epoch, String openedAt) { }
    public record Status(boolean enabled, String epoch, String openedAt, long blocked, long sent,
                         List<LlmTestMeterMapper.Counter> entries) { }

    public static final class Blocked extends IllegalStateException {
        public Blocked() { super(MESSAGE); }
    }

    public Mode mode() {
        String raw = config.getString(OWNER_FAMILY_ID, FamilyConfigService.K_LLM_TEST_MODE, "");
        if (raw == null || raw.isBlank()) return new Mode(false, "", null);
        try {
            var node = json.readTree(raw);
            if (!node.path("enabled").isBoolean()) throw new IllegalArgumentException();
            Mode mode = json.treeToValue(node, Mode.class);
            if (mode.enabled() && (mode.epoch() == null || mode.openedAt() == null
                    || !UUID.fromString(mode.epoch()).toString().equals(mode.epoch())))
                throw new IllegalArgumentException();
            return mode;
        } catch (Exception e) {
            throw new IllegalStateException("无法确认测试环境开关,已停止模型请求");
        }
    }

    public boolean enabled() { return mode().enabled(); }

    public synchronized void setEnabled(boolean enabled) {
        Mode old = mode();
        if (old.enabled() == enabled) return;
        Mode next = enabled ? new Mode(true, UUID.randomUUID().toString(), LocalDateTime.now().toString())
                : new Mode(false, old.epoch(), old.openedAt());
        try {
            config.set(OWNER_FAMILY_ID, FamilyConfigService.K_LLM_TEST_MODE, json.writeValueAsString(next));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("测试环境开关未保存");
        }
    }

    public Status status() {
        Mode mode = mode();
        List<LlmTestMeterMapper.Counter> entries = mode.epoch() == null || mode.epoch().isBlank()
                ? List.of() : mapper.find(OWNER_FAMILY_ID, mode.epoch());
        return new Status(mode.enabled(), mode.epoch(), mode.openedAt(),
                entries.stream().mapToLong(LlmTestMeterMapper.Counter::blocked).sum(),
                entries.stream().mapToLong(LlmTestMeterMapper.Counter::sent).sum(), List.copyOf(entries));
    }

    public boolean block(String surface) {
        Mode mode = mode();
        if (!mode.enabled()) return false;
        meter.record(OWNER_FAMILY_ID, mode.epoch(), surface, true);
        return true;
    }

    public boolean block(long familyId, PromptTrace trace, String system, String user) {
        PromptTrace actual = trace == null ? PromptTrace.of(PromptSurface.OTHER_TEXT) : trace;
        if (!block(actual.surface().name())) return false;
        actual.failed(MESSAGE);
        recorder.save(familyId, actual, system, user, MESSAGE, PromptRecorder.BLOCKED, MESSAGE);
        return true;
    }

    /** Last check directly at the outbound boundary, including callers bypassing the router. */
    public void beforeSend(String surface) {
        if (block(surface)) throw new Blocked();
    }

    /** Independent outbound receipt: a transmission observed while enabled makes QA fail. */
    public void observedSend(String surface, LocalDateTime dispatchedAt) {
        Mode mode = mode();
        if (mode.enabled() && !dispatchedAt.isBefore(LocalDateTime.parse(mode.openedAt())))
            meter.record(OWNER_FAMILY_ID, mode.epoch(), surface, false);
    }
}
