package com.family.finance.service.ask.runtime;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.service.ask.*;
import com.family.finance.service.checkup.llm.*;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalToolLoopSiliconflowTest {
    private final ObjectMapper json=new ObjectMapper();
    private FamilyConfigService config() {
        var cfg=mock(FamilyConfigService.class);
        var values=Map.of("llm_platform","siliconflow","llm_family","deepseek","llm_model_id","Pro/Org/ToolModel",
                "llm_backup_platform","deepseek","llm_backup_family","deepseek","llm_backup_model_id","deepseek-chat");
        when(cfg.getString(anyLong(),anyString(),anyString())).thenAnswer(a->values.getOrDefault(a.getArgument(1),a.<String>getArgument(1).endsWith("api_key")?"synthetic-"+a.getArgument(1):a.getArgument(2)));
        when(cfg.getBoolean(anyLong(),anyString(),anyBoolean())).thenAnswer(a->a.getArgument(2));
        when(cfg.getInt(anyLong(),anyString(),anyInt())).thenReturn(5);return cfg;
    }
    private LlmClient client(String platform) {var c=mock(LlmClient.class);when(c.platform()).thenReturn(platform);when(c.available()).thenReturn(true);return c;}
    private AgentRuntime.AskTurn turn() {return new AgentRuntime.AskTurn(1,1,null,"synthetic-system",List.of(),"synthetic-user",AskScope.DETAIL,s->{});}
    private String event(Object delta,String finish) throws Exception {
        var choice=new LinkedHashMap<String,Object>();choice.put("delta",delta);if(finish!=null)choice.put("finish_reason",finish);
        return "data: "+json.writeValueAsString(Map.of("choices",List.of(choice)))+"\n\n";
    }
    @Test void fullToolRoundtripKeepsCompleteModelCredentialAndToolCallId() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();var headers=new ArrayList<String>();
        server.createContext("/siliconflow",exchange->{
            try {
                requests.add(json.readTree(exchange.getRequestBody()));headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String response;
                if(requests.size()==1) {
                    response=event(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-one","type","function","function",Map.of("name","facts","arguments","{\"mode\":")))),null)
                        +event(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","\"all\"}")))),"tool_calls")+"data: [DONE]\n\n";
                } else response=event(Map.of("content","synthetic-answer"),"stop")+"data: [DONE]\n\n";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            } catch(Exception e){throw new RuntimeException(e);}
        });
        server.start();
        try {
            var cfg=config();var router=new LlmRouter(List.of(client("siliconflow")),cfg);var registry=mock(AskToolRegistry.class);var dispatcher=mock(AskToolDispatcher.class);
            when(registry.openAiToolList()).thenReturn(List.of(Map.of("type","function","function",Map.of("name","facts","parameters",Map.of("type","object")))));
            when(registry.find("facts")).thenReturn(Optional.empty());
            when(dispatcher.call(eq(1L),eq("facts"),eq(Map.of("mode","all")),eq(AskScope.DETAIL))).thenReturn(AskToolResult.of("facts").put("synthetic",true).summary("合成结果").build());
            var runtime=new LocalToolLoopRuntime(cfg,registry,dispatcher,router){@Override protected String endpoint(LlmCatalog.Platform p){return "http://127.0.0.1:"+server.getAddress().getPort()+"/"+p.code();}};
            var sink=mock(AskSink.class);runtime.run(turn(),sink);
            verify(sink).textDelta("synthetic-answer");verify(sink).done();verify(sink,never()).failed(anyString());
            verify(dispatcher).call(eq(1L),eq("facts"),eq(Map.of("mode","all")),eq(AskScope.DETAIL));
            assertThat(requests).hasSize(2);assertThat(headers).containsOnly("Bearer synthetic-llm_siliconflow_api_key");
            assertThat(requests).allSatisfy(r->{assertThat(r.path("model").asText()).isEqualTo("Pro/Org/ToolModel");assertThat(r.path("stream").asBoolean()).isTrue();});
            var messages=requests.get(1).path("messages");assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call-one");
            assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call-one");assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
        } finally {server.stop(0);}
    }
    @Test void partialSiliconflowOutputDoesNotReplayOnBackup() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var backup=new AtomicInteger();
        server.createContext("/siliconflow",exchange->{byte[] bytes="data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/deepseek",exchange->{backup.incrementAndGet();exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        try {
            var cfg=config();var registry=mock(AskToolRegistry.class);when(registry.openAiToolList()).thenReturn(List.of());
            var runtime=new LocalToolLoopRuntime(cfg,registry,mock(AskToolDispatcher.class),new LlmRouter(List.of(client("siliconflow"),client("deepseek")),cfg)){
                @Override protected String endpoint(LlmCatalog.Platform p){return "http://127.0.0.1:"+server.getAddress().getPort()+"/"+p.code();}};
            var sink=mock(AskSink.class);runtime.run(turn(),sink);verify(sink).textDelta("partial");verify(sink).failed(anyString());assertThat(backup).hasValue(0);
        } finally {server.stop(0);}
    }
    @Test void serverProtectionBlocksLocalLoopBeforeAnyCandidate() {
        var cfg=config();var router=mock(LlmRouter.class);var runtime=new LocalToolLoopRuntime(cfg,mock(AskToolRegistry.class),mock(AskToolDispatcher.class),router);
        var gate=mock(LlmTestGate.class);when(gate.block("ASK_TURN_LOCAL")).thenReturn(true);ReflectionTestUtils.setField(runtime,"testGate",gate);
        var sink=mock(AskSink.class);runtime.run(turn(),sink);verify(sink).failed(LlmTestGate.MESSAGE);verifyNoInteractions(router);
    }
}
