package com.family.finance.service.llmtrace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.28 · 一次 AI 调用的「随行说明」(tech-design v1.28 选型一)。
 *
 * <p>调用方填三样:用在哪、代号对照(代号 → 真名,只给本家庭看,不发给 AI)、用了哪些设置;
 * {@link com.family.finance.service.checkup.llm.LlmRouter} 在调用循环结束时把真正发出去的原文存下来,
 * 并把记录编号写回 {@link #recordId()}。调用方把编号放进结果,页面上的 {@code >_} 只读这条记录 —— 不重拼。</p>
 */
public final class PromptTrace {

    private final PromptSurface surface;
    private final Map<String, String> legend = new LinkedHashMap<>();
    private String settingsNote;
    private Long recordId;
    private String rejectReason;
    private String failureMessage;

    private PromptTrace(PromptSurface surface) {
        this.surface = surface;
    }

    public static PromptTrace of(PromptSurface surface) {
        return new PromptTrace(surface);
    }

    /** 代号 → 真名(成员 / 账户)· 只进面板末尾的注释行 */
    public PromptTrace legend(Map<String, String> codenameToReal) {
        if (codenameToReal != null) codenameToReal.forEach((k, v) -> {
            if (k != null && v != null && !k.equals(v)) legend.put(k, v);
        });
        return this;
    }

    /** 这一次用了哪些设置(如「模板「稳健守护」· 范围「金融资产」· 分析偏好 2 条」)· null = 没用任何设置 */
    public PromptTrace settings(String note) {
        this.settingsNote = note == null || note.isBlank() ? null : note;
        return this;
    }

    public PromptSurface surface() { return surface; }

    public Map<String, String> legendMap() { return legend; }

    public String settingsNote() { return settingsNote; }

    public Long recordId() { return recordId; }

    void recordId(Long id) { this.recordId = id; }

    /** 调用方在处理器里判「不收这次回答」时顺手记一句为什么(进面板的「回答没采用」那一行) */
    public void rejected(String reason) {
        if (reason != null && !reason.isBlank()) this.rejectReason = reason;
    }

    public String rejectReason() { return rejectReason; }

    public void failed(String safeMessage) { this.failureMessage = safeMessage; }

    /** 请求局部的安全提示,不存原始异常/密钥,不共享到下一次请求。 */
    public String failureMessage() {
        return failureMessage == null ? "AI 调用未成功,请稍后重试或到「AI 接入」检查配置。" : failureMessage;
    }
}
