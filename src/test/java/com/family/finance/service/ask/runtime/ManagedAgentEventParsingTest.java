package com.family.finance.service.ask.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.19.14 · 百炼会话事件流的两处解析。
 *
 * <p>这里的报文<b>照抄自真实的流</b>(文本换成了无意义占位)。写死这两条的理由:
 * 终止信号和答案正文都藏在 {@code content} 数组的第二层里,靠猜写不对 ——
 * 而猜错的表现分别是「流一直挂到超时」和「答案是空的」,两种都不报错。</p>
 */
class ManagedAgentEventParsingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode n(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("终止信号在 content[].data.session_status,不在顶层")
    void readsSessionStatusFromNestedData() {
        JsonNode e = n("""
            {"object":"message","type":"session_status","status":"completed",
             "content":[{"type":"data","data":{"stop_reason":{"type":"end_turn"},
                                               "session_status":"idle"}}]}""");
        assertThat(ManagedAgentRuntime.sessionStatus(e)).isEqualTo("idle");
    }

    @Test
    @DisplayName("事件自身的 status=completed 不能被当成会话状态 —— 每个事件都带它,会当场截断第一轮")
    void doesNotMistakeEventStatusForSessionStatus() {
        JsonNode e = n("""
            {"object":"message","type":"session_status","status":"completed","content":[]}""");
        assertThat(ManagedAgentRuntime.sessionStatus(e)).isNull();
    }

    @Test
    @DisplayName("答案正文从 content 里所有 text 块拼出来")
    void joinsTextBlocks() {
        JsonNode e = n("""
            {"type":"message","role":"assistant",
             "content":[{"type":"text","text":"前半"},{"type":"text","text":"后半"}]}""");
        assertThat(ManagedAgentRuntime.textOf(e.path("content"))).isEqualTo("前半后半");
    }

    @Test
    @DisplayName("非 text 块(工具进度里的 data)不许混进正文")
    void ignoresNonTextBlocks() {
        JsonNode e = n("""
            {"type":"model_request_end",
             "content":[{"type":"data","data":{"model":"qwen","output_tokens":494}}]}""");
        assertThat(ManagedAgentRuntime.textOf(e.path("content"))).isEmpty();
    }

    @Test
    @DisplayName("content 缺失 / 不是数组时都不能抛 —— 流里什么形状都可能来")
    void nullSafe() {
        assertThat(ManagedAgentRuntime.textOf(n("{}").path("content"))).isEmpty();
        assertThat(ManagedAgentRuntime.textOf(n("{\"content\":\"纯字符串\"}").path("content"))).isEmpty();
        assertThat(ManagedAgentRuntime.sessionStatus(n("{}"))).isNull();
        assertThat(ManagedAgentRuntime.sessionStatus(n("{\"content\":[{}]}"))).isNull();
    }
}
