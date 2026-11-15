-- Gateway configuration, the source of truth. Every gateway instance serves it from an in-memory
-- snapshot and reloads it when config_version changes.
--
-- Portable on purpose: only CREATE TABLE IF NOT EXISTS, primary keys, a unique constraint and a
-- foreign key, which H2, Postgres and MySQL all accept. Records are stored as the admin API's JSON
-- bodies in a large-text column; that column type is the one thing that differs per database:
-- H2 CLOB, Postgres TEXT, MySQL LONGTEXT. To use another database, copy this file to
-- schema-<platform>.sql with that type, and set spring.sql.init.platform and spring.datasource.url.

CREATE TABLE IF NOT EXISTS tenants (
    id   VARCHAR(63) PRIMARY KEY,
    data CLOB        NOT NULL
);

CREATE TABLE IF NOT EXISTS routes (
    id          VARCHAR(63)  PRIMARY KEY,
    path_prefix VARCHAR(512) NOT NULL UNIQUE,  -- no two routes share a prefix, even under concurrent writes
    data        CLOB         NOT NULL
);

-- Custom roles only; built-in roles are in application.yml.
CREATE TABLE IF NOT EXISTS roles (
    name        VARCHAR(63) PRIMARY KEY,
    permissions CLOB        NOT NULL
);

CREATE TABLE IF NOT EXISTS role_assignments (
    tenant_id VARCHAR(63) NOT NULL,
    user_id   VARCHAR(63) NOT NULL,
    roles     CLOB        NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE  -- a deleted tenant's users lose their roles
);

-- Revoked tenant tokens, by token id (jti). Not configuration: not in the snapshot and no version
-- change; read per token through a short cache. A row is needed only until the token would have
-- expired anyway (at most jwt.max-ttl after the revocation); later revocations delete expired rows.
CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti        VARCHAR(36) PRIMARY KEY,
    expires_at BIGINT      NOT NULL   -- epoch milliseconds (portable, no time-zone semantics)
);

-- One row (id = 1): a random generation token, changed in the same transaction as every write.
CREATE TABLE IF NOT EXISTS config_version (
    id      INT         PRIMARY KEY,
    version VARCHAR(36) NOT NULL
);
