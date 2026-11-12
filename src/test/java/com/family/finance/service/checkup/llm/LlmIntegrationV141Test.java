package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.holdingimport.VisionLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmIntegrationV141Test {
    static Stream<LlmCatalog.Platform> platforms() { return LlmCatalog.PLATFORMS.stream(); }
    private FamilyConfigService config(Map<String,String> values) {
        var cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), anyString())).thenAnswer(a -> {
            String key=a.getArgument(1);return values.getOrDefault(key,key.endsWith("api_key")?"synthetic-"+key:a.getArgument(2));
        });
        when(cfg.getInt(anyLong(), anyString(), anyInt())).thenAnswer(a -> a.getArgument(2));
        when(cfg.getDouble(anyLong(), anyString(), anyDouble())).thenAnswer(a -> a.getArgument(2));
        when(cfg.getBoolean(anyLong(), anyString(), anyBoolean())).thenAnswer(a -> Boolean.parseBoolean(values.getOrDefault(a.getArgument(1),a.getArgument(2).toString())));
        return cfg;
    }
    private AbstractOpenAiCompatibleClient client(LlmCatalog.Platform p, FamilyConfigService cfg) {
        return switch(p.code()) {
            case "dashscope" -> new DashScopeLlmClient(cfg,new RestTemplateBuilder());
            case "deepseek" -> new DeepSeekLlmClient(cfg,new RestTemplateBuilder());
            case "ark" -> new ArkLlmClient(cfg,new RestTemplateBuilder());
            case "siliconflow" -> new SiliconFlowLlmClient(cfg,new RestTemplateBuilder());
            default -> throw new AssertionError(p.code());
        };
    }
    @ParameterizedTest @MethodSource("platforms")
    void textRequestKeepsPlatformKeyEndpointAndCompleteId(LlmCatalog.Platform p) {
        var c=client(p,config(Map.of()));var wire=MockRestServiceServer.createServer(c.restTemplate);
        String model="Org/Case-Sensitive.Model";
        wire.expect(requestTo(p.chatEndpoint())).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer synthetic-"+p.keyName()))
            .andExpect(jsonPath("$.model").value(model)).andExpect(jsonPath("$.messages[0].content").value("synthetic-system"))
            .andExpect(jsonPath("$.messages[1].content").value("synthetic-user"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"wire-ok\"},\"finish_reason\":\"stop\"}]}",MediaType.APPLICATION_JSON));
        assertThat(c.chat(new LlmInvocation(p.code(),p.firstFamily(LlmCatalog.Modality.TEXT).orElseThrow().code(),model),"synthetic-system","synthetic-user")).isEqualTo("wire-ok");wire.verify();
    }
    @ParameterizedTest @MethodSource("platforms")
    void testProtectionStopsEachTextClientWithoutTransportOrBreakerFailure(LlmCatalog.Platform p) {
        var c=client(p,config(Map.of()));var wire=MockRestServiceServer.createServer(c.restTemplate);
        var gate=mock(LlmTestGate.class);doThrow(new LlmTestGate.Blocked()).when(gate).beforeSend(anyString());
        ReflectionTestUtils.setField(c,"testGate",gate);
        assertThatThrownBy(()->c.chat(new LlmInvocation(p.code(),p.families().getFirst().code(),"model"),"sys","user")).isInstanceOf(LlmTestGate.Blocked.class);
        assertThat(c.available()).isTrue();wire.verify();verify(gate,never()).observedSend(anyString(),any());
    }
    @Test void siliconflowVisionUsesIndependentModelAndImagePayload() {
        var cfg=config(Map.of("llm_vision_platform","siliconflow","llm_vision_family","kimi","llm_vision_model_id","Org/VisionCase","llm_vision_enabled","true"));
        var c=new VisionLlmClient(cfg,new RestTemplateBuilder());var rt=(RestTemplate)ReflectionTestUtils.getField(c,"rt");
        var wire=MockRestServiceServer.createServer(rt);
        wire.expect(requestTo(LlmCatalog.SILICONFLOW.chatEndpoint())).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer synthetic-llm_siliconflow_api_key"))
            .andExpect(jsonPath("$.model").value("Org/VisionCase"))
            .andExpect(jsonPath("$.messages[1].content[1].image_url.url").value("data:image/png;base64,AQID"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"[{\\\"name\\\":\\\"合成持仓\\\",\\\"marketValue\\\":\\\"100\\\"}]\"}}]}",MediaType.APPLICATION_JSON));
        assertThat(c.extract(new byte[]{1,2,3},"image/png")).hasSize(1);wire.verify();
    }
    @Test void failingSiliconflowPrimaryUsesOriginalBackup() {
        var cfg=config(Map.of("llm_platform","siliconflow","llm_family","deepseek","llm_model_id","Org/Text",
            "llm_backup_platform","deepseek","llm_backup_family","deepseek","llm_backup_model_id","deepseek-chat"));
        var a=new SiliconFlowLlmClient(cfg,new RestTemplateBuilder());var b=new DeepSeekLlmClient(cfg,new RestTemplateBuilder());
        var aw=MockRestServiceServer.createServer(a.restTemplate);var bw=MockRestServiceServer.createServer(b.restTemplate);
        aw.expect(requestTo(LlmCatalog.SILICONFLOW.chatEndpoint())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        bw.expect(requestTo(LlmCatalog.DEEPSEEK.chatEndpoint())).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"fallback\"}}]}",MediaType.APPLICATION_JSON));
        assertThat(new LlmRouter(List.of(a,b),cfg).invoke(1L,"sys","user",new LlmRouter.Handler<String>() {
            public String onOutput(LlmInvocation inv,String raw,long ms) { return raw; }
        })).isEqualTo("fallback");aw.verify();bw.verify();
    }
    @Test void blankAndMalformedSuccessResponsesAreFailures() {
        for(String response:List.of("{}","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":\"\"}}]}","not-json")) {
            var c=new SiliconFlowLlmClient(config(Map.of()),new RestTemplateBuilder());var wire=MockRestServiceServer.createServer(c.restTemplate);
            wire.expect(anything()).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->c.chat(new LlmInvocation("siliconflow","deepseek","Org/Model"),"sys","user")).isInstanceOf(RuntimeException.class);wire.verify();
        }
    }
    @Test void probeExcludesDisabledVisionAndPinsAutoWithoutRotation() {
        var settings=new LlmSettings(new LlmInvocation("dashscope","qwen",null),Optional.empty(),new LlmInvocation("siliconflow","kimi","Unselected/Vision"),false,false);
        var sf=LlmProbeTarget.resolve(settings,LlmCatalog.SILICONFLOW).orElseThrow();
        assertThat(sf.source()).isEqualTo("参考文字型号");assertThat(sf.invocation().resolvedModel()).isNotEqualTo("Unselected/Vision");
        var dash=LlmProbeTarget.resolve(settings,LlmCatalog.DASHSCOPE).orElseThrow();assertThat(dash.invocation().auto()).isFalse();
        assertThat(new DashScopeLlmClient(config(Map.of()),new RestTemplateBuilder()).attemptModels(dash.invocation())).hasSize(1);
    }
    @Test void automaticStrategyKeepsBailianRotationDistinct() {
        assertThat(new LlmInvocation("dashscope","qwen",null).sameStrategy(new LlmInvocation("dashscope","qwen","qwen-plus"))).isFalse();
        assertThat(new LlmInvocation("siliconflow","deepseek",null).sameStrategy(new LlmInvocation("siliconflow","deepseek",LlmCatalog.SILICONFLOW.families().getFirst().defaultModel()))).isTrue();
    }
    @Test void completeModelBoundariesAndInvalidFormats() {
        for(String value:List.of("x","x".repeat(65),"x".repeat(256),"Pro/deepseek-ai/DeepSeek-R1")) assertThat(LlmCatalog.validModel(value)).isTrue();
        for(String value:List.of("","x".repeat(257),"https://a/model","a//b","/a","a/","a b","a\"b","a\nb")) assertThat(LlmCatalog.validModel(value)).isFalse();
        assertThat(LlmCatalog.normalizeModel("  Pro/CaseSensitive  ")).isEqualTo("Pro/CaseSensitive");
    }
    @ParameterizedTest @MethodSource("platforms")
    void safeErrorsHaveNoRawSecretsAndPlatformAdviceDoesNotLeak(LlmCatalog.Platform p) {
        for(int status:List.of(401,402,403,404,429,503)) {
            String message=LlmFailure.describe(p.code(),status,"synthetic-SECRET model not found");
            assertThat(message).doesNotContain("synthetic-SECRET");
            if(!p.code().equals("ark")) assertThat(message).doesNotContain("方舟","豆包");
        }
        assertThat(LlmFailure.describe(p.code(),402,"insufficient balance")).contains("余额").doesNotContain("免费");
    }
}
