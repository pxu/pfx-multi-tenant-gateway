package com.example.gateway.error;

/** Every way the gateway can refuse or fail a request, with its HTTP status. */
public enum ErrorCode {
    BAD_PATH(400, "Malformed or unsafe request path"),
    VALIDATION_ERROR(400, "Request validation failed"),
    UNAUTHENTICATED(401, "Authentication required"),
    ADMIN_UNAUTHORIZED(401, "Admin credentials required"),
    PERMISSION_DENIED(403, "Active role lacks the required permission"),
    TENANT_DISABLED(403, "Tenant is not active"),
    PATH_NOT_ALLOWED(403, "Path not allowed for tenant"),
    INSUFFICIENT_SCOPE(403, "Token lacks a required scope"),
    ROUTE_NOT_FOUND(404, "No route matches the request path"),
    NOT_FOUND(404, "Resource not found"),
    CONFLICT(409, "Resource conflict"),
    PAYLOAD_TOO_LARGE(413, "Request body too large"),
    RATE_LIMITED(429, "Tenant rate limit exceeded"),
    USER_RATE_LIMITED(429, "User rate limit exceeded"),
    ROUTE_RATE_LIMITED(429, "Route rate limit exceeded"),
    IP_RATE_LIMITED(429, "Too many requests from this address"),
    CONCURRENCY_LIMITED(429, "Too many concurrent requests for this tenant"),
    RATE_LIMITER_UNAVAILABLE(503, "Rate limiter unavailable"),
    CONFIG_UNAVAILABLE(503, "Configuration store unavailable"),
    CIRCUIT_OPEN(503, "Upstream temporarily unavailable"),
    UPSTREAM_SATURATED(503, "Upstream connection pool exhausted"),
    UPSTREAM_ERROR(502, "Upstream request failed"),
    UPSTREAM_TIMEOUT(504, "Upstream did not respond in time"),
    INTERNAL_ERROR(500, "Internal error");

    public final int status;
    public final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    /**
     * A policy denial: the gateway decided not to forward. The other codes describe requests that
     * were allowed but failed, or admin input errors.
     */
    public boolean isDenial() {
        return switch (this) {
            case VALIDATION_ERROR, NOT_FOUND, CONFLICT, UPSTREAM_ERROR, UPSTREAM_TIMEOUT, UPSTREAM_SATURATED,
                    CONFIG_UNAVAILABLE, INTERNAL_ERROR -> false;
            default -> true;
        };
    }
}
