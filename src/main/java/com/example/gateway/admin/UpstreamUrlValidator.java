package com.example.gateway.admin;

import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Checks upstream URLs that operators configure through the admin API.
 *
 * <p>Tenants cannot set upstreams; this protects against an operator mistake (or a stolen admin key)
 * pointing a route at something dangerous (SSRF): chiefly the cloud metadata service
 * (169.254.169.254, a link-local address) and, in production, loopback services on the gateway host
 * itself. Private network ranges stay allowed because internal services normally live there.
 *
 * <p>Known gap: DNS is checked when the route is saved, not on every request (DNS rebinding).
 */
public class UpstreamUrlValidator {

    private final boolean allowLoopback;

    public UpstreamUrlValidator(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** The URL syntax (http/https, host, no credentials) is already checked by the Route's annotations. */
    public void validate(String url) {
        String host = URI.create(url).getHost();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw invalid("upstream host '" + host + "' does not resolve");
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw invalid("upstream '" + host + "' resolves to a link-local or wildcard address");
            }
            if (address.isLoopbackAddress() && !allowLoopback) {
                throw invalid("upstream '" + host + "' resolves to loopback, which is not allowed");
            }
        }
    }

    private static GatewayException invalid(String detail) {
        return new GatewayException(ErrorCode.VALIDATION_ERROR, detail);
    }
}
