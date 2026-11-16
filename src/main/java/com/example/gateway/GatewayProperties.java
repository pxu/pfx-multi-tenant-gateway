package com.example.gateway;

import com.example.gateway.resilience.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Gateway settings ({@code gateway.*} in application.yml).
 *
 * @param rbac              users, roles and sessions (role-based access control; guards {@code /admin/**})
 * @param auditQueueCapacity async audit buffer; when full, entries are dropped and counted
 * @param retry             retries of idempotent requests after a connect failure or timeout
 * @param tenantMaxConcurrentRequests default bulkhead per tenant: requests in flight at once (a tenant may set its own)
 * @param ipRateLimit       per client address, before authentication ({@code requests-per-minute: 0} disables it)
 * @param trustedProxies    load balancers in front of the gateway (IPs or CIDRs) whose X-Forwarded-For is believed
 * @param continueClientTraces join a valid W3C {@code traceparent} sent by the client (true), or always
 *                          start a new trace at the gateway (an edge that does not trust callers' trace ids)
 * @param rateLimitFailOpen when the rate limiter fails (or its breaker is open): serve without limits (true) or reject with 503
 * @param revocationFailOpen when a token's revocation state cannot be read (config database down, never cached here):
 *                          serve the token (true) or reject the request with 503 {@code CONFIG_UNAVAILABLE} (false)
 * @param configRefreshInterval how often each instance checks the config database for changes written via other instances
 * @param tenantCacheTtl    how long a tenant loaded on demand is kept in memory (= how long a change made
 *                          through another instance takes to apply here)
 * @param upstreamTimeout     how long to wait for an upstream response before answering 504
 * @param maxRequestBody      larger request bodies are rejected with 413 (chunked ones while streaming)
 * @param allowLoopbackUpstreams whether routes may point at loopback addresses (local/test only)
 * @param proxy               upstream connection pool
 * @param circuitBreaker      settings for the per-upstream breakers
 * @param rateLimitCircuitBreaker settings for the breaker around the rate limiter
 * @param configDbCircuitBreaker  settings for the breaker around the config database reads on the request
 *                          path (tenants and token revocations loaded on demand)
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        RbacSettings rbac,
        int auditQueueCapacity,
        Retry retry,
        int tenantMaxConcurrentRequests,
        IpRateLimit ipRateLimit,
        List<String> trustedProxies,
        boolean continueClientTraces,
        boolean rateLimitFailOpen,
        boolean revocationFailOpen,
        Duration configRefreshInterval,
        Duration tenantCacheTtl,
        Duration upstreamTimeout,
        DataSize maxRequestBody,
        boolean allowLoopbackUpstreams,
        Proxy proxy,
        CircuitBreaker.Settings circuitBreaker,
        CircuitBreaker.Settings rateLimitCircuitBreaker,
        CircuitBreaker.Settings configDbCircuitBreaker,
        Jwt jwt) {

    /**
     * @param maxAttempts   attempts in total, the first included (1 = no retries)
     * @param baseBackoff   the first retry waits a random time up to this; doubling per retry (full jitter)
     * @param maxBackoff    cap on that wait
     * @param budgetRatio   retries allowed per request, over time (0.1 = at most ~10% extra load)
     * @param budgetReserve retries that may go out before any traffic has earned them (and the cap)
     */
    public record Retry(int maxAttempts, Duration baseBackoff, Duration maxBackoff, double budgetRatio, int budgetReserve) {}

    /** @param burst how many requests one address may send at once */
    public record IpRateLimit(int requestsPerMinute, int burst) {}

    /**
     * @param sessionTtl how long a session (one login, one role) lasts
     * @param roles      role → permissions ({@code tenants:read}, {@code routes:write}, ...)
     * @param users      operators of the management API: user → key hash and the roles they may log in as
     *                   (tenant users and their roles are part of each tenant's config)
     */
    public record RbacSettings(Duration sessionTtl, Map<String, List<String>> roles, Map<String, Operator> users) {}

    /**
     * @param keySha256 hex SHA-256 of the operator's key; blank = cannot log in
     * @param roles     the roles this operator may log in as (one per session)
     */
    public record Operator(String keySha256, List<String> roles) {}

    /** Upstream connection pool. See the comments in {@code UpstreamProxy} for why each value matters. */
    public record Proxy(
            Duration connectTimeout,
            Duration poolLeaseTimeout,
            int maxTotalConnections,
            int maxConnectionsPerRoute,
            Duration idleTimeout,
            Duration validateAfterInactivity) {}

    /**
     * @param privateKeyPem PKCS#8 RSA key; if blank an ephemeral key is generated (tokens die on restart)
     */
    public record Jwt(String issuer, String audience, Duration defaultTtl, Duration maxTtl, String privateKeyPem) {}
}
