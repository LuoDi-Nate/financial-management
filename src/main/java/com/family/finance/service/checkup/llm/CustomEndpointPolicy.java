package com.family.finance.service.checkup.llm;

import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

@Component
public class CustomEndpointPolicy {
    private static final List<String> DENIED_HOSTS = List.of("metadata.google.internal", "metadata.goog", "instance-data.ec2.internal");
    private static final String[] V4_DENY = {"0.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "192.0.0.0/24",
        "192.0.2.0/24", "192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "168.63.129.16/32"};
    private static final String[] V6_DENY = {"2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20", "2620:4f:8000::/48", "fd00:ec2::254/128"};

    /** Syntax only: this method must never look up a hostname. */
    public String normalize(String raw, boolean allowLocalHttp) {
        try {
            if (raw == null || raw.length() > 2048) throw denied();
            String value = raw.trim();
            if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c == 127) || value.contains("\\")) throw denied();
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || allowLocalHttp && scheme.equals("http")) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() == null) throw denied();
            HttpUrl parsed = HttpUrl.parse(value);
            if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw denied();
            String authority = uri.getRawAuthority();
            if (authority.contains("%") || authority.contains("@") || authority.endsWith(":")) throw denied();
            String host = parsed.host().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isBlank() || deniedHost(host)) throw denied();
            if (!allowLocalHttp && (host.equals("localhost") || host.endsWith(".localhost"))) throw denied();
            if (!host.contains(":")) {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
                if (host.matches("[0-9.]+") && !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw denied();
                String originalHost = uri.getHost();
                if (originalHost != null && !originalHost.replaceFirst("\\.$", "").equalsIgnoreCase(host)) throw denied();
                if (host.startsWith("0x") || host.matches(".*\\.0x[0-9a-f]+.*")) throw denied();
            }
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            String lower = rawPath.toLowerCase(Locale.ROOT);
            if (lower.matches(".*%(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f).*")) throw denied();
            for (String part : uri.getPath().split("/")) if (part.equals(".") || part.equals("..")) throw denied();
            String path = rawPath.replaceAll("/+$", "");
            String decodedPath = uri.getPath().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
            if (decodedPath.endsWith("/chat/completions") || decodedPath.endsWith("/responses")) throw denied();
            if (!parsed.encodedPath().replaceAll("/+$", "").equals(path)) throw denied();
            HttpUrl normalized = parsed.newBuilder().host(host).encodedPath(path.isEmpty() ? "/" : path).build();
            String result = normalized.toString().replaceAll("/+$", "");
            if (literal(host)) validateAddress(InetAddress.getByName(host), allowLocalHttp);
            return result;
        } catch (CustomLlmFailure e) { throw e; }
        catch (Exception e) { throw denied(); }
    }

    public List<InetAddress> validateAnswers(List<InetAddress> addresses, boolean allowLocalHttp) {
        if (addresses == null || addresses.isEmpty()) throw new CustomLlmFailure(CustomLlmFailure.Kind.DNS);
        addresses.forEach(a -> validateAddress(a, allowLocalHttp));
        return List.copyOf(addresses);
    }

    public void validateAddress(InetAddress address, boolean allowLocalHttp) {
        byte[] b = address.getAddress();
        if (b.length == 16 && mapped(b)) {
            try { address = InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16)); b = address.getAddress(); }
            catch (Exception e) { throw denied(); }
        }
        for (String block : b.length == 4 ? V4_DENY : V6_DENY) if (inCidr(b, block)) throw denied();
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) throw denied();
        boolean local = address.isLoopbackAddress() || address.isSiteLocalAddress() || b.length == 16 && (b[0] & 0xfe) == 0xfc;
        if (local && !allowLocalHttp) throw denied();
        if (b.length == 16 && !local && (b[0] & 0xe0) != 0x20) throw denied();
    }

    private static boolean literal(String host) { return host.contains(":") || host.matches("[0-9.]+"); }
    private static boolean deniedHost(String host) { return DENIED_HOSTS.stream().anyMatch(x -> host.equals(x) || host.endsWith("." + x)); }
    private static boolean mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return b[10] == (byte) 255 && b[11] == (byte) 255;
    }
    private static boolean inCidr(byte[] address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) return false;
            int bits = Integer.parseInt(parts[1]);
            for (int i = 0; i < address.length && bits > 0; i++, bits -= 8) {
                int mask = (0xff << (8 - Math.min(8, bits))) & 0xff;
                if ((address[i] & mask) != (network[i] & mask)) return false;
            }
            return true;
        } catch (Exception e) { throw new IllegalStateException("地址策略配置错误"); }
    }
    private static CustomLlmFailure denied() { return new CustomLlmFailure(CustomLlmFailure.Kind.URL_POLICY); }
}
