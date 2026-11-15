# Architecture

## Request flow (with every failure branch)

```mermaid
flowchart TD
    C([Client]) --> A["AuditFilter<br/>request id, trace context, start audit record"]
    A --> N{"Path canonical?<br/>no .. %2f // ; control chars"}
    N -- no --> E400["400 BAD_PATH"]
    N -- yes --> IP{"IpRateLimitFilter:<br/>per client address<br/>(before authentication)"}
    IP -- exceeded --> E429I["429 IP_RATE_LIMITED<br/>+ Retry-After"]
    IP -- ok --> R{"/admin/** ?"}
    R -- yes --> K{"Admin session valid?<br/>(from POST /admin/login,<br/>user still holds its role)"}
    K -- no --> E401A["401 ADMIN_UNAUTHORIZED"]
    K -- yes --> AZ{"Active role has the endpoint's<br/>@RequiresPermission?"}
    AZ -- no --> E403A["403 PERMISSION_DENIED"]
    AZ -- yes --> ADM["AdminController<br/>tenants, routes, tokens<br/>(writes the config DB:<br/>data + version, one transaction)"]
    R -- no --> J{"AuthFilter: Bearer JWT valid?<br/>RS256 only, iss, aud, exp, tid"}
    J -- "no, or jti revoked" --> E401["401 UNAUTHENTICATED<br/>+ WWW-Authenticate"]
    J -- yes --> T{"Tenant (loaded on demand:<br/>5s cache, single flight)<br/>and enabled?"}
    T -- no --> E403T["403 TENANT_DISABLED"]
    T -- "database down and tenant<br/>never cached (config-db breaker)" --> E503D["503 CONFIG_UNAVAILABLE<br/>+ Retry-After"]
    T -- yes --> RT{"Route match?<br/>longest path prefix"}
    RT -- no --> E404["404 ROUTE_NOT_FOUND"]
    RT -- yes --> SZ{"Content-Length<br/>within limit?"}
    SZ -- no --> E413["413 PAYLOAD_TOO_LARGE"]
    SZ -- yes --> P1{"PolicyEngine:<br/>path in tenant allowlist?"}
    P1 -- no --> E403P["403 PATH_NOT_ALLOWED"]
    P1 -- yes --> P2{"scope: token (and tenant)<br/>may reach the route's service?"}
    P2 -- no --> E403S["403 INSUFFICIENT_SCOPE"]
    P2 -- yes --> P2R{"RBAC (Authorizer): user holds the role,<br/>role has service:action (by method)?"}
    P2R -- no --> E403R["403 PERMISSION_DENIED"]
    P2R -- yes --> PU{"Per-user bucket<br/>(if the tenant sets one)"}
    PU -- exceeded --> E429U["429 USER_RATE_LIMITED"]
    PU -- ok --> P3{"Tenant token bucket: rate + burst<br/>(in memory, behind a breaker)"}
    P3 -- exceeded --> E429["429 RATE_LIMITED<br/>+ Retry-After"]
    P3 -- "limiter failing,<br/>fail-closed" --> E503R["503 RATE_LIMITER_UNAVAILABLE"]
    P3 -- "ok (or limiter failing,<br/>fail-open)" --> PRL{"Route bucket, all tenants together<br/>(if the route sets a limit)"}
    PRL -- exceeded --> E429RT["429 ROUTE_RATE_LIMITED<br/>+ Retry-After"]
    PRL -- ok --> BH{"Bulkhead: tenant has fewer than<br/>N requests in flight?"}
    BH -- no --> E429C["429 CONCURRENCY_LIMITED"]
    BH -- yes --> LB["Pick upstream: by weight (Vose alias, O(1)),<br/>or by tenant on a sticky route (rendezvous hash)"]
    LB --> CB{"Circuit breaker<br/>for that upstream"}
    CB -- "open, route has another upstream<br/>whose breaker admits the call" --> PX
    CB -- "open, all upstreams open" --> E503C["503 CIRCUIT_OPEN<br/>+ Retry-After"]
    PX -- "connect failure / timeout,<br/>idempotent, budget left" --> RY["Retry: backoff with full jitter,<br/>prefer another upstream"]
    RY --> LB
    CB -- "closed / half-open probe" --> PX["UpstreamProxy (pooled HttpClient 5)<br/>strip hop-by-hop + Authorization,<br/>set X-Tenant-Id, X-User-Id, X-Role, traceparent,<br/>stream body<br/>(hedged route: a second copy after hedgeAfterMs,<br/>first answer wins)"]
    PX -- "pool full" --> E503P["503 UPSTREAM_SATURATED"]
    PX -- "chunked body over limit" --> E413
    PX -- "connect error" --> E502["502 UPSTREAM_ERROR"]
    PX -- timeout --> E504["504 UPSTREAM_TIMEOUT"]
    PX -- response --> OK(["upstream status, headers and body<br/>returned unchanged"])
    E400 & E429I & E401A & E403A & E401 & E403T & E503D & E404 & E413 & E403P & E403S & E403R & E429U & E429 & E503R & E429RT & E429C & E503C & E503P & E502 & E504 & OK & ADM --> AU[["AuditFilter finally:<br/>audit entry -> async queue -> JSON line<br/>+ request metric"]]
    classDef err fill:#fde8e8,stroke:#c53030,color:#742a2a
    class E400,E429I,E401A,E403A,E401,E403T,E503D,E404,E413,E403P,E403S,E403R,E429U,E429,E503R,E429RT,E429C,E503C,E503P,E502,E504 err
```

