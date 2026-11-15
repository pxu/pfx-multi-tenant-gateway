package com.example.gateway.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ConfigRepository} on any JDBC database (H2 by default; Postgres or MySQL by URL and schema).
 *
 * <p>Portable SQL only: plain SELECT / INSERT / UPDATE / DELETE with bound parameters. No upsert syntax
 * (H2 {@code MERGE}, Postgres {@code ON CONFLICT}, MySQL {@code ON DUPLICATE KEY} all differ): create-or-
 * replace is "UPDATE, and INSERT if nothing was updated" in one transaction. No JSON column types: the
 * records are JSON text. Uniqueness and the tenant → assignments cascade are database constraints.
 *
 * <p>Every write and its version change commit together ({@link #write}). A statement that violates a
 * constraint rolls the whole transaction back before anything else runs, which also keeps Postgres
 * (where a failed statement aborts the transaction) happy.
 */
public class JdbcConfigRepository implements ConfigRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcConfigRepository(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public String version() {
        return jdbc.sql("SELECT version FROM config_version WHERE id = 1").query(String.class).optional().orElse(null);
    }

    // ---- tenants -----------------------------------------------------------------------------

    @Override
    public List<Tenant> tenants() {
        return jdbc.sql("SELECT data FROM tenants ORDER BY id").query((rs, i) -> read(rs.getString(1), Tenant.class)).list();
    }

    @Override
    public Optional<Tenant> tenant(String id) {
        return jdbc.sql("SELECT data FROM tenants WHERE id = ?").param(id)
                .query((rs, i) -> read(rs.getString(1), Tenant.class)).optional();
    }

    @Override
    public Write insertTenant(Tenant t) {
        try {
            return write(() -> {
                if (exists("SELECT COUNT(*) FROM tenants WHERE id = ?", t.id())) {
                    return Write.ALREADY_EXISTS;
                }
                jdbc.sql("INSERT INTO tenants (id, data) VALUES (?, ?)").params(t.id(), text(t)).update();
                return Write.DONE;
            });
        } catch (DuplicateKeyException e) {
            return Write.ALREADY_EXISTS; // lost a race with a concurrent insert
        }
    }

    @Override
    public Write updateTenant(Tenant t) {
        return write(() -> jdbc.sql("UPDATE tenants SET data = ? WHERE id = ?").params(text(t), t.id()).update() == 0
                ? Write.NOT_FOUND : Write.DONE);
    }

    @Override
    public Write deleteTenant(String id) {
        // role_assignments rows go with it (ON DELETE CASCADE)
        return write(() -> jdbc.sql("DELETE FROM tenants WHERE id = ?").param(id).update() == 0 ? Write.NOT_FOUND : Write.DONE);
    }

    // ---- routes ------------------------------------------------------------------------------

    @Override
    public List<Route> routes() {
        return jdbc.sql("SELECT data FROM routes ORDER BY id").query((rs, i) -> read(rs.getString(1), Route.class)).list();
    }

    @Override
    public Optional<Route> route(String id) {
        return jdbc.sql("SELECT data FROM routes WHERE id = ?").param(id)
                .query((rs, i) -> read(rs.getString(1), Route.class)).optional();
    }

    @Override
    public Write insertRoute(Route r) {
        try {
            return write(() -> {
                if (exists("SELECT COUNT(*) FROM routes WHERE id = ?", r.id())) {
                    return Write.ALREADY_EXISTS;
                }
                if (exists("SELECT COUNT(*) FROM routes WHERE path_prefix = ?", r.pathPrefix())) {
                    return Write.PREFIX_TAKEN;
                }
                jdbc.sql("INSERT INTO routes (id, path_prefix, data) VALUES (?, ?, ?)")
                        .params(r.id(), r.pathPrefix(), text(r)).update();
                return Write.DONE;
            });
        } catch (DuplicateKeyException e) {
            return route(r.id()).isPresent() ? Write.ALREADY_EXISTS : Write.PREFIX_TAKEN; // lost a race
        }
    }

    @Override
    public Write updateRoute(Route r) {
        try {
            return write(() -> {
                if (exists("SELECT COUNT(*) FROM routes WHERE path_prefix = ? AND id <> ?", r.pathPrefix(), r.id())) {
                    return Write.PREFIX_TAKEN;
                }
                return jdbc.sql("UPDATE routes SET path_prefix = ?, data = ? WHERE id = ?")
                        .params(r.pathPrefix(), text(r), r.id()).update() == 0 ? Write.NOT_FOUND : Write.DONE;
            });
        } catch (DuplicateKeyException e) {
            return Write.PREFIX_TAKEN; // lost a race for the prefix
        }
    }

    @Override
    public Write deleteRoute(String id) {
        return write(() -> jdbc.sql("DELETE FROM routes WHERE id = ?").param(id).update() == 0 ? Write.NOT_FOUND : Write.DONE);
    }

    // ---- RBAC data ---------------------------------------------------------------------------

    @Override
    public Map<String, List<String>> roles() {
        Map<String, List<String>> roles = new TreeMap<>();
        jdbc.sql("SELECT name, permissions FROM roles")
                .query(rs -> {
                    roles.put(rs.getString(1), read(rs.getString(2), STRINGS));
                });
        return Collections.unmodifiableMap(roles);
    }

    @Override
    public void putRole(String role, List<String> permissions) {
        String value = text(permissions);
        upsert(() -> jdbc.sql("UPDATE roles SET permissions = ? WHERE name = ?").params(value, role).update(),
                () -> jdbc.sql("INSERT INTO roles (name, permissions) VALUES (?, ?)").params(role, value).update());
    }

    @Override
    public Write deleteRole(String role) {
        return write(() -> jdbc.sql("DELETE FROM roles WHERE name = ?").param(role).update() == 0 ? Write.NOT_FOUND : Write.DONE);
    }

    @Override
    public Map<String, List<String>> assignments() {
        Map<String, List<String>> all = new TreeMap<>();
        jdbc.sql("SELECT tenant_id, user_id, roles FROM role_assignments")
                .query(rs -> {
                    all.put(rs.getString(1) + "/" + rs.getString(2), read(rs.getString(3), STRINGS));
                });
        return Collections.unmodifiableMap(all);
    }

    @Override
    public Map<String, List<String>> assignments(String tenant) {
        Map<String, List<String>> users = new TreeMap<>();
        jdbc.sql("SELECT user_id, roles FROM role_assignments WHERE tenant_id = ?").param(tenant)
                .query(rs -> {
                    users.put(rs.getString(1), read(rs.getString(2), STRINGS));
                });
        return Collections.unmodifiableMap(users);
    }

    @Override
    public void putAssignment(String tenant, String user, List<String> roles) {
        String value = text(roles);
        upsert(() -> jdbc.sql("UPDATE role_assignments SET roles = ? WHERE tenant_id = ? AND user_id = ?")
                        .params(value, tenant, user).update(),
                () -> jdbc.sql("INSERT INTO role_assignments (tenant_id, user_id, roles) VALUES (?, ?, ?)")
                        .params(tenant, user, value).update());
    }

    @Override
    public Write deleteAssignment(String tenant, String user) {
        return write(() -> jdbc.sql("DELETE FROM role_assignments WHERE tenant_id = ? AND user_id = ?")
                .params(tenant, user).update() == 0 ? Write.NOT_FOUND : Write.DONE);
    }

    // ---- token revocations (not configuration: no version change) ------------------------------

    @Override
    public void revokeToken(String tokenId, Instant expiresAt, Instant now) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM revoked_tokens WHERE expires_at < ?").param(now.toEpochMilli()).update();
                if (jdbc.sql("UPDATE revoked_tokens SET expires_at = ? WHERE jti = ?")
                        .params(expiresAt.toEpochMilli(), tokenId).update() == 0) {
                    jdbc.sql("INSERT INTO revoked_tokens (jti, expires_at) VALUES (?, ?)")
                            .params(tokenId, expiresAt.toEpochMilli()).update();
                }
            });
        } catch (DuplicateKeyException e) {
            // a concurrent revocation of the same token inserted it first: revoked either way
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return exists("SELECT COUNT(*) FROM revoked_tokens WHERE jti = ?", tokenId);
    }

    // ---- transactions, version, JSON ---------------------------------------------------------

    /**
     * Runs {@code change} and, if it succeeded, replaces the version, in one transaction. If a unique
     * constraint fires (a concurrent writer inserted the same key, or created the version row, first),
     * the transaction has rolled back and runs once more: its checks now see the other writer's row.
     */
    private Write write(Supplier<Write> change) {
        try {
            return transaction(change);
        } catch (DuplicateKeyException e) {
            return transaction(change);
        }
    }

    private Write transaction(Supplier<Write> change) {
        return tx.execute(status -> {
            Write result = change.get();
            if (result == Write.DONE) {
                bumpVersion();
            }
            return result;
        });
    }

    /** Create or replace without vendor upsert syntax. */
    private void upsert(Supplier<Integer> update, Supplier<Integer> insert) {
        write(() -> {
            if (update.get() == 0) {
                insert.get();
            }
            return Write.DONE;
        });
    }

    /** A random token, not a counter: after a data loss a counter could repeat a version an instance already has. */
    private void bumpVersion() {
        String version = UUID.randomUUID().toString();
        if (jdbc.sql("UPDATE config_version SET version = ? WHERE id = 1").param(version).update() == 0) {
            jdbc.sql("INSERT INTO config_version (id, version) VALUES (1, ?)").param(version).update();
        }
    }

    private boolean exists(String countSql, Object... params) {
        return jdbc.sql(countSql).params(params).query(Long.class).single() > 0;
    }

    private String text(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt " + type.getSimpleName() + " in the config database", e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt entry in the config database", e);
        }
    }
}
