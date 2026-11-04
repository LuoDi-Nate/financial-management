package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.expense.imports.MerchantAiClassifier;
import com.family.finance.service.llmtrace.PromptSurface;
import com.family.finance.service.llmtrace.PromptTrace;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmAvailabilityV139Test {
    private FamilyConfigService config(Map<String, String> values) {
        var config = mock(FamilyConfigService.class);
        when(config.getString(anyLong(), anyString(), any())).thenAnswer(a -> values.getOrDefault(a.getArgument(1), a.getArgument(2)));
        when(config.getBoolean(anyLong(), anyString(), anyBoolean())).thenAnswer(a -> a.getArgument(2));
        when(config.getInt(anyLong(), anyString(), anyInt())).thenAnswer(a -> Integer.parseInt(values.getOrDefault(a.getArgument(1), "" + a.getArgument(2))));
        return config;
    }

    private LlmClient client(String platform, boolean available, String output) {
        var client = mock(LlmClient.class);
        when(client.platform()).thenReturn(platform);
        when(client.available()).thenReturn(available);
        when(client.chat(any(), anyString(), anyString())).thenReturn(output);
        return client;
    }

    @Test void classificationUsesBackupAndOnlySendsMerchantNames() {
        var cfg = config(new HashMap<>());
        var primary = client("dashscope", false, "unused");
        var backup = client("deepseek", true, "[{\"merchant\":\"测试店\",\"category\":\"餐饮\"},{\"merchant\":\"模型编造店\",\"category\":\"餐饮\"}]");
        var classifier = new MerchantAiClassifier(new LlmRouter(List.of(primary, backup), cfg));
        assertThat(classifier.available()).isTrue();
        assertThat(classifier.classify(List.of("测试店"), List.of("餐饮"))).containsExactlyEntriesOf(Map.of("测试店", "餐饮"));
        verify(primary, never()).chat(any(), any(), any());
        verify(backup).chat(any(), contains("看不到金额"), contains("测试店"));
    }

    @Test void configurationFailureAndAttemptFailureAreDifferentAndSafe() {
        var cfg = config(new HashMap<>());
        var down = client("dashscope", false, null);
        var router = new LlmRouter(List.of(down), cfg);
        var missing = PromptTrace.of(PromptSurface.GOAL_REPORT);
        assertThat(router.invoke(1, missing, "s", "u")).isEmpty();
        assertThat(missing.failureMessage()).contains("密钥");
        var failed = client("dashscope", true, null);
        when(failed.chat(any(), anyString(), anyString())).thenThrow(new IllegalStateException("secret-test-value"));
        var attempt = PromptTrace.of(PromptSurface.GOAL_REPORT);
        assertThat(new LlmRouter(List.of(failed), cfg).invoke(1, attempt, "s", "u")).isEmpty();
        assertThat(attempt.failureMessage()).contains("调用失败").doesNotContain("secret-test-value");
    }

    @Test void refusalIsReportedEvenWithoutPromptRecorder() {
        var router = new LlmRouter(List.of(client("dashscope", true, "bad")), config(new HashMap<>()));
        var trace = PromptTrace.of(PromptSurface.DIAGNOSE_FAMILY);
        Object result = router.invoke(1, trace, "s", "u", (inv, raw, ms) -> null);
        assertThat(result).isNull();
        assertThat(trace.failureMessage()).contains("未通过校验");
    }

    @Test void externalCallsSharePlatformCircuitAndSuccessResetsIt() {
        var cfg = config(new HashMap<>(Map.of(FamilyConfigService.K_LLM_DEEPSEEK_KEY, "test-key")));
        var c = new DeepSeekLlmClient(cfg, new RestTemplateBuilder());
        assertThat(c.available()).isTrue();
        c.recordExternalFailure(); c.recordExternalFailure(); c.recordExternalFailure();
        assertThat(c.available()).isFalse();
        c.recordExternalSuccess();
        assertThat(c.available()).isTrue();
    }

    @Test void eachRequestUsesCurrentTimeoutWithoutMutatingSharedClient() throws Exception {
        var values = new HashMap<>(Map.of(FamilyConfigService.K_LLM_TIMEOUT_SECS, "7"));
        var c = new DeepSeekLlmClient(config(values), new RestTemplateBuilder());
        var request = c.restTemplate.getRequestFactory().createRequest(URI.create("http://127.0.0.1:9/"), org.springframework.http.HttpMethod.POST);
        var field = org.springframework.util.ReflectionUtils.findField(request.getClass(), "connection");
        org.springframework.util.ReflectionUtils.makeAccessible(field);
        HttpURLConnection connection = (HttpURLConnection) field.get(request);
        assertThat(connection.getReadTimeout()).isEqualTo(7000);
        values.put(FamilyConfigService.K_LLM_TIMEOUT_SECS, "11");
        var next = c.restTemplate.getRequestFactory().createRequest(URI.create("http://127.0.0.1:9/"), org.springframework.http.HttpMethod.POST);
        assertThat(((HttpURLConnection) field.get(next)).getReadTimeout()).isEqualTo(11000);
        assertThat(connection.getReadTimeout()).isEqualTo(7000);
    }

    @Test void diagnosisKeepsActualUnavailableReason() {
        assertThat(LlmDiagnoseService.DiagnoseResult.unavailable("尚未配置密钥").text()).contains("尚未配置密钥");
    }
}
