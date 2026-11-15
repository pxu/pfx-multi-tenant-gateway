package com.example.gateway.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the configuration lives: the source of truth behind {@link ConfigStore}. The store serves an
 * in-memory snapshot and only calls this to load it and to apply admin writes, so swapping the
 * database (or using something that is not SQL at all) means another implementation of this
 * interface, or for any JDBC database just another URL and schema file ({@link JdbcConfigRepository}).
 *
 * <p>Contract: every successful write also replaces the version with a new random token, <b>in the
 * same transaction</b>, so whoever reads the new version reads the new data. Failed writes change
 * nothing. Records are validated by the caller. Token revocations are the exception: they are not
 * configuration (not in the snapshot, read per token), so they do not change the version.
 */
public interface ConfigRepository {

    /** Outcome of a write that can fail for a reason the caller reports to the client. */
    enum Write { DONE, NOT_FOUND, ALREADY_EXISTS, PREFIX_TAKEN }

    /** The current generation token; {@code null} if there is none (an empty, or wiped, store). */
    String version();

    List<Tenant> tenants();

    Optional<Tenant> tenant(String id);

    Write insertTenant(Tenant tenant);

    Write updateTenant(Tenant tenant);

    /** Also deletes the tenant's users' role assignments. */
    Write deleteTenant(String id);

    List<Route> routes();

    Optional<Route> route(String id);

    /** {@code PREFIX_TAKEN} if another route already uses the path prefix. */
    Write insertRoute(Route route);

    Write updateRoute(Route route);

    Write deleteRoute(String id);

    /** Custom roles: role → permissions. */
    Map<String, List<String>> roles();

    /** Creates or replaces. */
    void putRole(String role, List<String> permissions);

    Write deleteRole(String role);

    /** All tenant users' role assignments, keyed {@code <tenant>/<user>}. */
    Map<String, List<String>> assignments();

    /** One tenant's assignments: user → roles. */
    Map<String, List<String>> assignments(String tenant);

    /** Creates or replaces the user's roles. The tenant must exist. */
    void putAssignment(String tenant, String user, List<String> roles);

    Write deleteAssignment(String tenant, String user);

    /**
     * Marks a token id revoked until {@code expiresAt} (by then the token has expired anyway).
     * Idempotent. Also deletes revocations that expired before {@code now}, so the table stays small.
     */
    void revokeToken(String tokenId, Instant expiresAt, Instant now);

    boolean isRevoked(String tokenId);
}
