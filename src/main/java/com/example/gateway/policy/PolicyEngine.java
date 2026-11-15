package com.example.gateway.policy;

import com.example.gateway.Paths;
import com.example.gateway.auth.TokenService.Principal;
import com.example.gateway.error.ErrorCode;
import com.example.gateway.error.GatewayException;
import com.example.gateway.rbac.Authorizer;
import com.example.gateway.rbac.Permission;
import com.example.gateway.rbac.Subject;
import com.example.gateway.store.Route;
import com.example.gateway.store.Tenant;
import java.util.Locale;
import java.util.Map;

/**
 * Per-tenant policy, checked before anything is sent upstream. Any violation throws, so a rejected
 * request cannot reach the proxy code. Order matters: cheap checks first, and the quota check last
 * so that a request rejected for its path, scope or permission does not use up quota.
 *
 * <p>Two layers of authorization: the token's scopes say <i>which services</i> it may reach; RBAC
 * (the {@link Authorizer}) says <i>which actions</i> the token's role may take there. This class is
 * the enforcement point; the decision belongs to the RBAC module.
 *
 * <p>Rate limits are multi-dimensional: per client address (before authentication, in
 * {@link IpRateLimitFilter}), then per user, per tenant and per route here, all on one {@link RateLimiter}
 * with prefixed keys ({@code ip:}, {@code user:}, {@code tenant:}, {@code route:}). Limiting by address alone would punish an
 * office behind one IP; by user alone it would miss many accounts working together.
 */
public class PolicyEngine {

    private final RateLimiter rateLimiter;
    private final Authorizer authorizer;

    public PolicyEngine(RateLimiter rateLimiter, Authorizer authorizer) {
        this.rateLimiter = rateLimiter;
        this.authorizer = authorizer;
    }

    /**
     * GET/HEAD/OPTIONS read, DELETE deletes, everything else writes. Case-insensitive, because an
     * upstream that upper-cases {@code delete} would otherwise run a DELETE we classified as a write.
     */
    static String action(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> "read";
            case "DELETE" -> "delete";
            default -> "write";
        };
    }

    /** @return rate-limit headers to add to the response */
    public Map<String, String> enforce(Tenant tenant, Principal caller, Route route, String method, String path) {
        // 1. Path allowlist (empty list = nothing allowed).
        if (tenant.allowedPathPrefixes().stream().noneMatch(prefix -> Paths.matches(path, prefix))) {
            throw new GatewayException(ErrorCode.PATH_NOT_ALLOWED,
                    "path '" + path + "' is not in the allowlist of tenant '" + tenant.id() + "'");
        }

        // 2. Scope = which services. The token must carry the route's service, and the tenant must still
        //    be allowed it, so removing a service from a tenant also cuts off tokens issued before.
        String service = route.service();
        if (!caller.scopes().contains(service) || !tenant.grantableScopes().contains(service)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE, "token has no scope for service '" + service + "'");
        }

        // 3. RBAC = which actions: the token's role needs <service>:<action>, and the user must still
        //    hold that role (checked now, so revoking a role does not wait for the token to expire).
        Subject subject = new Subject(tenant.id(), caller.user(), caller.role());
        Permission needed = new Permission(service, action(method));
        if (!authorizer.allowed(subject, needed)) {
            throw new GatewayException(ErrorCode.PERMISSION_DENIED, authorizer.holdsRole(subject)
                    ? "role '" + caller.role() + "' does not have " + needed
                    : "role '" + caller.role() + "' is not assigned to user '" + caller.user() + "'");
        }

        // 4. Per-user quota, before the tenant's: otherwise one runaway user would spend the whole tenant's
        //    quota first. (A request the tenant quota then rejects has still used a user token.)
        if (tenant.userRequestsPerMinute() > 0) {
            RateLimiter.Result user = rateLimiter.tryAcquire("user:" + tenant.id() + "/" + caller.user(),
                    tenant.userRequestsPerMinute(), tenant.userBurst());
            if (!user.allowed()) {
                throw new GatewayException(ErrorCode.USER_RATE_LIMITED,
                        "user '" + caller.user() + "': more than " + tenant.userRequestsPerMinute() + " requests per minute",
                        Map.of("Retry-After", String.valueOf(user.retryAfterSeconds())));
            }
        }

        // 5. Tenant quota: a token bucket that holds up to `burst` requests and refills at requestsPerMinute.
        int burst = tenant.effectiveBurst();
        RateLimiter.Result quota = rateLimiter.tryAcquire("tenant:" + tenant.id(), tenant.requestsPerMinute(), burst);
        String limit = String.valueOf(tenant.requestsPerMinute());
        if (!quota.allowed()) {
            throw new GatewayException(ErrorCode.RATE_LIMITED,
                    "more than " + limit + " requests per minute (burst " + burst + ")",
                    Map.of("Retry-After", String.valueOf(quota.retryAfterSeconds()), "X-RateLimit-Limit", limit,
                            "X-RateLimit-Remaining", "0"));
        }
        // 6. Route limit (if the route has one): all tenants together, the service owner's protection for
        //    the upstream. Last, because its bucket is shared: a request another limit rejects must not
        //    spend a token every tenant needs. (One it rejects has spent the tenant's and user's.)
        if (route.requestsPerMinute() > 0) {
            RateLimiter.Result routeQuota = rateLimiter.tryAcquire("route:" + route.id(), route.requestsPerMinute(),
                    route.effectiveBurst());
            if (!routeQuota.allowed()) {
                throw new GatewayException(ErrorCode.ROUTE_RATE_LIMITED,
                        "route '" + route.id() + "': more than " + route.requestsPerMinute() + " requests per minute",
                        Map.of("Retry-After", String.valueOf(routeQuota.retryAfterSeconds())));
            }
        }
        return quota.remaining() < 0 // unknown: the rate limiter failed open
                ? Map.of()
                : Map.of("X-RateLimit-Limit", limit, "X-RateLimit-Remaining", String.valueOf(quota.remaining()));
    }
}
