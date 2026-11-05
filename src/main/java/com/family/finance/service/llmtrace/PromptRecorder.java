package com.family.finance.service.llmtrace;

import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PromptRecordMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.28 · 把「真正发给 AI 的内容」存下来,给页面上的 {@code >_} 读(PRD FR-909 / FR-915)。
 *
 * <p><b>只有两个地方调 {@link #save}</b>:{@code LlmRouter} 的调用循环结束时、超级 Agent 真正发出去的那一刻。
 * 存的就是交给大模型客户端的两个字符串 —— 页面只读记录,不重拼(护栏 {@code v128-PEEK-STORED-NOT-REBUILT})。</p>
 *
 * <p>记录失败<b>绝不影响分析本身</b>:吞掉异常、记一行告警、返回 null(页面照实说「这次没记下来」)。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptRecorder {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";
    /** 超级 Agent:发出去那一刻记下,回答是流式的,结果不回写 */
    public static final String SENT = "SENT";
    public static final String BLOCKED = "BLOCKED";

    static final int SHORT_DAYS = 2;
    static final int LONG_DAYS = 90;

    private final PromptRecordMapper mapper;
    private final FamilyMapper familyMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 存一次调用 · 返回记录编号(失败返回 null)· 同时写回 {@code trace.recordId()} */
    public Long save(long familyId, PromptTrace trace, String system, String user,
                     String vendor, String outcome, String note) {
        if (trace == null) return null;
        try {
            String sys = system == null ? "" : system;
            String hash = sha256(sys);
            mapper.insertSystem(familyId, hash, sys);
            PromptRecordMapper.Row r = new PromptRecordMapper.Row();
            r.familyId = familyId;
            r.surface = trace.surface().name();
            r.systemHash = hash;
            r.userText = user == null ? "" : user;
            r.vendor = cut(vendor, 80);
            r.outcome = outcome;
            r.outcomeNote = cut(note, 500);
            r.settingsNote = cut(trace.settingsNote(), 200);
            // 代号对照只留这一次真的出现过的(家里 20 个账户、这次只发了 6 个 → 面板只列 6 个)
            Map<String, String> used = new LinkedHashMap<>();
            trace.legendMap().forEach((code, real) -> {
                if (sys.contains(code) || r.userText.contains(code)) used.put(code, real);
            });
            r.legendJson = used.isEmpty() ? null : objectMapper.writeValueAsString(used);
            mapper.insert(r);
            trace.recordId(r.id);
            return r.id;
        } catch (Exception e) {
            log.warn("记录发给 AI 的内容失败(不影响分析本身)· surface={} · {}", trace.surface(), e.toString());
            return null;
        }
    }

    /** 调用方在路由之后自己的校验没通过(如调仓的输出校验)—— 补记「发出去了,回答没采用」 */
    public void markRejected(long familyId, Long id, String reason) {
        if (id == null) return;
        try {
            mapper.updateOutcome(familyId, id, REJECTED, cut(reason, 500));
        } catch (Exception e) {
            log.warn("补记「回答没采用」失败 · id={} · {}", id, e.toString());
        }
    }

    public Optional<PromptRecordMapper.Row> find(long familyId, long id) {
        return Optional.ofNullable(mapper.find(familyId, id));
    }

    /** 结果被新一次覆盖时,旧记录跟着走(FR-915「跟着结果走」) */
    public void forget(long familyId, Long id) {
        if (id == null) return;
        try {
            mapper.delete(familyId, id);
        } catch (Exception e) {
            log.debug("删旧记录失败 · id={} · {}", id, e.toString());
        }
    }

    /** 代号 → 真名(面板末尾注释行用)· 解析失败给空表 */
    public Map<String, String> legend(PromptRecordMapper.Row r) {
        if (r == null || r.legendJson == null || r.legendJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(r.legendJson, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 每天 04:17:内存结果那几处留 2 天,其余 90 天;没人引用的规矩文本一起清 */
    @Scheduled(cron = "0 17 4 * * *")
    public void cleanup() {
        List<String> shortOnes = Arrays.stream(PromptSurface.values()).filter(PromptSurface::isShortLived).map(Enum::name).toList();
        List<String> longOnes = Arrays.stream(PromptSurface.values()).filter(s -> !s.isShortLived()).map(Enum::name).toList();
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (var f : familyMapper.findAll()) {
            try {
                n += mapper.deleteOlderThan(f.getId(), now.minusDays(SHORT_DAYS), shortOnes);
                n += mapper.deleteOlderThan(f.getId(), now.minusDays(LONG_DAYS), longOnes);
                mapper.deleteOrphanSystems(f.getId());
            } catch (Exception e) {
                log.warn("清理发给 AI 的内容记录失败 · family={} · {}", f.getId(), e.toString());
            }
        }
        if (n > 0) log.info("清理发给 AI 的内容记录 · {} 条", n);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
