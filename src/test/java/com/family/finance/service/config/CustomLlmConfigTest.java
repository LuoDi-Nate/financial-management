package com.family.finance.service.config;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.checkup.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomLlmConfigTest {
    final Map<String,String> store = new HashMap<>();
    final ObjectMapper json = new ObjectMapper();
    FamilyRuntimeConfigMapper mapper;
    FamilyConfigService config;
    CustomLlmConfigService service;
    @BeforeEach void setup() {
        mapper = mock(FamilyRuntimeConfigMapper.class); config = mock(FamilyConfigService.class);
        when(mapper.lockFamily(1)).thenReturn(1L);
        when(config.getAll(1)).thenAnswer(a -> new HashMap<>(store));
        when(mapper.upsert(eq(1L), anyString(), anyString())).thenAnswer(a -> { store.put(a.getArgument(1), a.getArgument(2)); return 1; });
        service = new CustomLlmConfigService(mapper, config, new CustomEndpointPolicy(), json, "");
    }
    void initial() { service.save(1, "合成服务", "https://gateway.example.com/prefix/v1", "BEARER", "synthetic-secret-original", false, ""); }
    @Test void oneRowRoundTripMaskAndNoSecretsInToString() {
        initial(); var saved = service.load(1);
        assertThat(store).hasSize(1); assertThat(saved.apiKey()).isEqualTo("synthetic-secret-original");
        assertThat(service.card(1).masked()).doesNotContain("secret-original");
        assertThat(saved.toString()).doesNotContain(saved.apiKey(), saved.baseUrl);
        verify(config, never()).set(anyLong(),anyString(),anyString());
    }
    @Test void addressChangeCannotReuseOldKeyAndFailureWritesNothing() {
        initial(); String before = store.get(CustomLlmConfigService.KEY); var old = service.load(1);
        assertThatThrownBy(() -> service.save(1,"新名称","https://another.example.com/v1","BEARER","",false,old.editToken)).hasMessageContaining("重新输入");
        assertThat(store.get(CustomLlmConfigService.KEY)).isEqualTo(before);
        service.save(1,"新名称","https://another.example.com/v1","BEARER","synthetic-new-key",false,old.editToken);
        assertThat(service.load(1).revision).isNotEqualTo(old.revision);
    }
    @Test void nameOnlyChangeRetainsConnectionRevisionButRejectsStaleEditor() {
        initial(); var old = service.load(1);
        service.save(1,"改名","https://gateway.example.com/prefix/v1/","BEARER","",false,old.editToken);
        assertThat(service.load(1).revision).isEqualTo(old.revision);
        assertThat(service.load(1).editToken).isNotEqualTo(old.editToken);
        assertThatThrownBy(() -> service.save(1,"旧页","https://gateway.example.com/prefix/v1","BEARER","",false,old.editToken)).hasMessageContaining("刷新");
    }
    @Test void noAuthErasesOldCredentialAndChangingBackRequiresNewKey() {
        initial(); service.save(1,"","http://localhost:11434/v1","NONE","ignored",true,service.load(1).editToken);
        var saved = service.load(1); assertThat(saved.apiKey()).isEmpty(); assertThat(saved.configured()).isTrue();
        assertThatThrownBy(() -> service.save(1,"",saved.baseUrl,"BEARER","",true,saved.editToken)).hasMessageContaining("重新输入");
    }
    @Test void badConfigDoesNotBecomeEnvironmentOrAnotherPlatform() {
        store.put(CustomLlmConfigService.KEY,"invalid-json");
        assertThatThrownBy(() -> service.load(1)).isInstanceOf(CustomLlmFailure.class);
        assertThat(service.card(1).ready()).isFalse(); assertThat(service.card(1).error()).isNotBlank();
    }
    @Test void completeExecutionIsImmutableAndStopsAfterServiceChange() throws Exception {
        initial(); store.putAll(Map.of("llm_platform","custom","llm_family","custom","llm_model_id","auto","llm_vision_enabled","false"));
        when(config.llmSnapshot(1)).thenAnswer(a -> new HashMap<>(store));
        var resolver = new LlmTargetResolver(config, service); var execution = resolver.load(1);
        var invocation = resolver.prepare(1, execution.settings.primary(), execution);
        assertThat(invocation.model()).isEqualTo("auto"); assertThat(invocation.auto()).isFalse();
        assertThat(invocation.target().endpoint().toString()).endsWith("/prefix/v1/chat/completions");
        assertThat(invocation.sourceJson()).contains("合成服务", "auto").doesNotContain("gateway.example.com", "synthetic-secret");
        assertThat(json.writeValueAsString(invocation)).doesNotContain("apiKey","gateway.example.com","synthetic-secret");
        service.save(1,"","https://changed.example.com/v1","BEARER","new-key",false,service.load(1).editToken);
        assertThat(invocation.target().apiKey()).isEqualTo("synthetic-secret-original");
        assertThatThrownBy(() -> resolver.assertCurrent(invocation.target())).isInstanceOf(CustomLlmFailure.class).hasMessageContaining("已更新");
    }
}
