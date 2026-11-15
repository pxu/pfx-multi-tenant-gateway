package com.example.gateway;

import com.example.gateway.admin.UpstreamUrlValidator;
import com.example.gateway.audit.AsyncAuditSink;
import com.example.gateway.audit.AuditFilter;
import com.example.gateway.audit.AuditSink;
import com.example.gateway.auth.AuthFilter;
import com.example.gateway.auth.PermissionInterceptor;
import com.example.gateway.auth.SessionService;
import com.example.gateway.auth.TokenService;
import com.example.gateway.policy.CircuitBreakingRateLimiter;
import com.example.gateway.policy.ConcurrencyLimiter;
import com.example.gateway.policy.InMemoryRateLimiter;
import com.example.gateway.policy.IpRateLimitFilter;
import com.example.gateway.policy.PolicyEngine;
import com.example.gateway.policy.RateLimiter;
import com.example.gateway.proxy.UpstreamProxy;
import com.example.gateway.resilience.CircuitBreaker;
import com.example.gateway.store.ConfigRepository;
import com.example.gateway.store.JdbcConfigRepository;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.RbacAuthorizer;
import com.example.gateway.rbac.RoleAssignments;
import com.example.gateway.store.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wiring. The interesting classes are plain Java (no Spring annotations), built here, so their unit
 * tests need no Spring at all.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The configuration's source of truth: plain JDBC with portable SQL. Which database is only
     * {@code spring.datasource.url} plus {@code schema-<platform>.sql} (H2 by default).
     */
    @Bean
    ConfigRepository configRepository(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        return new JdbcConfigRepository(jdbc, tx, json);
    }

    /** Signs tenant tokens and RBAC sessions (told apart by audience). */
    @Bean
    RSAKey signingKey(GatewayProperties props) {
        String pem = props.jwt().privateKeyPem();
        if (pem == null || pem.isBlank()) {
            LoggerFactory.getLogger(GatewayConfig.class).warn("no gateway.jwt.private-key-pem: using an ephemeral key");
            return TokenService.generateKey("gateway-key");
        }
        return TokenService.loadKey(pem, "gateway-key");
    }

    @Bean
    TokenService tokenService(RSAKey signingKey, GatewayProperties props, Clock clock) {
        GatewayProperties.Jwt jwt = props.jwt();
        if (SessionService.AUDIENCE.equals(jwt.audience())) {
            throw new IllegalStateException("gateway.jwt.audience must differ from " + SessionService.AUDIENCE);
        }
        return new TokenService(signingKey, jwt.issuer(), jwt.audience(), jwt.defaultTtl(), jwt.maxTtl(), clock);
    }

    /**
     * The RBAC module (policy decision point), in-process. Built-in roles and operators' assignments come
     * from config; custom roles and tenant users' assignments from the config store's in-memory snapshot
     * (written through /admin/rbac), so no decision does I/O.
     */
    @Bean
    Authorizer authorizer(GatewayProperties props, ConfigStore store) {
        Map<String, GatewayProperties.Operator> operators = props.rbac().users();
        RoleAssignments assignments = (tenant, user) -> tenant == null
                ? Optional.ofNullable(operators.get(user)).map(GatewayProperties.Operator::roles).orElse(List.of())
                : store.rolesOf(tenant, user);
        Authorizer authorizer = new RbacAuthorizer(props.rbac().roles(), store::customRoles, assignments);
        operators.forEach((user, op) -> op.roles().stream().filter(r -> !authorizer.isBuiltIn(r)).findFirst()
                .ifPresent(r -> {
                    throw new IllegalStateException("operator '" + user + "' has role '" + r + "', which is not built in");
                }));
        return authorizer;
    }

    @Bean
    SessionService sessions(RSAKey signingKey, GatewayProperties props, Authorizer authorizer, Clock clock) {
        Map<String, String> keys = new HashMap<>();
        props.rbac().users().forEach((user, op) -> keys.put(user, op.keySha256()));
        return new SessionService(signingKey, props.jwt().issuer(), keys, authorizer, props.rbac().sessionTtl(), clock);
    }

    /**
     * The one place that picks the implementation: token buckets in memory (one instance). A shared-store
     * implementation for several instances is plugged in here; the circuit breaker and the
     * fail-open/closed policy around it stay the same.
     */
    @Bean
    RateLimiter rateLimiter(GatewayProperties props, Clock clock, MeterRegistry meters) {
        RateLimiter store = new InMemoryRateLimiter(clock);
        CircuitBreaker breaker = new CircuitBreaker("rate-limiter", props.rateLimitCircuitBreaker(), clock).bindTo(meters);
        return new CircuitBreakingRateLimiter(store, breaker, props.rateLimitFailOpen());
    }

    /** Bulkheads: requests in flight per tenant. */
    @Bean
    ConcurrencyLimiter concurrencyLimiter() {
        return new ConcurrencyLimiter();
    }

    @Bean
    PolicyEngine policyEngine(RateLimiter rateLimiter, Authorizer authorizer) {
        return new PolicyEngine(rateLimiter, authorizer);
    }

    @Bean(destroyMethod = "close")
    UpstreamProxy upstreamProxy(GatewayProperties props, MeterRegistry meters) {
        UpstreamProxy proxy = new UpstreamProxy(props.proxy(), props.upstreamTimeout(), props.maxRequestBody().toBytes());
        // httpcomponents_httpclient_pool_*: leased / available / pending connections (saturation)
        new PoolingHttpClientConnectionManagerMetricsBinder(proxy.pool(), "upstream").bindTo(meters);
        // gateway_upstream_connections_opened_total: churn (compare its rate with the request rate)
        FunctionCounter.builder("gateway.upstream.connections.opened", proxy, UpstreamProxy::connectionsOpened)
                .description("New TCP connections opened to upstreams (connection churn)")
                .register(meters);
        return proxy;
    }

    @Bean
    UpstreamUrlValidator upstreamUrlValidator(GatewayProperties props) {
        return new UpstreamUrlValidator(props.allowLoopbackUpstreams());
    }

    @Bean(destroyMethod = "close")
    AsyncAuditSink auditSink(ObjectMapper json, GatewayProperties props, MeterRegistry meters) {
        return new AsyncAuditSink(json, props.auditQueueCapacity(), meters);
    }

    /** Filter order = middleware order: audit (outermost) -> per-IP limit -> auth -> controllers. */
    @Bean
    FilterRegistrationBean<AuditFilter> auditFilter(AuditSink sink, MeterRegistry meters, GatewayProperties props) {
        ClientIpResolver clientIps = new ClientIpResolver(props.trustedProxies());
        FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>(new AuditFilter(sink, meters, clientIps, props.continueClientTraces()));
        registration.setOrder(1);
        return registration;
    }

    /** Per client address, before authentication: floods of junk tokens never reach the signature check. */
    @Bean
    FilterRegistrationBean<IpRateLimitFilter> ipRateLimitFilter(RateLimiter rateLimiter, GatewayProperties props) {
        GatewayProperties.IpRateLimit ip = props.ipRateLimit();
        FilterRegistrationBean<IpRateLimitFilter> registration =
                new FilterRegistrationBean<>(new IpRateLimitFilter(rateLimiter, ip.requestsPerMinute(), ip.burst()));
        registration.setOrder(2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(TokenService tokens, SessionService sessions, ConfigStore store,
            MeterRegistry meters) {
        FunctionCounter.builder("gateway.auth.revocation.unavailable", store, ConfigStore::revocationUnavailable)
                .description("Token revocation checks that could not reach the database (token served: fail open)")
                .register(meters);
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(
                new AuthFilter(tokens, sessions, store::isRevoked));
        registration.setOrder(3);
        return registration;
    }

    /** Permission checks where sessions are used (the management API), once the handler is known. Login needs none. */
    @Bean
    WebMvcConfigurer permissionChecks(Authorizer authorizer) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PermissionInterceptor(authorizer))
                        .addPathPatterns("/admin", "/admin/**")
                        .excludePathPatterns(AuthFilter.LOGIN_PATH);
            }
        };
    }
}