Two rules make this diagram true by construction:

* **Reject = throw.** Every stage either passes or throws a `GatewayException`. The outermost filter
  (or the controller advice) renders it as `application/problem+json`. A rejected request therefore
  cannot reach the proxy code.
* **One audit entry per request**, written in `AuditFilter`'s `finally`. Stages only fill in fields
  of the request's `AuditRecord`.

The order is deliberate: local, cheap checks first; the size check before anything that spends quota;
the quota check (which also *consumes* quota) last among the policies; the circuit breaker
immediately before the upstream call.

## Components

```mermaid
flowchart LR
    subgraph Gateway instance
      AF[AuditFilter] --> AuF[AuthFilter] --> PC[ProxyController]
      AuF -.-> TS[TokenService<br/>RS256 verify]
      PC --> CS[ConfigStore<br/>immutable snapshot]
      PC --> PE[PolicyEngine] --> CBR[CircuitBreakingRateLimiter<br/>breaker + fail-open] --> RL[InMemoryRateLimiter<br/>token buckets, Caffeine]
      PC --> CB[CircuitBreaker<br/>per upstream]
      PC --> UP[UpstreamProxy<br/>HttpClient 5 pool]
      AF --> AS[AsyncAuditSink<br/>bounded queue]
      PI[PermissionInterceptor] --> ADM[AdminController] --> CS
      PE -- "allowed(subject, service:action)?" --> AZ[RBAC module<br/>Authorizer, in memory]
      PI -- "allowed(subject, routes:write)?" --> AZ
      AZ -. "custom roles, assignments<br/>(from the snapshot)" .-> CS
      PI --> RC[RbacController<br/>/admin/rbac] --> CS
    end
    CS --> REPO[ConfigRepository<br/>JdbcConfigRepository]
    REPO <-- "poll version / reload" --> DB[(Config database<br/>H2 file by default;<br/>any JDBC DB by URL)]

    UP --> U1[upstream A]
    UP --> U2[upstream B, canary]
    AS --> LOG[stdout JSON lines<br/>-> log shipper]
```

| State | Where | Shared across instances? |
|---|---|---|
| Routes, custom roles, tenant users' role assignments | Config database (H2 file by default), served from an in-memory snapshot | With one shared database (Postgres, MySQL): yes, ≤ 5s propagation. The demo's embedded H2 belongs to its one instance |
| Tenants | Config database, loaded on demand into a 5s single-flight cache | Same as above (≤ 5s propagation with a shared database) |
| Rate-limit buckets | Instance memory (Caffeine) | No: each instance counts on its own (N instances = up to N× a tenant's quota; the demo runs one) |
| Circuit breakers, connection pool, audit queue | Instance memory | No (by design: each instance judges its own view of upstream health) |
| Signing key, admin users (key hashes) and roles | Environment / secret manager / application.yml | Same values on every instance |

## Sequence: an allowed request

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant G as Gateway
    participant U as Upstream
    C->>G: GET /orders/7 (Bearer JWT)
    G->>G: request id, canonical path, verify JWT (local, no I/O)
    G->>G: tenant + route from the in-memory snapshot (no I/O)
    G->>G: allowlist, scope (service), RBAC (role has orders:read, in memory)
    G->>G: take one token from the tenant's bucket (in memory)
    G->>G: pick upstream (alias table, sticky route: by tenant), breaker permit<br/>(hedged route: a second copy if no answer after hedgeAfterMs)
    G->>U: GET /orders/7 + X-Tenant-Id, X-User-Id, X-Role, X-Request-Id, X-Forwarded-*, traceparent (no Authorization)
    U-->>G: 200 + body (streamed)
    G-->>C: 200 + body (streamed) + X-RateLimit-*, X-Request-Id
    G-)G: enqueue audit entry (written by a background thread)
```

No per-request I/O before the upstream: routes, RBAC and the quota are in memory; a tenant and a token's
revocation state are each read from the config database once per 5s per instance (single flight, stale
if error), so a cache miss costs one read shared by every concurrent request that needs it.

## Configuration changes at runtime

```mermaid
sequenceDiagram
    participant Op as Operator
    participant A as Instance A
    participant D as Config database
    participant B as Instance B
    Op->>A: PUT /admin/routes/orders
    A->>D: BEGIN, UPDATE routes ..., UPDATE config_version = a new random token, COMMIT
    A->>A: reload snapshot (applies immediately here)
    loop every 5s ± 20% jitter
        B->>D: SELECT version FROM config_version
        alt version changed
            B->>D: SELECT tenants, routes, roles, role_assignments
            B->>B: swap in new snapshot
        else unchanged, or version missing (database lost its data)
            B->>B: keep current snapshot
        end
    end
```

Two instances only see each other's writes when they share one database (Postgres, MySQL). With the
demo's embedded H2 there is one instance, and the poll just confirms nothing changed.
