package com.family.finance.service.checkup.llm;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskAuditMapper;
import com.family.finance.repository.LlmTestMeterMapper;
import com.family.finance.service.ask.AskToolDispatcher;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.ask.runtime.AgentRuntime;
import com.family.finance.service.ask.runtime.AskSink;
import com.family.finance.service.ask.runtime.LocalToolLoopRuntime;
import com.family.finance.service.ask.runtime.ManagedAgentRuntime;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.holdingimport.VisionLlmClient;
import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmTestGateTest {
    final FamilyConfigService config = mock(FamilyConfigService.class);
    final LlmTestMeterMapper mapper = mock(LlmTestMeterMapper.class);
    final PromptRecorder recorder = mock(PromptRecorder.class);
    final Map<String,String> saved = new HashMap<>();
    LlmTestGate gate;

    @BeforeEach void setup() {
        when(config.getString(eq(1L),eq(FamilyConfigService.K_LLM_TEST_MODE),anyString()))
                .thenAnswer(i->saved.getOrDefault("mode",""));
        doAnswer(i->{saved.put("mode",i.getArgument(2));return null;})
                .when(config).set(eq(1L),eq(FamilyConfigService.K_LLM_TEST_MODE),anyString());
        when(mapper.upsertOwned(anyLong(),anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(mapper.find(anyLong(),anyString())).thenReturn(List.of());
        gate = new LlmTestGate(config,new LlmTestMeter(mapper),mapper,recorder);
    }

    @Test void defaultOffDoesNotTouchModelOrCounters() {
        assertThat(gate.enabled()).isFalse();assertThat(gate.block("X")).isFalse();
        verifyNoInteractions(mapper,recorder);
    }
    @Test void enabledStateSurvivesNewServiceInstance() {
        gate.setEnabled(true);String epoch=gate.mode().epoch();
        var restarted=new LlmTestGate(config,new LlmTestMeter(mapper),mapper,recorder);
        assertThat(restarted.enabled()).isTrue();assertThat(restarted.mode().epoch()).isEqualTo(epoch);
    }
    @Test void reopeningUsesNewCountingWindowWithoutDeletingOldReceipts() {
        gate.setEnabled(true);String first=gate.mode().epoch();gate.setEnabled(false);gate.setEnabled(true);
        assertThat(gate.mode().epoch()).isNotEqualTo(first);verifyNoInteractions(mapper);
    }
    @Test void repeatedSaveDoesNotResetEnabledWindow() {
        gate.setEnabled(true);String first=gate.mode().epoch();gate.setEnabled(true);
        assertThat(gate.mode().epoch()).isEqualTo(first);
    }
    @Test void corruptStateFailsClosed() {
        saved.put("mode","{}");assertThatThrownBy(gate::enabled).hasMessageContaining("已停止");
        assertThatThrownBy(()->gate.beforeSend("X")).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidCountingTimestampCannotCreateAnUnverifiableWindow() {
        gate.setEnabled(true);saved.put("mode",saved.get("mode").replace(gate.mode().openedAt(),"invalid-time"));
        assertThatThrownBy(gate::mode).hasMessageContaining("已停止");
    }
    @Test void blockedPromptRetainsCallerOwnershipAndSeparateInstanceCounters() {
        gate.setEnabled(true);var trace=PromptTrace.of(PromptSurface.GOAL_REPORT);
        assertThat(gate.block(44,trace,"rules","data")).isTrue();
        verify(mapper).upsertOwned(eq(1L),anyString(),eq("GOAL_REPORT"),eq(true));
        verify(recorder).save(44,trace,"rules","data",LlmTestGate.MESSAGE,PromptRecorder.BLOCKED,LlmTestGate.MESSAGE);
        assertThat(trace.failureMessage()).isEqualTo(LlmTestGate.MESSAGE);
    }
    @Test void outboundBoundaryRejectsBeforeAnyWork() {
        gate.setEnabled(true);assertThatThrownBy(()->gate.beforeSend("VISION_CLIENT")).isInstanceOf(LlmTestGate.Blocked.class);
        verify(mapper).upsertOwned(eq(1L),anyString(),eq("VISION_CLIENT"),eq(true));
    }
    @Test void independentTransmissionReceiptDetectsLeakAndIgnoresEarlierRequests() {
        gate.setEnabled(true);gate.observedSend("bypass",LocalDateTime.now().minusDays(1));
        verifyNoInteractions(mapper);gate.observedSend("bypass",LocalDateTime.now().plusSeconds(1));
        verify(mapper).upsertOwned(eq(1L),anyString(),eq("bypass"),eq(false));
    }
    @Test void meterFailureCannotPermitModelCall() {
        gate.setEnabled(true);when(mapper.upsertOwned(anyLong(),anyString(),anyString(),anyBoolean())).thenReturn(0);
        assertThatThrownBy(()->gate.beforeSend("TEXT")).hasMessageContaining("已停止");
    }
    @Test void routerComposesAndRecordsWithoutCallingCandidateOrHealthTracker() {
        gate.setEnabled(true);var client=mock(LlmClient.class);var health=mock(LlmHealthTracker.class);
        var router=new LlmRouter(List.of(client),config,health);ReflectionTestUtils.setField(router,"testGate",gate);
        var trace=PromptTrace.of(PromptSurface.REVIEW);
        assertThat(router.available(9)).isTrue();
        assertThat(router.invoke(9,trace,"system","user")).isEmpty();
        verifyNoInteractions(client,health);verify(recorder).save(eq(9L),eq(trace),eq("system"),eq("user"),anyString(),eq("BLOCKED"),anyString());
    }
    @Test void directTextClientIsProtectedEvenWithoutRouter() {
        gate.setEnabled(true);var client=new DashScopeLlmClient(config,new RestTemplateBuilder());
        ReflectionTestUtils.setField(client,"testGate",gate);
        assertThatThrownBy(()->client.chat(null,"rules","data")).isInstanceOf(LlmTestGate.Blocked.class);
    }
    @Test void transportSeesZeroRequestsWhileProtectedAndOnlyLocalMockWhenOff() {
        var client=new DashScopeLlmClient(config,new RestTemplateBuilder());ReflectionTestUtils.setField(client,"testGate",gate);
        var wire=org.springframework.test.web.client.MockRestServiceServer.createServer(client.restTemplate);
        var invocation=new LlmInvocation("dashscope","qwen","local-test-model");
        when(config.getString(1L,LlmCatalog.DASHSCOPE.keyName(),"")).thenReturn("local-test-key");
        gate.setEnabled(true);
        assertThatThrownBy(()->client.chat(invocation,"rules","data")).isInstanceOf(LlmTestGate.Blocked.class);
        wire.verify();
        gate.setEnabled(false);
        wire.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything()).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"local-only\"},\"finish_reason\":\"stop\"}]}",org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(client.chat(invocation,"rules","data")).isEqualTo("local-only");wire.verify();
    }
    @Test void blockedVisionDoesNotProduceEmptyRecognizedHoldings() {
        gate.setEnabled(true);var vision=new VisionLlmClient(config,new RestTemplateBuilder());ReflectionTestUtils.setField(vision,"testGate",gate);
        assertThat(vision.available()).isTrue();
        assertThatThrownBy(()->vision.extract(new byte[]{1,2,3},"image/png")).isInstanceOf(LlmTestGate.Blocked.class);
        verify(recorder).save(eq(1L),any(),anyString(),contains("图片未发送"),anyString(),eq("BLOCKED"),anyString());
    }
    @Test void localAgentStopsBeforeToolsOrHttpAndKeepsPromptCallback() {
        gate.setEnabled(true);var registry=mock(AskToolRegistry.class);var dispatcher=mock(AskToolDispatcher.class);var router=mock(LlmRouter.class);
        var runtime=new LocalToolLoopRuntime(config,registry,dispatcher,router);ReflectionTestUtils.setField(runtime,"testGate",gate);
        var callback=mock(AgentRuntime.PromptSent.class);var sink=mock(AskSink.class);
        runtime.run(turn(callback),sink);verify(sink).failed(LlmTestGate.MESSAGE);
        verify(callback).sent(contains("system"),eq("question"),eq(LlmTestGate.MESSAGE),eq(false));
        verifyNoInteractions(registry,dispatcher,router);
    }
    @Test void managedAgentAndConnectionProbeDoNotCreateRemoteSession() {
        gate.setEnabled(true);var registry=mock(AskToolRegistry.class);var audit=mock(AskAuditMapper.class);
        var runtime=new ManagedAgentRuntime(config,registry,audit);ReflectionTestUtils.setField(runtime,"testGate",gate);
        var callback=mock(AgentRuntime.PromptSent.class);var sink=mock(AskSink.class);
        runtime.run(turn(callback),sink);verify(sink).failed(LlmTestGate.MESSAGE);
        verify(callback).sent(isNull(),contains("question"),eq(LlmTestGate.MESSAGE),eq(true));
        assertThat(runtime.testMcpLink()).isEqualTo(LlmTestGate.MESSAGE);verifyNoInteractions(registry,audit);
    }
    private AgentRuntime.AskTurn turn(AgentRuntime.PromptSent callback) {
        return new AgentRuntime.AskTurn(9,10,null,"system",List.of(),"question",AskScope.DETAIL,
                ref->{},"analysis",note->{},callback);
    }
}
