package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    private final ClientIpResolver none = new ClientIpResolver(List.of());
    private final ClientIpResolver behindLb = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.1.7"));

    @Test
    void testResolve_NoTrustedProxies_PeerEvenIfHeaderSaysOtherwise() {
        // a client writing its own X-Forwarded-For achieves nothing
        assertThat(none.resolve("203.0.113.7", List.of("10.0.0.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void testResolve_PeerIsNotATrustedProxy_HeaderIgnored() {
        assertThat(behindLb.resolve("203.0.113.7", List.of("198.51.100.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void testResolve_BehindTrustedProxy_FirstUntrustedHopFromTheRight() {
        // client 203.0.113.7 -> LB 10.1.2.3 -> gateway; the client also forged a leading hop
        assertThat(behindLb.resolve("10.1.2.3", List.of("6.6.6.6, 203.0.113.7"))).isEqualTo("203.0.113.7");
        // two trusted hops (CIDR and single address), split over two headers
        assertThat(behindLb.resolve("10.9.9.9", List.of("203.0.113.7", "192.168.1.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void testResolve_MalformedOrHostnameHop_StopsAtLastVouchedAddress() {
        assertThat(behindLb.resolve("10.1.2.3", List.of("evil.example.com"))).isEqualTo("10.1.2.3"); // never resolved via DNS
        assertThat(behindLb.resolve("10.1.2.3", List.of("999.1.1.1"))).isEqualTo("10.1.2.3");
        assertThat(behindLb.resolve("10.1.2.3", List.of())).isEqualTo("10.1.2.3");
    }

    @Test
    void testResolve_Ipv6() {
        ClientIpResolver v6 = new ClientIpResolver(List.of("fd00::/8"));

        assertThat(v6.resolve("fd00::1", List.of("2001:db8::42"))).isEqualTo("2001:db8::42");
        assertThat(v6.resolve("2001:db8::9", List.of("2001:db8::42"))).isEqualTo("2001:db8::9");
    }

    @Test
    void testConstructor_InvalidEntry_FailsStartup() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("lb.internal"))).hasMessageContaining("not an IP or CIDR");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33"))).hasMessageContaining("prefix");
    }
}
