package com.example.gateway.store;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;

/**
 * A tenant and its policy. Also the JSON body of the admin API (validated on input).
 *
 * @param requestsPerMinute   sustained rate: the token bucket refills at this many tokens per minute
 * @param burst               bucket capacity: how many requests may arrive at once, at most
 *                            requestsPerMinute; 0 = one second's worth (see {@link #effectiveBurst()})
 * @param allowedPathPrefixes paths this tenant may call (segment-aware prefixes). Empty = nothing.
 * @param grantableScopes     the services ({@code Route.service}) tokens for this tenant may reach, at most.
 *                            The tenant's users and their roles are RBAC data ({@code /admin/rbac}).
 * @param userRequestsPerMinute per user of this tenant, checked before the tenant's own quota (so one
 *                            runaway user cannot spend everyone's); at most requestsPerMinute; 0 = no limit per user
 * @param maxConcurrentRequests most of this tenant's requests in flight at once (bulkhead); 0 = the
 *                            gateway default ({@code gateway.tenant-max-concurrent-requests})
 */
public record Tenant(
        @Pattern(regexp = Tenant.ID) String id,
        @NotBlank String name,
        boolean disabled,
        @Min(1) @Max(1_000_000) int requestsPerMinute,
        @Min(0) @Max(1_000_000) int burst,
        List<@Pattern(regexp = "^/.*") String> allowedPathPrefixes,
        Set<@Pattern(regexp = Tenant.SCOPE) String> grantableScopes,
        @Min(0) @Max(1_000_000) int userRequestsPerMinute,
        @Min(0) @Max(100_000) int maxConcurrentRequests) {

    public static final String ID = "^[a-z0-9][a-z0-9-]{0,62}$";
    public static final String SCOPE = "^[a-z0-9:._-]{1,64}$";

    public Tenant {
        allowedPathPrefixes = allowedPathPrefixes == null ? List.of() : List.copyOf(allowedPathPrefixes);
        grantableScopes = grantableScopes == null ? Set.of() : Set.copyOf(grantableScopes);
    }

    /** Without a per-user limit. */
    public Tenant(String id, String name, boolean disabled, int requestsPerMinute, int burst,
            List<String> allowedPathPrefixes, Set<String> grantableScopes) {
        this(id, name, disabled, requestsPerMinute, burst, allowedPathPrefixes, grantableScopes, 0, 0);
    }

    /** With a per-user limit, the default concurrency limit. */
    public Tenant(String id, String name, boolean disabled, int requestsPerMinute, int burst,
            List<String> allowedPathPrefixes, Set<String> grantableScopes, int userRequestsPerMinute) {
        this(id, name, disabled, requestsPerMinute, burst, allowedPathPrefixes, grantableScopes, userRequestsPerMinute, 0);
    }

    /** A user's bucket holds one second of their rate (at least 1): a user cannot burst like a tenant. */
    public int userBurst() {
        return Math.max(1, Math.ceilDiv(userRequestsPerMinute, 60));
    }

    /** The bucket capacity actually used: {@code burst}, or one second's worth (at least 1) when unset. */
    public int effectiveBurst() {
        return burst > 0 ? burst : Math.max(1, Math.ceilDiv(requestsPerMinute, 60));
    }

    public Tenant withId(String newId) {
        return new Tenant(newId, name, disabled, requestsPerMinute, burst, allowedPathPrefixes, grantableScopes,
                userRequestsPerMinute, maxConcurrentRequests);
    }
}
