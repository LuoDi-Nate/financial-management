package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CustomEndpointPolicyTest {
    private final CustomEndpointPolicy policy = new CustomEndpointPolicy();
    @ParameterizedTest @ValueSource(strings = {"https://gateway.example.com", "https://gateway.example.com/prefix/v1/", "https://gateway.example.com:8443/api/v2"})
    void prefixesAndPortsArePreservedWithoutLookup(String value) {
        assertThat(policy.normalize(value, false)).isEqualTo(value.replaceAll("/+$", ""));
    }
    @ParameterizedTest @ValueSource(strings = {"http://localhost/v1", "https://127.0.0.1/v1", "https://10.1.2.3/v1", "https://[::1]/v1", "https://[fd12::1]/v1"})
    void localTargetsRequireExplicitPermission(String value) {
        assertThatThrownBy(() -> policy.normalize(value, false)).isInstanceOf(CustomLlmFailure.class);
        assertThat(policy.normalize(value, true)).isNotBlank();
    }
    @ParameterizedTest @ValueSource(strings = {"file:///v1", "https://user:password@gateway.example.com/v1", "https://gateway.example.com/v1?key=secret",
        "https://gateway.example.com/#fragment", "https://gateway.example.com/v1/chat/completions", "https://gateway.example.com/responses",
        "https://gateway.example.com/a/../v1", "https://gateway.example.com/%2e%2e/v1", "https://gateway.example.com/a%2fb", "https://gateway.example.com/a%5cb",
        "https://gateway.example.com/a%0ab", "https://gateway.example.com:0/v1", "https://gateway.example.com:65536/v1",
        "https://2130706433/v1", "https://0177.0.0.1/v1", "https://0x7f000001/v1", "https://127.1/v1",
        "https://[fe80::1%25eth0]/v1", "https://metadata.google.internal./v1", "https://child.metadata.google.internal/v1"})
    void ambiguousOrCredentialBearingUrlsFailEvenWithPermission(String value) {
        assertThatThrownBy(() -> policy.normalize(value, true)).isInstanceOf(CustomLlmFailure.class);
    }
    @ParameterizedTest @ValueSource(strings = {"0.0.0.0", "100.100.100.200", "169.254.169.254", "168.63.129.16", "192.0.2.1", "198.18.0.1",
        "224.0.0.1", "255.255.255.255", "::", "fe80::1", "ff02::1", "fd00:ec2::254", "2001:db8::1", "2002:7f00:1::1", "64:ff9b::7f00:1"})
    void reservedAddressesNeverBecomeAllowed(String address) throws Exception {
        InetAddress ip = InetAddress.getByName(address);
        assertThatThrownBy(() -> policy.validateAddress(ip, true)).isInstanceOf(CustomLlmFailure.class);
    }
    @Test void mixedDnsAnswersFailAsAWholeAndMappedIpv4CannotBypass() throws Exception {
        InetAddress global = InetAddress.getByAddress(new byte[]{8,8,8,8});
        InetAddress privateIp = InetAddress.getByAddress(new byte[]{10,0,0,1});
        assertThat(policy.validateAnswers(List.of(global), false)).containsExactly(global);
        assertThatThrownBy(() -> policy.validateAnswers(List.of(global, privateIp), false)).isInstanceOf(CustomLlmFailure.class);
        assertThat(policy.validateAnswers(List.of(privateIp), true)).containsExactly(privateIp);
        byte[] mapped = new byte[16]; mapped[10] = mapped[11] = (byte)255; mapped[12] = 127; mapped[15] = 1;
        InetAddress v6 = java.net.Inet6Address.getByAddress(null, mapped, -1);
        assertThatThrownBy(() -> policy.validateAddress(v6, false)).isInstanceOf(CustomLlmFailure.class);
    }
}
