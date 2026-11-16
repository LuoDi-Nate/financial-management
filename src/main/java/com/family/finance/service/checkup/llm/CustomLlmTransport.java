package com.family.finance.service.checkup.llm;

import okhttp3.*;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.family.finance.service.checkup.llm.CustomLlmFailure.Kind.*;

@Component
public class CustomLlmTransport {
    private final CustomEndpointPolicy policy;
    private final LlmTargetResolver targets;
    private final LlmTestGate gate;
    private final ObjectMapper json;
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "custom-llm-dns"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());

    public CustomLlmTransport(CustomEndpointPolicy policy, LlmTargetResolver targets, LlmTestGate gate, ObjectMapper json) {
        this.policy = policy; this.targets = targets; this.gate = gate; this.json = json;
    }
    @PreDestroy public void close() { dns.shutdownNow(); }
    protected List<InetAddress> lookup(String host) throws Exception { return Arrays.asList(InetAddress.getAllByName(host)); }

    public Result open(ResolvedLlmTarget target, Object body, boolean stream) {
        return open(target, body, stream, () -> { });
    }
    public Result open(ResolvedLlmTarget target, Object body, boolean stream, Runnable beforeWire) {
        gate.beforeSend(stream ? "CUSTOM_STREAM" : "CUSTOM_JSON");
        targets.assertCurrent(target);
        String host = target.endpoint().getHost();
        List<InetAddress> allowed;
        Future<List<InetAddress>> lookup = null;
        try {
            lookup = dns.submit(() -> lookup(host));
            allowed = policy.validateAnswers(lookup.get(5, TimeUnit.SECONDS), target.allowLocalHttp());
        } catch (CustomLlmFailure e) { throw e; }
        catch (Exception e) { if (lookup != null) lookup.cancel(true); throw new CustomLlmFailure(DNS); }
        var client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(name -> {
            if (!name.equalsIgnoreCase(host)) throw new java.net.UnknownHostException("Unexpected target");
            return allowed;
        }).protocols(List.of(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(target.timeout(), TimeUnit.SECONDS).writeTimeout(target.timeout(), TimeUnit.SECONDS)
            .callTimeout(target.timeout(), TimeUnit.SECONDS).connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)).build();
        Call call;
        try {
            Request.Builder request = new Request.Builder().url(target.endpoint().toString())
                .header("Accept-Encoding", "identity").header("Accept", stream ? "text/event-stream" : "application/json")
                .post(RequestBody.create(json.writeValueAsBytes(body), MediaType.get("application/json")));
            if (!target.apiKey().isBlank()) request.header("Authorization", "Bearer " + target.apiKey());
            gate.beforeSend(stream ? "CUSTOM_STREAM" : "CUSTOM_JSON");
            targets.assertCurrent(target);
            call = client.newCall(request.build());
            beforeWire.run();
            LocalDateTime dispatched = LocalDateTime.now();
            Response response;
            try { response = call.execute(); }
            finally { gate.observedSend(stream ? "CUSTOM_STREAM" : "CUSTOM_JSON", dispatched); }
            if (!response.isSuccessful()) {
                int status = response.code();
                response.close();
                throw new CustomLlmFailure(switch (status) {
                    case 401 -> AUTH; case 402, 403 -> PERMISSION; case 404 -> MODEL;
                    case 429, 503 -> RATE_LIMIT; default -> PROTOCOL;
                });
            }
            String contentType = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
            if (response.body() == null || !(stream ? contentType.contains("text/event-stream") : contentType.contains("application/json"))) {
                response.close(); throw new CustomLlmFailure(PROTOCOL);
            }
            return new Result(response, call, client, stream ? 4 * 1024 * 1024 : 2 * 1024 * 1024);
        } catch (LlmTestGate.Blocked | CustomLlmFailure e) { client.connectionPool().evictAll(); throw e; }
        catch (Exception e) { client.connectionPool().evictAll(); throw safe(e); }
    }

    public java.util.Map<String, Object> json(ResolvedLlmTarget target, Object body) {
        try (Result result = open(target, body, false)) {
            return json.readValue(result.input(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        } catch (CustomLlmFailure | LlmTestGate.Blocked e) { throw e; }
        catch (Exception e) { throw safe(e); }
    }

    public static final class Result implements AutoCloseable {
        private final Response response;
        private final Call call;
        private final OkHttpClient client;
        private final InputStream input;
        Result(Response response, Call call, OkHttpClient client, int limit) {
            this.response = response; this.call = call; this.client = client;
            input = new FilterInputStream(response.body().byteStream()) {
                int remaining = limit;
                private int count(int n) { if (n > 0 && (remaining -= n) < 0) throw new CustomLlmFailure(RESPONSE_TOO_LARGE); return n; }
                @Override public int read() {
                    try { int n = in.read(); if (n >= 0) count(1); return n; }
                    catch (java.io.IOException e) { throw safe(e); }
                }
                @Override public int read(byte[] b, int off, int len) {
                    try { return count(in.read(b, off, Math.min(len, Math.max(1, remaining + 1)))); }
                    catch (java.io.IOException e) { throw safe(e); }
                }
            };
        }
        public InputStream input() { return input; }
        public BufferedReader reader() { return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)); }
        public void cancel() { call.cancel(); }
        @Override public void close() { response.close(); client.connectionPool().evictAll(); }
    }
    private static CustomLlmFailure safe(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CustomLlmFailure custom) return custom;
            if (t instanceof javax.net.ssl.SSLException) return new CustomLlmFailure(TLS);
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.io.InterruptedIOException) return new CustomLlmFailure(TIMEOUT);
            if (t instanceof java.net.ConnectException) return new CustomLlmFailure(CONNECT);
        }
        return new CustomLlmFailure(PROTOCOL);
    }
}
