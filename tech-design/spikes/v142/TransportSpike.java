import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

/** Design experiment only: a local TLS fixture, never an application or model client. */
public class TransportSpike {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger DNS = new AtomicInteger();
    static final List<Seen> SEEN = new ArrayList<>();
    static final String MODEL = "Org/Model:version";
    static int checks;
    record Seen(String path, String host, String sni, String auth, String body) {}
    record Reply(int status, String body) {}

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    static Reply post(SSLContext tls, int port, String host, String path,
                      String auth, Map<String, Object> body) throws Exception {
        DnsResolver pinned = new DnsResolver() {
            public InetAddress[] resolve(String name) throws UnknownHostException {
                if (!name.equals(host)) throw new UnknownHostException("unexpected host");
                DNS.incrementAndGet();
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
            }
            public String resolveCanonicalHostname(String name) { return name; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
            .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create().setSslContext(tls).build())
            .build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().build()) {
            var request = new HttpPost("https://" + host + ":" + port + path);
            if (auth != null) request.setHeader("Authorization", auth);
            request.setEntity(new StringEntity(JSON.writeValueAsString(body), ContentType.APPLICATION_JSON));
            return client.execute(request, response -> new Reply(response.getCode(),
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)));
        }
    }

    public static void main(String[] args) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(args[0]))) { store.load(in, "fixture-only".toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "fixture-only".toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        var clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var session = (javax.net.ssl.ExtendedSSLSession)
                ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession();
            String sni = session.getRequestedServerNames().stream().filter(javax.net.ssl.SNIHostName.class::isInstance)
                .map(name -> ((javax.net.ssl.SNIHostName) name).getAsciiName()).findFirst().orElse("");
            SEEN.add(new Seen(path, exchange.getRequestHeaders().getFirst("Host"), sni,
                exchange.getRequestHeaders().getFirst("Authorization"), input));
            int status = 200;
            String output = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
            String type = "application/json";
            if (path.startsWith("/redirect")) {
                status = Integer.parseInt(path.substring("/redirect".length()));
                exchange.getResponseHeaders().set("Location", "https://elsewhere.invalid/stolen");
                output = "{}";
            } else if (path.equals("/busy")) { status = 503; output = "{\"error\":{\"code\":\"busy\"}}"; }
            else if (path.equals("/stream")) {
                type = "text/event-stream";
                output = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n";
            }
            byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var text = Map.<String, Object>of("model", MODEL, "messages",
                List.of(Map.of("role", "user", "content", "synthetic ping")));
            var reply = post(clientTls, port, "gateway.invalid", "/prefix/v1/chat/completions", "Bearer fixture-token", text);
            check(reply.status == 200, "pinned address with valid hostname TLS");
            check(SEEN.getLast().path.equals("/prefix/v1/chat/completions"), "prefix retained");
            check(SEEN.getLast().host.equals("gateway.invalid:" + port), "original Host and port retained");
            check(SEEN.getLast().sni.equals("gateway.invalid"), "original TLS SNI retained");
            check(SEEN.getLast().auth.equals("Bearer fixture-token"), "explicit bearer header");
            check(JSON.readTree(SEEN.getLast().body).path("model").asText().equals(MODEL), "full model ID retained");
            check(JSON.readTree(reply.body).at("/choices/0/message/content").asText().equals("ok"), "JSON text shape");
            post(clientTls, port, "gateway.invalid", "/chat/completions", null, text);
            check(SEEN.getLast().auth == null, "no-auth sends no Authorization header");
            for (int redirect : new int[]{301, 302, 303, 307, 308}) {
                int count = SEEN.size(), resolutions = DNS.get();
                reply = post(clientTls, port, "gateway.invalid", "/redirect" + redirect, "Bearer fixture-token", text);
                check(reply.status == redirect && SEEN.size() == count + 1 && DNS.get() == resolutions + 1,
                    "redirect " + redirect + " never followed or resolved");
            }
            int count = SEEN.size();
            reply = post(clientTls, port, "gateway.invalid", "/busy", null, text);
            check(reply.status == 503 && SEEN.size() == count + 1, "no automatic retry on 503");
            boolean mismatch = false;
            try { post(clientTls, port, "wrong.invalid", "/chat/completions", "Bearer fixture-token", text); }
            catch (javax.net.ssl.SSLException expected) { mismatch = true; }
            check(mismatch && SEEN.size() == count + 1, "hostname mismatch fails before HTTP credentials");
            boolean untrusted = false;
            try { post(SSLContext.getDefault(), port, "gateway.invalid", "/chat/completions", "Bearer fixture-token", text); }
            catch (javax.net.ssl.SSLException expected) { untrusted = true; }
            check(untrusted && SEEN.size() == count + 1, "untrusted certificate fails before HTTP credentials");
            var image = Map.<String, Object>of("model", MODEL, "messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AA=="))))));
            post(clientTls, port, "gateway.invalid", "/chat/completions", null, image);
            check(JSON.readTree(SEEN.getLast().body).at("/messages/0/content/0/image_url/url").asText()
                .equals("data:image/png;base64,AA=="), "synthetic image JSON retained, no model invoked");
            reply = post(clientTls, port, "gateway.invalid", "/stream", null, Map.of("model", MODEL, "stream", true,
                "messages", text.get("messages"), "tools", List.of(Map.of("type", "function", "function", Map.of("name", "fixture")))));
            check(reply.body.contains("data: [DONE]"), "SSE bytes retained");
            check(JSON.readTree(SEEN.getLast().body).path("stream").asBoolean(), "stream request retained");
            check(JSON.readTree(SEEN.getLast().body).at("/tools/0/function/name").asText().equals("fixture"), "tool JSON retained");
            System.out.println("RESULT " + checks + " PASS; loopback fixture only; business model sends=0");
        } finally { server.stop(0); }
    }
}
