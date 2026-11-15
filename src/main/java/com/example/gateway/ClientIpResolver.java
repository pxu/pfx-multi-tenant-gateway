package com.example.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The client's IP address, for per-IP rate limiting, the audit log and the {@code X-Forwarded-For}
 * sent upstream.
 *
 * <p>By default it is the TCP peer: a header the client wrote is not evidence of anything. Only when the
 * peer is one of {@code gateway.trusted-proxies} (a load balancer in front of the gateway) is its
 * {@code X-Forwarded-For} used: walking it from the right, every hop appended by a trusted proxy is
 * skipped and the first untrusted one is the client. Hops further left were written by the client and
 * are ignored, so a forged {@code X-Forwarded-For: 10.0.0.1} achieves nothing.
 *
 * <p>Only IP literals are accepted (never resolved through DNS); a malformed hop ends the walk at the
 * last address a trusted proxy vouched for.
 */
public final class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** An address range: {@code 10.0.0.0/8}, or a single address. */
    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trusted;

    /** @throws IllegalArgumentException on an entry that is not an IP or CIDR (fails startup) */
    public ClientIpResolver(List<String> trustedProxies) {
        this.trusted = new ArrayList<>();
        for (String entry : trustedProxies == null ? List.<String>of() : trustedProxies) {
            String[] parts = entry.trim().split("/", 2);
            byte[] network = literal(parts[0]);
            if (network == null) {
                throw new IllegalArgumentException("trusted proxy is not an IP or CIDR: " + entry);
            }
            int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (bits < 0 || bits > network.length * 8) {
                throw new IllegalArgumentException("invalid prefix length: " + entry);
            }
            trusted.add(new Cidr(network, bits));
        }
    }

    /**
     * @param peer           the TCP peer ({@code request.getRemoteAddr()})
     * @param forwardedFor   every {@code X-Forwarded-For} header value, in order
     */
    public String resolve(String peer, List<String> forwardedFor) {
        if (!isTrusted(peer)) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String value : forwardedFor) {
            for (String hop : value.split(",")) {
                if (!hop.isBlank()) {
                    hops.add(hop.trim());
                }
            }
        }
        String client = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (literal(hop) == null) {
                return client; // malformed: stop at the last address a trusted proxy vouched for
            }
            client = hop;
            if (!isTrusted(hop)) {
                return hop; // the first hop not appended by one of our proxies
            }
        }
        return client; // every hop was a trusted proxy: the leftmost is as far as we can see
    }

    private boolean isTrusted(String address) {
        byte[] bytes = literal(address);
        return bytes != null && trusted.stream().anyMatch(c -> c.contains(bytes));
    }

    /** "999.1.1.1" matches the pattern but is not a literal; the JDK would resolve it as a hostname. */
    private static boolean octetsInRange(String ipv4) {
        for (String octet : ipv4.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /** The address bytes of an IPv4 or IPv6 literal, or null. Never a DNS lookup. */
    private static byte[] literal(String text) {
        if (text == null || text.isEmpty() || text.length() > 45) {
            return null;
        }
        boolean ipv6 = text.indexOf(':') >= 0;
        if (!ipv6 && !(IPV4.matcher(text).matches() && octetsInRange(text))) {
            return null; // anything else could be a hostname, and resolving it would be a DNS call
        }
        try {
            return InetAddress.getByName(text).getAddress(); // a literal: parsed, not resolved
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
