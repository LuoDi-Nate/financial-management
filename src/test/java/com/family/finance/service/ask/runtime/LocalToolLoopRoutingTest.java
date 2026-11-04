package com.family.finance.service.ask.runtime;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.service.ask.AskToolDispatcher;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.checkup.llm.*;
import com.family.finance.service.config.FamilyConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalToolLoopRoutingTest {
    private LlmClient client(String platform, boolean available) {
        var c = mock(LlmClient.class);
        when(c.platform()).thenReturn(platform);
        when(c.available()).thenReturn(available);
        return c;
    }

    private void run(boolean primaryAvailable, boolean partial, AtomicInteger primaryCalls, AtomicInteger backupCalls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dashscope", exchange -> {
            primaryCalls.incrementAndGet();
            byte[] body = (partial ? "data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n" : "error").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(partial ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/deepseek", exchange -> {
            backupCalls.incrementAndGet();
            byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"备选回答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var cfg = mock(FamilyConfigService.class);
            when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(a -> a.<String>getArgument(1).endsWith("api_key") ? "test-key" : a.getArgument(2));
            when(cfg.getBoolean(anyLong(), anyString(), anyBoolean())).thenAnswer(a -> a.getArgument(2));
            when(cfg.getInt(anyLong(), anyString(), anyInt())).thenReturn(5);
            var router = new LlmRouter(List.of(client("dashscope", primaryAvailable), client("deepseek", true)), cfg);
            var registry = mock(AskToolRegistry.class);
            when(registry.openAiToolList()).thenReturn(List.of());
            var runtime = new LocalToolLoopRuntime(cfg, registry, mock(AskToolDispatcher.class), router) {
                @Override protected String endpoint(LlmCatalog.Platform p) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + p.code(); }
            };
            var sink = mock(AskSink.class);
            runtime.run(new AgentRuntime.AskTurn(1, 1, null, "system", List.of(), "test", AskScope.DETAIL, s -> {}), sink);
            if (partial) {
                verify(sink).textDelta("半截"); verify(sink).failed(anyString()); verify(sink, never()).done();
            } else {
                verify(sink).textDelta("备选回答"); verify(sink).done(); verify(sink, never()).failed(anyString());
            }
        } finally { server.stop(0); }
    }

    @Test void initialFailureUsesBackup() throws Exception {
        var p = new AtomicInteger(); var b = new AtomicInteger(); run(true, false, p, b);
        assertThat(p.get()).isEqualTo(1); assertThat(b.get()).isEqualTo(1);
    }
    @Test void backupOnlyIsUsable() throws Exception {
        var p = new AtomicInteger(); var b = new AtomicInteger(); run(false, false, p, b);
        assertThat(p.get()).isZero(); assertThat(b.get()).isEqualTo(1);
    }
    @Test void partialStreamDoesNotReplayOnBackup() throws Exception {
        var p = new AtomicInteger(); var b = new AtomicInteger(); run(true, true, p, b);
        assertThat(p.get()).isEqualTo(1); assertThat(b.get()).isZero();
    }
}
