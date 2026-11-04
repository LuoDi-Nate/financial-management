package com.family.finance.service.ask.runtime;

import com.family.finance.repository.AskAuditMapper;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedTemplateStatusTest {
    @Test void renderingStatusNeverFetchesRemoteDefinition() {
        var cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(a -> a.getArgument(2));
        var runtime = spy(new ManagedAgentRuntime(cfg, mock(AskToolRegistry.class), mock(AskAuditMapper.class)));
        for (int i = 0; i < 3; i++) {
            assertThat(runtime.templateStatus().drift()).isNull();
        }
        verify(runtime, never()).templateDrift();
    }

    @Test void explicitCheckIsRetainedButChangingConfigurationDropsOldResult() {
        var cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(a -> a.getArgument(2));
        var runtime = new ManagedAgentRuntime(cfg, mock(AskToolRegistry.class), mock(AskAuditMapper.class));
        assertThat(runtime.templateDrift().error()).contains("还没创建");
        assertThat(runtime.templateStatus().checkedAt()).isNotNull();
        assertThat(runtime.templateStatus().drift()).isNotNull();
        when(cfg.getString(1, FamilyConfigService.K_ASK_MA_AGENT_ID, "")).thenReturn("new-test-agent");
        assertThat(runtime.templateStatus().drift()).isNull();
    }
}
