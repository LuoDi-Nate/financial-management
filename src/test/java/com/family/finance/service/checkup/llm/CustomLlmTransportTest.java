package com.family.finance.service.checkup.llm;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.config.*;
import com.family.finance.service.holdingimport.VisionLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomLlmTransportTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String,String> values = new HashMap<>();
    final AtomicInteger requests = new AtomicInteger(), resolutions = new AtomicInteger();
    final List<Map<String,Object>> received = new ArrayList<>();
    HttpServer server;
    LlmTestGate gate;
    FamilyConfigService config;
    LlmTargetResolver targets;
    CustomLlmTransport transport;
    CustomLlmClient client;
    volatile int status = 200;
    volatile String type = "application/json", response = "{\"choices\":[{\"message\":{\"content\":\"synthetic-ok\"}}]}";
    volatile String authorization, path, host;
    java.util.function.IntFunction<String> fixtureReply;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            path = exchange.getRequestURI().getRawPath(); host = exchange.getRequestHeaders().getFirst("Host");
            received.add(json.readValue(exchange.getRequestBody(),Map.class));
            exchange.getResponseHeaders().set("Content-Type",type);
            exchange.getResponseHeaders().set("Location","http://elsewhere.invalid/never");
            byte[] body = (fixtureReply == null ? response : fixtureReply.apply(requests.get())).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);
            try(var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        values.putAll(Map.of("llm_platform","custom","llm_family","custom","llm_model_id","Org/Case:version",
            "llm_vision_platform","custom","llm_vision_family","custom-vision","llm_vision_model_id","Org/Vision","llm_vision_enabled","true"));
        service("BEARER",true);
        config = mock(FamilyConfigService.class);
        when(config.llmSnapshot(anyLong())).thenAnswer(a -> new HashMap<>(values));
        when(config.getAll(anyLong())).thenAnswer(a -> new HashMap<>(values));
        when(config.getString(anyLong(),anyString(),anyString())).thenAnswer(a -> values.getOrDefault(a.getArgument(1),a.getArgument(2)));
        when(config.getBoolean(anyLong(),anyString(),anyBoolean())).thenAnswer(a -> Boolean.parseBoolean(values.getOrDefault(a.getArgument(1),a.getArgument(2).toString())));
        var custom = new CustomLlmConfigService(mock(FamilyRuntimeConfigMapper.class),config,new CustomEndpointPolicy(),json,"");
        targets = new LlmTargetResolver(config,custom); gate = mock(LlmTestGate.class);
        transport = new CustomLlmTransport(new CustomEndpointPolicy(),targets,gate,json) {
            @Override protected List<InetAddress> lookup(String name) throws Exception {
                resolutions.incrementAndGet(); return List.of(InetAddress.getByAddress(new byte[]{127,0,0,1}));
            }
        };
        client = new CustomLlmClient(targets,transport);
    }
    void service(String mode, boolean local) throws Exception {
        values.put(CustomLlmConfigService.KEY,json.writeValueAsString(Map.of("version",1,"revision",UUID.randomUUID().toString(),
            "editToken",UUID.randomUUID().toString(),"baseUrl","http://fixture.invalid:" + server.getAddress().getPort() + "/prefix/v1",
            "displayName","合成网关","authMode",mode,"apiKey","synthetic-secret-marker","allowLocalHttp",local)));
    }
    LlmInvocation text() { var e=targets.load(1); return targets.prepare(1,e.settings.primary(),e); }
    @AfterEach void close() { transport.close(); server.stop(0); }

    @Test void actualTransportPinsDnsPreservesPrefixHeaderAndCompleteModel() {
        assertThat(client.chat(text(),"synthetic-system","synthetic-user")).isEqualTo("synthetic-ok");
        assertThat(path).isEqualTo("/prefix/v1/chat/completions"); assertThat(host).startsWith("fixture.invalid:");
        assertThat(authorization).isEqualTo("Bearer synthetic-secret-marker"); assertThat(resolutions.get()).isEqualTo(1);
        assertThat(received.getFirst()).containsEntry("model","Org/Case:version");
        assertThat(received.toString()).doesNotContain("synthetic-secret-marker");
    }
    @Test void explicitNoAuthSendsNoHeaderAndLiteralAutoRemainsAModel() throws Exception {
        service("NONE",true); values.put("llm_model_id","auto");
        client.chat(text(),"sys","user"); assertThat(authorization).isNull(); assertThat(received.getFirst()).containsEntry("model","auto");
    }
    @ParameterizedTest @ValueSource(ints = {301,302,303,307,308,401,403,404,429,503})
    void failuresNeverFollowRedirectOrRetryOrExposeResponse(int code) {
        status=code; response="synthetic-secret-marker internal-address";
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(CustomLlmFailure.class)
            .hasMessageNotContaining("synthetic-secret-marker").hasMessageNotContaining("internal-address");
        assertThat(requests.get()).isEqualTo(1); assertThat(resolutions.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"error\":{\"message\":\"synthetic-secret-marker\"}}","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":null}}]}","{\"choices\":[{\"message\":{\"content\":[]}}]}","<html>not a model</html>"})
    void malformedSuccessIsNeverAValidAnswer(String body) {
        response=body;
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(CustomLlmFailure.class).hasMessageNotContaining("synthetic-secret-marker");
    }
    @Test void contentTypeAndBodySizeAreBounded() {
        type="text/html";
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(CustomLlmFailure.class);
        type="application/json"; response="{\"choices\":[{\"message\":{\"content\":\""+"a".repeat(2*1024*1024)+"\"}}]}";
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(CustomLlmFailure.class).hasMessageContaining("过大");
    }
    @Test void protectionHappensBeforeDnsAndBeforeAnySocketAttempt() {
        doThrow(new LlmTestGate.Blocked()).when(gate).beforeSend(anyString());
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(LlmTestGate.Blocked.class);
        assertThat(resolutions.get()).isZero(); assertThat(requests.get()).isZero();
        verify(gate,never()).observedSend(anyString(),any()); assertThat(client.available(text())).isTrue();
    }
    @Test void changedSnapshotNeverSendsOldCredentialToNewAddress() throws Exception {
        var old=text(); service("NONE",true);
        assertThatThrownBy(() -> client.chat(old,"sys","user")).isInstanceOf(CustomLlmFailure.class).hasMessageContaining("已更新");
        assertThat(requests.get()).isZero(); assertThat(resolutions.get()).isZero();
    }
    @Test void privateDnsAnswersRequirePermissionAtWireTime() throws Exception {
        // HTTPS syntax can be saved without resolving it. Runtime policy still checks the actual addresses.
        var s=json.readTree(values.get(CustomLlmConfigService.KEY));
        ((com.fasterxml.jackson.databind.node.ObjectNode)s).put("baseUrl","https://fixture.invalid/v1").put("allowLocalHttp",false);
        values.put(CustomLlmConfigService.KEY,json.writeValueAsString(s));
        assertThatThrownBy(() -> client.chat(text(),"sys","user")).isInstanceOf(CustomLlmFailure.class).hasMessageContaining("访问规则");
        assertThat(requests.get()).isZero(); verify(gate,never()).observedSend(anyString(),any());
    }
    @Test void visionUsesIndependentModelAndTheSameGuardedTransport() {
        response="{\"choices\":[{\"message\":{\"content\":\"[{\\\"name\\\":\\\"合成持仓\\\",\\\"marketValue\\\":\\\"100\\\"}]\"}}]}";
        var vision=new VisionLlmClient(config,new RestTemplateBuilder());
        ReflectionTestUtils.setField(vision,"targets",targets); ReflectionTestUtils.setField(vision,"customTransport",transport); ReflectionTestUtils.setField(vision,"testGate",gate);
        assertThat(vision.extract(new byte[]{1,2,3},"image/png")).hasSize(1);
        assertThat(received.getFirst()).containsEntry("model","Org/Vision");
        assertThat(received.getFirst().toString()).contains("data:image/png;base64,AQID");
    }
    @Test void routerUsesSavedBackupAndPreparedSourceMetadata() {
        status=401;
        values.putAll(Map.of("llm_backup_platform","deepseek","llm_backup_family","deepseek","llm_backup_model_id","deepseek-chat","llm_deepseek_api_key","synthetic-backup"));
        LlmClient backup=new LlmClient() {
            public String platform(){return "deepseek";} public boolean available(){return true;}
            public String chat(LlmInvocation inv,String sys,String user){ assertThat(inv.target()).isNotNull(); return "backup-ok"; }
        };
        var router=new LlmRouter(List.of(client,backup),config);
        ReflectionTestUtils.setField(router,"targets",targets); ReflectionTestUtils.setField(router,"testGate",gate);
        var trace=com.family.finance.service.llmtrace.PromptTrace.of(com.family.finance.service.llmtrace.PromptSurface.GOAL_REPORT);
        assertThat(router.invoke(1,trace,"sys","user").orElseThrow().text()).isEqualTo("backup-ok");
        assertThat(trace.sourceJson()).contains("deepseek-chat").doesNotContain("synthetic-backup","fixture.invalid");
    }

    String event(Map<String,Object> delta,String finish) {
        try {
            var choice=new LinkedHashMap<String,Object>(); choice.put("delta",delta);
            if(finish!=null) choice.put("finish_reason",finish);
            return "data: "+json.writeValueAsString(Map.of("choices",List.of(choice)))+"\n\n";
        } catch(Exception e) { throw new AssertionError(e); }
    }
    void toolLoop(boolean changeConfiguration) throws Exception {
        type="text/event-stream";
        fixtureReply = n -> n == 1 ? event(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-one","type","function",
            "function",Map.of("name","facts","arguments","{\"mode\":")))),null)
            + event(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","\"all\"}")))),"tool_calls")+"data: [DONE]\n\n"
            : event(Map.of("content","synthetic-answer"),"stop")+"data: [DONE]\n\n";
        var router=new LlmRouter(List.of(client),config); ReflectionTestUtils.setField(router,"targets",targets); ReflectionTestUtils.setField(router,"testGate",gate);
        var registry=mock(com.family.finance.service.ask.AskToolRegistry.class);
        var dispatcher=mock(com.family.finance.service.ask.AskToolDispatcher.class);
        when(registry.openAiToolList()).thenReturn(List.of(Map.of("type","function","function",Map.of("name","facts","parameters",Map.of("type","object")))));
        when(dispatcher.call(eq(1L),eq("facts"),eq(Map.of("mode","all")),any())).thenAnswer(a -> {
            if(changeConfiguration) service("NONE",true);
            return com.family.finance.service.ask.AskToolResult.of("facts").put("synthetic",true).summary("合成结果").build();
        });
        var runtime=new com.family.finance.service.ask.runtime.LocalToolLoopRuntime(config,registry,dispatcher,router);
        ReflectionTestUtils.setField(runtime,"targets",targets); ReflectionTestUtils.setField(runtime,"customTransport",transport); ReflectionTestUtils.setField(runtime,"testGate",gate);
        var sink=mock(com.family.finance.service.ask.runtime.AskSink.class);
        runtime.run(new com.family.finance.service.ask.runtime.AgentRuntime.AskTurn(1,1,null,"synthetic-system",List.of(),"synthetic-user",com.family.finance.domain.ask.AskScope.DETAIL,s -> {}),sink);
        verify(dispatcher).call(eq(1L),eq("facts"),eq(Map.of("mode","all")),any());
        if(changeConfiguration) {
            verify(sink).failed(contains("配置已更新")); assertThat(requests.get()).isEqualTo(1); verify(sink,never()).done();
        } else {
            verify(sink).textDelta("synthetic-answer"); verify(sink).done(); verify(sink,never()).failed(anyString());
            assertThat(requests.get()).isEqualTo(2);
            var second=json.valueToTree(received.get(1));
            assertThat(second.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("call-one");
            assertThat(second.at("/messages/3/tool_call_id").asText()).isEqualTo("call-one");
            assertThat(received).allSatisfy(r -> assertThat(r).containsEntry("model","Org/Case:version"));
        }
    }
    @Test void fullCustomToolLoopPreservesModelAndToolCallIds() throws Exception { toolLoop(false); }
    @Test void customConfigurationChangeStopsSecondToolRound() throws Exception { toolLoop(true); }
}
