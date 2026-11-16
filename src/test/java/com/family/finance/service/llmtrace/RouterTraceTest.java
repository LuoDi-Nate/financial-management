package com.family.finance.service.llmtrace;

import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PromptRecordMapper;
import com.family.finance.service.checkup.llm.LlmClient;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.28 · 记录只在 LlmRouter 的调用循环里写,写的就是交给客户端的那两个字符串(护栏 v128-PEEK-STORED-NOT-REBUILT)。
 */
class RouterTraceTest {

    private static LlmClient client(String reply, RuntimeException fail) {
        LlmClient c = mock(LlmClient.class);
        when(c.platform()).thenReturn(com.family.finance.service.checkup.llm.LlmCatalog.P_DASHSCOPE);
        when(c.available()).thenReturn(true);
        if (fail != null) when(c.chat(any(), anyString(), anyString())).thenThrow(fail);
        else when(c.chat(any(), anyString(), anyString())).thenReturn(reply);
        return c;
    }

    /** 主选 = 百炼 · 通义(与 LlmRouterPrimaryOrderTest 同一种冒充法) */
    private static FamilyConfigService config() {
        java.util.Map<String, String> v = java.util.Map.of(
                FamilyConfigService.K_LLM_PLATFORM, "dashscope",
                FamilyConfigService.K_LLM_FAMILY, "qwen");
        FamilyConfigService cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(i ->
                v.getOrDefault((String) i.getArgument(1), i.getArgument(2)));
        when(cfg.getBoolean(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(i -> i.getArgument(2));
        return cfg;
    }

    private record Setup(LlmRouter router, PromptRecordMapper mapper) {}

    private static Setup setup(LlmClient c) {
        LlmRouter router = new LlmRouter(List.of(c), config());
        PromptRecordMapper mapper = mock(PromptRecordMapper.class);
        doAnswer(i -> { ((PromptRecordMapper.Row) i.getArgument(0)).id = 42L; return 1; }).when(mapper).insert(any());
        router.setPromptRecorder(new PromptRecorder(mapper, mock(FamilyMapper.class)));
        return new Setup(router, mapper);
    }

    @Test
    void 接受了_记OK_原文逐字() {
        var s = setup(client("好的", null));
        assertThat(s.router().plan(1L)).as("脚手架:主选百炼应当可用").isNotEmpty();
        PromptTrace t = PromptTrace.of(PromptSurface.REVIEW);
        String out = s.router().invoke(1L, t, "SYS 规矩", "USER 数据 ¥100", (inv, raw, ms) -> raw);
        assertThat(out).isEqualTo("好的");
        ArgumentCaptor<PromptRecordMapper.Row> cap = ArgumentCaptor.forClass(PromptRecordMapper.Row.class);
        verify(s.mapper()).insert(cap.capture());
        assertThat(cap.getValue().userText).isEqualTo("USER 数据 ¥100");
        assertThat(cap.getValue().outcome).isEqualTo(PromptRecorder.OK);
        verify(s.mapper()).insertSystem(eq(1L), eq(PromptRecorder.sha256("SYS 规矩")), eq("SYS 规矩"));
        assertThat(t.recordId()).isEqualTo(42L);
    }

    @Test
    void 拒收了_记REJECTED与原因() {
        var s = setup(client("胡话", null));
        assertThat(s.router().plan(1L)).isNotEmpty();
        PromptTrace t = PromptTrace.of(PromptSurface.DIAGNOSE_FAMILY);
        String out = s.router().invoke(1L, t, "S", "U", (inv, raw, ms) -> { t.rejected("出现了材料里没有的金额"); return null; });
        assertThat(out).isNull();
        ArgumentCaptor<PromptRecordMapper.Row> cap = ArgumentCaptor.forClass(PromptRecordMapper.Row.class);
        verify(s.mapper()).insert(cap.capture());
        assertThat(cap.getValue().outcome).isEqualTo(PromptRecorder.REJECTED);
        assertThat(cap.getValue().outcomeNote).isEqualTo("出现了材料里没有的金额");
    }

    @Test
    void 调用失败_记FAILED与安全分类不保留上游敏感原文() {
        var s = setup(client(null, new IllegalStateException("Arrearage: Access denied synthetic-secret")));
        assertThat(s.router().plan(1L)).isNotEmpty();
        PromptTrace t = PromptTrace.of(PromptSurface.LENS_INSIGHT);
        s.router().invoke(1L, t, "S", "U", (inv, raw, ms) -> raw);
        ArgumentCaptor<PromptRecordMapper.Row> cap = ArgumentCaptor.forClass(PromptRecordMapper.Row.class);
        verify(s.mapper()).insert(cap.capture());
        assertThat(cap.getValue().outcome).isEqualTo(PromptRecorder.FAILED);
        assertThat(cap.getValue().outcomeNote).contains("余额或权限").doesNotContain("Arrearage: Access denied","synthetic-secret");
    }

    @Test
    void 没有可用候选_什么都没发_不记() {
        LlmClient c = client("x", null);
        when(c.available()).thenReturn(false);
        var s = setup(c);
        s.router().invoke(1L, PromptTrace.of(PromptSurface.REVIEW), "S", "U", (inv, raw, ms) -> raw);
        verify(s.mapper(), never()).insert(any());
    }

    @Test
    void 代号对照只留这一次出现过的() {
        PromptRecordMapper mapper = mock(PromptRecordMapper.class);
        var rec = new PromptRecorder(mapper, mock(FamilyMapper.class));
        PromptTrace t = PromptTrace.of(PromptSurface.REBALANCE)
                .legend(java.util.Map.of("账户A", "招行", "账户B", "富途", "成员A", "爸爸"));
        rec.save(1L, t, "S", "从账户A 调到 账户A", "v", PromptRecorder.OK, null);
        ArgumentCaptor<PromptRecordMapper.Row> cap = ArgumentCaptor.forClass(PromptRecordMapper.Row.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().legendJson).contains("账户A").doesNotContain("富途").doesNotContain("爸爸");
    }

    @Test
    void 记录失败不影响分析() {
        PromptRecordMapper mapper = mock(PromptRecordMapper.class);
        when(mapper.insertSystem(anyLong(), anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        var rec = new PromptRecorder(mapper, mock(FamilyMapper.class));
        assertThat(rec.save(1L, PromptTrace.of(PromptSurface.REVIEW), "S", "U", "v", PromptRecorder.OK, null)).isNull();
    }
}
