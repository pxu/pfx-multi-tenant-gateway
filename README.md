# Multi-Tenant API Gateway

A reverse proxy that authenticates callers, routes requests to upstream services through a route
table you can change at runtime, enforces per-tenant policy (path allowlist, scopes per service, RBAC per action,
rate limit as a token bucket: per-minute rate plus a burst) and writes an audit entry for every request. Upstream calls
go through per-upstream circuit breakers and a tuned connection pool; routes can split traffic
across weighted upstreams (canary).

Java 21 · Spring Boot 3.5 (MVC on virtual threads) · Apache HttpClient 5 · Caffeine · JDBC + H2 · Nimbus JOSE (JWT)

| Document | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Request flow with every failure branch, components, sequences |
| [docs/threat-model.md](docs/threat-model.md) | What an authenticated-but-malicious tenant can and cannot do |
| [docs/design-decisions.md](docs/design-decisions.md) | Trade-offs, what was left out and why, how the pieces interact |
| [docs/design-brief.pdf](docs/design-brief.pdf) | A short brief for walking someone through the design: every component, then the end-to-end workflow (source: `design-brief.html`) |

## Request flow

```
client ─> AuditFilter ─> IpRateLimitFilter (429 per address) ─> AuthFilter ─> ProxyController ──> UpstreamProxy ─> upstream
          request id     /admin/**:    1. tenant in snapshot and enabled     (403)      HttpClient 5 pool
          canonical      session       2. longest-prefix route               (404)      strip hop-by-hop,
          path (400)     else: JWT,    3. body size                          (413)      Authorization;
          renders every  not revoked   4. PolicyEngine: allowlist, scope,    (403)      set X-Tenant-Id,
          rejection      (401)            RBAC: action by method             (403)      X-User-Id, X-Role;
          finally: one                    token buckets: user, tenant, route (429)      stream both ways
          audit entry                  5. bulkhead per tenant                (429)      (502 / 504 / 503)
                                       6. upstream pick (weighted / sticky),
                                          breaker, retry or hedge            (503)
```

Every stage either passes or **throws** a `GatewayException`, rendered as RFC 9457
`application/problem+json` in one place, so a rejected request cannot reach the upstream. Every
request produces exactly one audit entry. The full diagram is in
[docs/architecture.md](docs/architecture.md).

| Class | Role |
|---|---|
| `audit/AuditFilter` | Outermost filter: request id, path canonicalisation, error rendering, audit entry, request metric |
| `auth/AuthFilter`, `auth/TokenService` | Admin session for `/admin/**`; RS256 JWT issue and verify for everything else |
| `rbac/Authorizer`, `rbac/RbacAuthorizer` | The RBAC module (decision point): user → roles → permissions; the rest of the gateway only uses its API |
| `auth/SessionService`, `auth/PermissionInterceptor` | Operator login with one role; per-endpoint `@RequiresPermission(AdminPermissions.X)` on the admin API |
| `proxy/ProxyController` | The pipeline: tenant → route → size → policy → upstream pick → breaker → proxy |
| `policy/PolicyEngine`, `policy/RateLimiter` (interface), `policy/InMemoryRateLimiter`, `policy/CircuitBreakingRateLimiter` | Allowlist, scope (service), RBAC permission (action by HTTP method); in-memory token-bucket rate limit (Caffeine), wrapped in a circuit breaker with fail-open/closed |
| `proxy/UpstreamProxy` | HTTP forwarding over a pooled HttpClient 5 (Go: `httputil.ReverseProxy`) |
| `resilience/CircuitBreaker` | Per upstream service, and around the rate limiter; half-open probes |
| `store/ConfigStore`, `store/ConfigRepository`, `store/JdbcConfigRepository` | Tenants, routes and RBAC data in a database (H2 by default, portable SQL); routes and RBAC data served from an immutable in-memory snapshot, tenants loaded on demand |
| `cache/SingleFlightCache` | Short-TTL cache with a single flight per key (Go: `singleflight`): 1,000 simultaneous misses = one database read |
| `routing/AliasTable` | O(1) weighted upstream selection (Vose's alias method) |
| `routing/RendezvousHash` | Sticky routes: the same tenant always gets the same upstream, by weight (weighted rendezvous hashing) |
| `audit/AsyncAuditSink` | Bounded queue + background writer for audit lines |
| `admin/AdminController` | Control plane: tenants, routes, token issuance |

## Run it

```bash
docker compose up -d --build   # gateway :8080 (data plane + admin), :8081 (health, metrics), echo upstream
./scripts/demo.sh              # creates a tenant, routes and a token, then shows 200/401/403/400/404/429
```

Without Docker: `GATEWAY_OPS_KEY_SHA256=$(printf %s local-ops-key | shasum -a 256 | cut -d' ' -f1) ./mvnw spring-boot:run`
(JDK 21). Nothing else to run: the config is an embedded H2 database in `./data/`.

On DigitalOcean App Platform (gateway + stable/canary upstreams in one app, redeployed on every merge to
`main`): `./scripts/do-app.sh deploy`, then `./scripts/do-app.sh demo`. See
[Deploy to DigitalOcean App Platform](#deploy-to-digitalocean-app-platform).

## Access control: scopes and RBAC

Two layers, for operators of the gateway and for tenant users alike:

* **Scope = which services.** A tenant token's `scope` lists the services it may reach (`orders`), at
  most the tenant's `grantableScopes`. Each route belongs to a `service` (default: the route id).
* **RBAC = which actions.** **User → roles → permissions.** A role has many permissions
  (`resource:action`), a role can be assigned to many users, and users never get permissions directly.
  A user may hold several roles but acts as **one** of them per login or token, with exactly that role's
  permissions. **Built-in** roles come from `gateway.rbac.roles` and are read-only; **custom** roles
  are created through the RBAC API. Nothing is special about admins: `admin` is just a built-in role
  whose permissions cover the management API.

| Role (application.yml) | Permissions |
|---|---|
| `viewer` | `tenants:read`, `routes:read`, `rbac:read` |
| `operator` | `tenants:read`, `tenants:write`, `routes:read`, `routes:write` |
| `admin` | all of the above + `tokens:issue`, `tokens:revoke`, `rbac:read`, `rbac:write` |
| `orders-reader` | `orders:read` |
| `orders-writer` | `orders:read`, `orders:write`, `orders:delete` |

On the data plane the action comes from the HTTP method: GET/HEAD/OPTIONS = `read`, DELETE = `delete`,
anything else = `write`. So a token for `rita` acting as `orders-reader` may `GET /orders/1` but gets
403 `PERMISSION_DENIED` on `DELETE /orders/1`, before anything reaches the upstream. The decision is
made by the RBAC module (`rbac/Authorizer`, in-process); the gateway only enforces it. The user's
assignment is checked on every request, so removing a role takes effect at once (≤ 5s on other
instances). Upstreams receive `X-Tenant-Id`, `X-User-Id` and `X-Role` (set by the gateway, never
taken from the client) for resource-level checks of their own.

**Operators** are in `gateway.rbac.users` (key hash + roles). `POST /admin/login {"user", "key",
"role"}` returns a session token (1h) for that one role; send it as `Authorization: Bearer` on every
other admin call. **Tenant users** get their roles through the RBAC API; a token is issued for one
user and one of their roles. Tenant tokens are never accepted on the admin API, and
sessions never on the data plane. A wrong user or key is 401 `ADMIN_UNAUTHORIZED`; a role the user
does not hold, or an action the role does not allow, is 403 `PERMISSION_DENIED`. Each audit entry
records `actor` and `role`. In docker compose, operator `ops` (roles `admin`, `viewer`) has the key
`local-ops-key`.

### Setup and token issuance

Log in as an operator, create a tenant, give its users roles, add a route, then issue a token:

```bash
SESSION=$(curl -s -X POST localhost:8080/admin/login -H 'Content-Type: application/json' \
  -d '{"user": "ops", "key": "local-ops-key", "role": "admin"}' | jq -r .token)
A=(-H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json')

curl -X POST localhost:8080/admin/tenants "${A[@]}" -d '{
  "id": "acme", "name": "Acme", "requestsPerMinute": 600, "burst": 20,
  "allowedPathPrefixes": ["/orders"], "grantableScopes": ["orders"]}'

# RBAC: rita may read orders; will holds two roles (a token acts as one of them)
curl -X PUT localhost:8080/admin/rbac/tenants/acme/users/rita "${A[@]}" -d '{"roles": ["orders-reader"]}'
curl -X PUT localhost:8080/admin/rbac/tenants/acme/users/will "${A[@]}" -d '{"roles": ["orders-reader", "orders-writer"]}'
# A custom role
curl -X PUT localhost:8080/admin/rbac/roles/orders-auditor "${A[@]}" -d '{"permissions": ["orders:read"]}'

# A route for service "orders" (its id) with a 90/10 canary split
curl -X POST localhost:8080/admin/routes "${A[@]}" -d '{
  "id": "orders", "pathPrefix": "/orders",
  "upstreams": [{"url": "http://echo:8080", "weight": 90}, {"url": "http://echo:8080/canary", "weight": 10}]}'

# Token for rita acting as orders-reader. scopes: services, a subset of grantableScopes (empty = all);
# ttlSeconds defaults to 900 and is capped at 3600.
curl -X POST localhost:8080/admin/tenants/acme/tokens "${A[@]}" -d '{"user": "rita", "role": "orders-reader", "scopes": ["orders"]}'
# -> {"token": "eyJ...", "tokenId": "...", "user": "rita", "role": "orders-reader", "expiresAt": "...", "scopes": ["orders"]}
# or: ./scripts/issue-token.sh acme rita orders-reader orders

curl localhost:8080/orders/42 -H "Authorization: Bearer $TOKEN"              # 200
curl -X DELETE localhost:8080/orders/42 -H "Authorization: Bearer $TOKEN"    # 403 PERMISSION_DENIED
```

| Endpoint | |
|---|---|
| `POST /admin/login` | Log in as one of your roles; no session needed |
| `GET/POST /admin/tenants`, `GET/PUT/DELETE /admin/tenants/{id}` | `tenants:read` / `tenants:write`. Tenants. `PUT` with `"disabled": true` cuts a tenant off even with valid tokens. `burst` = requests a tenant may send at once (token-bucket size, ≤ `requestsPerMinute`); `0` = one second's worth. `userRequestsPerMinute` (≤ `requestsPerMinute`, `0` = off) limits each of the tenant's users, before the tenant quota. `maxConcurrentRequests` caps its requests in flight (bulkhead; `0` = the default, 50). |
| `POST /admin/tenants/{id}/tokens` | `tokens:issue`. Issue a token for one of the tenant's users and one of their roles |
| `POST /admin/tokens/{tokenId}/revoke` | `tokens:revoke`. Revoke one tenant token by the `tokenId` returned at issuance (its `jti`): 401 on its next request (audit `detail: auth:REVOKED`), at once on this instance and within `tenant-cache-ttl` on others. Always 204, since the gateway keeps no list of issued tokens |
| `GET/POST /admin/routes`, `GET/PUT/DELETE /admin/routes/{id}` | `routes:read` / `routes:write`. Routes. Changes apply at once on this instance and within 5s on the others. `"sticky": true` sends each tenant to the same upstream every time (a sticky canary, weighted by tenant; rendezvous hashing); default false, a weighted pick per request. `"requestsPerMinute": 600, "burst": 50` limits the route as a whole, all tenants together (the service owner's protection for the upstream; checked after the tenant quota; `0` = no limit). `"hedgeAfterMs": 200` hedges: a GET/HEAD/OPTIONS without a body that has no response after 200ms is sent again (preferably to another upstream) and the first answer wins; spends the retry budget; `0` (default) = off. |
| `GET /admin/rbac/roles`, `PUT/DELETE /admin/rbac/roles/{role}` | `rbac:read` / `rbac:write`. Built-in and custom roles; only custom ones can be changed, and they may hold service permissions only (management permissions stay in built-in roles). `PUT {"permissions": [...]}` creates or replaces. A role still assigned cannot be deleted (409). |
| `GET /admin/rbac/tenants/{t}/users`, `PUT/DELETE /admin/rbac/tenants/{t}/users/{u}` | `rbac:read` / `rbac:write`. A tenant user's roles; `PUT {"roles": [...]}` replaces them. Deleting a tenant deletes its assignments. |

Tokens are RS256 JWTs: `{"iss", "aud", "sub": "rita", "tid": "acme", "role": "orders-reader", "scope": "orders", "exp", "jti"}`.
Set `GATEWAY_JWT_PRIVATE_KEY` to a PKCS#8 PEM (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`)
and give every instance the same key; without it an ephemeral key is generated and tokens stop working
on restart.

## Errors

```json
{"type": "urn:gateway:problem:rate-limited", "title": "Tenant rate limit exceeded", "status": 429,
 "detail": "more than 600 requests per minute (burst 20)", "code": "RATE_LIMITED", "requestId": "4f6c..."}
```

| Status | Codes |
|---|---|
| 400 | `BAD_PATH` (traversal, encoded slashes, ...), `VALIDATION_ERROR` |
| 401 | `UNAUTHENTICATED` (+ `WWW-Authenticate`), `ADMIN_UNAUTHORIZED` |
| 403 | `TENANT_DISABLED`, `PATH_NOT_ALLOWED`, `INSUFFICIENT_SCOPE`, `PERMISSION_DENIED` |
| 404 / 409 | `ROUTE_NOT_FOUND`, `NOT_FOUND` / `CONFLICT` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 429 | `IP_RATE_LIMITED` (per address, before authentication), `USER_RATE_LIMITED`, `RATE_LIMITED` (tenant), `ROUTE_RATE_LIMITED` (the route's own limit, all tenants together), `CONCURRENCY_LIMITED` (tenant's requests in flight); all with `Retry-After` |
| 502 / 504 | `UPSTREAM_ERROR` / `UPSTREAM_TIMEOUT` |
| 503 | `CIRCUIT_OPEN` (+ `Retry-After`), `UPSTREAM_SATURATED`, `RATE_LIMITER_UNAVAILABLE` (limiter failing, fail-closed), `CONFIG_UNAVAILABLE` (database down and the tenant not cached; at once while the `config-db` breaker is open, `Retry-After` = when it probes) |

Upstream responses, including upstream 4xx/5xx, pass through unchanged.

## Audit log

One JSON line per request on stdout (logger `AUDIT`), written by a background thread so logging never
blocks a request:

```json
{"requestId":"4f6c...","traceId":"4bf92f35...","spanId":"a3ce929d...","tenantId":"acme","method":"GET",
 "path":"/orders/42","routeId":"orders","upstream":"http://echo:8080/canary","decision":"ALLOW",
 "reason":null,"detail":null,"upstreamStatus":200,"upstreamSpans":["00f067aa..."],"status":200,"latencyMs":3.4}
```

`decision` is the policy decision (`ALLOW` / `DENY`); `reason` the error code if the request failed;
`upstream` the variant that served it; `detail` an internal diagnostic (e.g. `auth:EXPIRED`) never sent
to the client. If the writer falls behind and its queue (10,000) fills, entries are dropped and counted.

**Tracing (W3C Trace Context, no library).** The gateway joins the client's `traceparent` (or starts a
trace) and is one span in it (`spanId`). Every upstream call is a child span with its own id, sent
upstream as `traceparent` (with the client's `tracestate`) and listed in `upstreamSpans` in the order
sent, so a retry or a hedge shows up as a separate span. `gateway.continue-client-traces: false` starts
a new trace for every request instead.

## Metrics

`:8081/actuator/prometheus`; probes at `:8081/actuator/health/liveness` and `/readiness`.

| Metric | Signal |
|---|---|
| `gateway_requests_seconds{route,decision,reason,status}` | latency histogram, traffic, errors (no tenant/path/IP labels: unbounded cardinality; per-tenant analysis uses the audit log) |
| `httpcomponents_httpclient_pool_*` | upstream connection pool: leased, available, pending |
| `gateway_upstream_connections_opened_total` | new TCP connections to upstreams. Divided by forwarded requests it is the churn ratio: near 0 when keep-alive works, near 1 when every request opens a connection (and leaves a port in `TIME_WAIT`) |
| `gateway_circuit_state{name}` | 0 closed, 1 open, 2 half-open (per upstream, `rate-limiter`, and `config-db` around the database reads on the request path) |
| `gateway_upstream_fallbacks_total{route}` | requests sent to another upstream of the route because the picked one's breaker was open |
| `gateway_upstream_retries_total{route}`, `gateway_upstream_retries_denied_total{route}` | retries after a connect failure or timeout; retries refused because the retry budget was spent |
| `gateway_upstream_hedges_total{route}`, `..._hedges_won_total`, `..._hedges_denied_total` | second copies sent after `hedgeAfterMs`; how many answered first (the benefit); not sent (budget spent or breaker open) |
| `gateway_audit_queue_size`, `gateway_audit_dropped_total` | audit back-pressure (alert on dropped > 0) |
| `gateway_auth_revocation_unavailable_total` | revocation checks that could not reach the database; the token was served (fail open) |

Alerting rules for Prometheus are in [`ops/prometheus/gateway-rules.yml`](ops/prometheus/gateway-rules.yml)
(`promtool check rules` clean). SLO: 99.9% of requests without a 5xx over 30 days, alerted with
multi-window, multi-burn-rate rules (Google SRE Workbook): page when the budget burns at 14.4x (1h and
5m windows, 2% of the monthly budget in an hour), ticket at 6x (6h and 30m). Both windows must agree, so
an alert fires fast on a real outage and clears soon after it ends. Plus p99 latency, open breakers, pool
waits, connection churn, retry budget exhausted and audit entries dropped. A test checks that every
metric the rules use is exported, so a renamed metric fails the build instead of silently disabling an
alert.

## Configuration

All settings are in [application.yml](src/main/resources/application.yml) under `gateway.*`, with
comments. Environment variables:

| Variable | Default | |
|---|---|---|
| `GATEWAY_OPS_KEY_SHA256` | none | Hex SHA-256 of the bootstrap user `ops`'s key; it cannot log in if unset (on App Platform: a SECRET set by `do-app.sh`) |
| `GATEWAY_JWT_PRIVATE_KEY` | none | PKCS#8 PEM (headers and line breaks optional); ephemeral key if unset, so tokens die with the instance (on App Platform: a SECRET set by `do-app.sh`) |
| `gateway.ip-rate-limit` (yml) | 1200/min, burst 100 | Per client address, before authentication; `requests-per-minute: 0` turns it off |
| `gateway.trusted-proxies` (yml) | none | Load balancers (IPs or CIDRs) whose `X-Forwarded-For` is believed (empty on App Platform for now: see known gaps) |
| `gateway.continue-client-traces` (yml) | true | Join a valid client `traceparent`; `false` = every request starts a new trace at the gateway |
| `gateway.config-db-circuit-breaker` (yml) | 50% of the last 10 reads (at least 5), open 5s | Breaker around the database reads on the request path (tenants, token revocations); `spring.datasource.hikari.connection-timeout` (2000 ms, Hikari's default is 30 s) bounds each read before it opens |
| `GATEWAY_DB_URL` | `jdbc:h2:file:./data/gateway` | Config database; any JDBC URL (add the driver) |
| `GATEWAY_DB_USER`, `GATEWAY_DB_PASSWORD` | `sa`, empty | Its credentials |
| `GATEWAY_DB_PLATFORM` | `h2` | Runs `schema-<platform>.sql` at startup (see design decisions §4 for Postgres/MySQL) |

Key defaults: upstream timeout 10s; body limit 10MB; config refresh 5s; rate limits in memory, failing open;
breaker trips at 50% failures over the last 20 calls (min 10), open 10s, 3 probes; pool 100 per
upstream / 500 total, idle timeout 60s.

## Tests

`./mvnw verify` runs unit tests, integration tests (real HTTP, in-memory H2; no Docker needed) and a
coverage gate (≥ 80% of lines).

* **Unit:** `TokenServiceTest` (expired, wrong key, `alg:none`, HS256 algorithm confusion, bad claims,
  issuance rules, `jti` required), `SessionServiceTest` (one role per session, role removed after
  login, tenant and session tokens not interchangeable), `PermissionInterceptorTest`,
  `AdminControllerPermissionsTest` (every admin endpoint declares a permission), `RbacAuthorizerTest` (only the active role counts, custom roles, built-ins cannot be shadowed), `RbacBoundaryTest` (the gateway uses only the RBAC API),
  `PolicyEngineTest` (allowlist, segment boundaries, scopes, action by method, role revoked, quota, default burst, rejected
  requests not consuming quota, route limit last), `CircuitBreakerTest` (every state transition, stale
  results, probe slots), `AliasTableTest`, `RendezvousHashTest`, `TraceContextTest`, `SingleFlightCacheTest`,
  `ConcurrencyLimiterTest`, `ConfigStoreTest`, `AsyncAuditSinkTest`, `PathsTest`, `UpstreamUrlValidatorTest`,
  header filtering (`UpstreamProxyHeadersTest`).
* **Integration:** `GatewayIT` sends real HTTP to the running gateway with WireMock as the upstream:
  faithful proxying, every rejection (each asserts the upstream received nothing and the audit line
  says `DENY` with the right reason), admin login and RBAC (one active role per session), a revoked token refused on its next request, per-user limits that leave the tenant quota alone; `IpRateLimitIT` an address flood stopped before authentication, `HedgingBudgetIT` hedges stopping when the retry budget is spent, a tenant at its concurrency limit refused while its neighbour is served, data-plane RBAC (reader cannot delete, revoked role), the RBAC API (custom role lifecycle,
  built-ins read-only, management permissions only in built-in roles), runtime route changes, weighted split, a sticky canary (each tenant on one upstream), hedging (a slow call cut short by a hedge whose loser is cancelled; no hedge for a POST or a fast call), 502/504, the circuit
  breaker opening and recovering, a failing canary falling back to stable, a GET retried after a reset connection while a POST is not, pool saturation, body limits, metrics. `ClientIpResolverTest` (forged XFF ignored, trusted-proxy chains, CIDR, no DNS), `InMemoryRateLimiterTest` covers
  the in-memory token bucket (burst, refill rate, capacity cap, 300 concurrent requests against a bucket of 50);
  `RetryBudgetTest` (retries ≈ 10% of requests even when all fail, full-jitter bounds), `CircuitBreakingRateLimiterTest` the breaker and fail-open/closed around it, with a store that can be made to fail.
  `ConfigStoreIT` runs two config stores against one database (propagation, surviving a wiped database, create-or-replace, prefix uniqueness).

CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push and builds the Docker image. Deployment is
separate: App Platform builds from `main` itself on every merge (`deploy/do-app.yaml`).

## A real upstream (local or on DigitalOcean)

[upstream-app/](upstream-app/README.md) is a small Go service to put behind the gateway: it echoes
requests, waits as long as asked (`/delay/{ms}`, `/random-delay?min=&max=`), answers any status code,
and fails a share of requests on purpose (`FAIL_PCT`): a stable one and a canary that fails 60% of
requests. Locally:

```bash
docker compose -f upstream-app/docker-compose.yml up -d --build   # gateway :9180, stable :9201, canary :9202
./scripts/demo-do-upstream.sh
```

On DigitalOcean the gateway and both upstreams run as one app: see the next section.

The demo shows: the headers the upstream receives (gateway identity headers and `traceparent`, no
`Authorization`, no client-spoofed `X-Tenant-Id`); a 90/10 canary whose breaker opens and falls back to
stable; a sticky canary; a 504 after a timeout and its retry; hedging; an upstream 418 passed through;
and policy rejections.

## Deploy to DigitalOcean App Platform

[deploy/do-app.yaml](deploy/do-app.yaml) is one App Platform app with three components, each built by
App Platform from its Dockerfile (nothing is built or pushed from your machine):

| Component | Built from | Reachable |
|---|---|---|
| `pfx-multi-tenant-gateway` | `Dockerfile` | public HTTPS on the app's URL (port 8080: data plane + admin API); 8081 (health, metrics) inside the app only |
| `stable` | `upstream-app/Dockerfile`, `VERSION=stable` | inside the app only, as `http://stable:8080` |
| `canary` | `upstream-app/Dockerfile`, `VERSION=canary`, `FAIL_PCT=60` | inside the app only, as `http://canary:8080` |

The component names match the docker-compose service names, so the demo's default upstream URLs work
unchanged. App Platform's health check is the gateway's readiness endpoint on 8081. Every merge to `main`
redeploys (`deploy_on_push`).

```bash
brew install doctl && doctl auth init   # once: a DigitalOcean API token that may read and write apps
./scripts/do-app.sh validate            # fills in the secrets, validates the spec, changes nothing
./scripts/do-app.sh deploy              # creates the app, or updates it to the spec; waits until live
./scripts/do-app.sh demo                # scripts/demo-do-upstream.sh against the deployed gateway
./scripts/do-app.sh login               # an admin session token, for your own curl calls
./scripts/do-app.sh logs                # follow the gateway's log (one JSON audit line per request)
```

The repository is private: connect DigitalOcean's GitHub app to it first (Apps > Create App > GitHub).

**Secrets.** `do-app.sh` keeps them in `~/.config/pfx-gateway/` (mode 600, created on the first
`validate`/`deploy`, never committed): `ops-key` is operator `ops`'s key (the app stores only its SHA-256
in `GATEWAY_OPS_KEY_SHA256`), `jwt-private-key.pem` signs tokens (`GATEWAY_JWT_PRIVATE_KEY`, so tokens
survive a redeploy). Both are SECRET variables in the app. `local-ops-key` does not work there.

**Testing the deployed gateway with the scripts.** The management port is internal on App Platform, so
the scripts take `MANAGEMENT=` (empty) to skip the readiness check and the metrics; `do-app.sh demo`
sets it. For load tests see [Against the App Platform deployment](#against-the-app-platform-deployment).

**Things that differ from local runs:**

- **The config starts empty after every deploy.** It is the H2 file inside the gateway's container; the
  scripts recreate their tenants and routes each run. For data that must last, use a managed Postgres
  (`GATEWAY_DB_URL`, see design decisions §4).
- **App Platform rewrites an upstream 502/503 to 504** on the way to the client and keeps the original
  status in `X-Do-Orig-Status`. The gateway and its audit log see the real status; the demo prints both
  (`503(shown as 504)`). 418, 500 and other codes pass through unchanged.
- **The gateway sees the CDN's address, not the client's.** Requests arrive through App Platform's edge
  (Cloudflare), whose addresses are not in `gateway.trusted-proxies`, so `X-Forwarded-For`, the audit
  `clientIp` and the per-address rate limit use the edge node's address: unrelated clients can share a
  bucket. Trusting the edge's address ranges would fix it; not done yet.
- **One instance.** Rate limits and the H2 config are per instance; raise `instance_count` only together
  with a shared database.

## Load testing

Vegeta step load tests (open model: constant arrival rate), results and analysis in
[loadtest-results/REPORT.md](loadtest-results/REPORT.md):

- **Test 1**, an upstream that answers at once: 20,000 requests/s at p99 4.7 ms on a laptop (CPU-bound,
  ~0.3 ms per request); the defaults stop at 4,000.
- **Test 2**, an upstream that answers in 100 ms – 5 s: capacity is requests in flight (rate × 2.55 s).
  The defaults allow 20 requests/s (100 pooled connections per upstream); sized by Little's law (pool,
  Tomcat `max-connections`) 5,000 requests/s at the upstream's own p99, then memory (~145 KB per request
  in flight).

```bash
brew install vegeta
LT_POOL_PER_ROUTE=400 docker compose -f ops/loadtest/docker-compose.yml up -d --build   # ports 9080/9081, upstream 9090
RATES="4000 8000 12000 16000 20000" ./scripts/loadtest.sh                              # stops at the first step missing the SLO
docker compose -f ops/loadtest/docker-compose.yml down
```

The script creates its own tenants, route and tokens, warms up, loads the upstream directly once as a
baseline, and stops at the first step that misses the SLO (`P99_MS`, `SUCCESS_MIN`). The stack turns off
the per-address limit (one load generator would otherwise get 20 requests/s). `LT_POOL_PER_ROUTE` sets the
upstream pool (default 100 per upstream), and `MAX_CONNECTIONS` caps the load generator's connections so it
does not run out of local ports itself. For test 2: `LT_POOL_TOTAL`, `LT_TOMCAT_MAX_CONNECTIONS` and
`LT_JAVA_OPTS` set the pool total, Tomcat's connection limit and JVM flags; the `slow-upstream` service
(port 9091) delays each response by 100 ms – 5 s, and `TIMEOUT` raises the client's timeout.

Settings of `scripts/loadtest.sh` (environment variables; the full list is at the top of the script):

| Variable | Default | |
|---|---|---|
| `RATES` | `500 1000 2000 4000 8000 12000` | requests/s, one step each; the run stops at the first step missing the SLO |
| `DURATION`, `WARMUP` | `30s`, `20s` | length of each step, and of the warm-up at the first rate |
| `P99_MS`, `SUCCESS_MIN` | `50`, `0.999` | the SLO |
| `GATEWAY` | `http://localhost:9080` | the gateway's data plane and admin API |
| `MANAGEMENT` | `http://localhost:9081` | the gateway's management port, for the readiness check and the pool, audit and breaker figures; **empty** = not reachable (App Platform): those are skipped and shown as `-` |
| `OPS_USER`, `OPS_KEY` | `ops`, `local-ops-key` | operator used to create the test tenants, route and tokens |
| `UPSTREAM` | `http://localhost:9090` | the upstream as the load generator reaches it (for the baseline) |
| `UPSTREAM_IN_DOCKER` | `http://upstream:80` | the upstream as the gateway reaches it (the test route's target) |
| `BASELINE` | `1` | also load the upstream directly once; `0` when the load generator cannot reach it |
| `TENANTS` | `4` | test tenants; tokens rotate across them |
| `CONTAINER` | `gateway-loadtest-gateway-1` | Docker container sampled for `gw-cpu`; empty = skip |
| `TIMEOUT`, `MAX_CONNECTIONS` | `5s`, `2000` | per-request timeout; cap on the load generator's connections |

Each run writes `loadtest-results/<time>/` (vegeta results, per-step reports, `summary.txt`); only
`REPORT.md` is kept in the repository.

### Against the App Platform deployment

```bash
GATEWAY=$(./scripts/do-app.sh url) MANAGEMENT= OPS_KEY=$(cat ~/.config/pfx-gateway/ops-key) \
UPSTREAM_IN_DOCKER=http://stable:8080 BASELINE=0 CONTAINER= \
RATES="10 15" DURATION=10s WARMUP=3s P99_MS=2000 ./scripts/loadtest.sh
```

`MANAGEMENT=` because the management port is internal (no pool, audit or breaker figures; read the
gateway's log with `./scripts/do-app.sh logs`); `UPSTREAM_IN_DOCKER=http://stable:8080` routes the test
to the `stable` component; `BASELINE=0` and `CONTAINER=` because neither the upstream nor the container
is reachable from outside the app. The test tenants and route live only until the next deploy.

What limits it there (measured from a laptop: 10 requests/s at p99 99 ms; at 50 requests/s, 4 of 500
answered 429):

- **The per-address limit, 20 requests/s per address.** The gateway sees App Platform's edge (Cloudflare)
  addresses, not the load generator's, and requests from one machine mostly arrive through one edge
  node, so above ~20 requests/s they get 429 `IP_RATE_LIMITED`. Keep `RATES` at or below about 15, or
  for a real load test set `GATEWAY_IPRATELIMIT_REQUESTSPERMINUTE` to `0` in `deploy/do-app.yaml`,
  deploy, and put it back afterwards (the limit is the only per-address protection before
  authentication).
- **The internet path.** Latency includes the trip to the `sfo` region and App Platform's edge; run the
  load generator on a Droplet in `sfo` for numbers about the gateway rather than the network, and set
  `P99_MS` accordingly.
- **One small instance** (`apps-s-1vcpu-2gb`, one shared vCPU) and an upstream that echoes JSON rather
  than answering `ok`: not comparable with the figures in REPORT.md.

## Security assumptions and known gaps

**Assumptions**

* TLS terminates in front of the gateway (load balancer / ingress); the gateway speaks plain HTTP.
* Upstreams are reachable only through the gateway (network policy) and trust `X-Tenant-Id` because
  the gateway always overwrites it.
* The signing key and admin user keys come from a secret manager and are the same on every instance.
* The config database is embedded (demo) or a shared database on a private network, with backups.
* The client address is the TCP peer, unless the peer is listed in `gateway.trusted-proxies`; only then
  is its `X-Forwarded-For` used (from the right, skipping trusted hops). A client's own
  `X-Forwarded-For` is never believed.

**Known gaps**

| Gap | Impact | Next step |
|---|---|---|
| Token revocation fails open, and lives in the config database | While the database is down, a revoked token an instance has never checked is served (counted, alerted); a database that loses its data forgets its revocations | Fail closed per tenant (a setting), or replicate revocations into the snapshot; backups |
| Spans are identified, not exported | Trace and span ids reach upstreams and the audit log, but no span (with timings) is sent to a collector; new traces are always sampled | The OpenTelemetry SDK with an OTLP exporter and a ratio sampler (needs dependency approval); the ids stay the same |
| Until the `config-db` breaker opens, reads wait for the database | The first few requests that miss the cache during an outage each wait up to the connection timeout (2s) before failing; after that they fail at once | Lower timeouts, or a health probe that opens the breaker before requests see the outage |
| Config propagates by polling | A change made through another instance (sharing one database) applies within ~5s | Push invalidation (Postgres `LISTEN/NOTIFY`), polling kept as a safety net |
| The per-address limit is per instance and coarse | Many addresses (a botnet) each stay under it; an office behind one IP shares one bucket | Behavioural signals (bot detection); a shared store for several instances |
| Circuit breakers are per upstream and per instance | One tenant can trip a shared upstream's breaker for everyone | Per-(tenant, upstream) error budgets; outlier ejection |
| The bulkhead is per instance and refuses rather than queues | Behind N instances a tenant can hold N × its limit; a burst just over the limit gets 429s | A short bounded queue; adaptive limits (e.g. by observed latency) |
| Rate limits are per instance (buckets in memory) | With N instances a tenant gets up to N× its quota; the demo runs one instance | Another `RateLimiter` implementation on a shared store (Redis + Lua, as steps 15–18), behind a breaker |
| A tenant an instance has never seen cannot be served while the database is down | 503 `CONFIG_UNAVAILABLE` for that tenant on that instance; tenants already cached keep working (stale if error) | Warm the cache at startup from the database |
| RBAC is method-level; a `_method` form/query parameter is passed through | The gateway cannot enforce "only your own orders", and an upstream honouring `_method` could run another action | Resource-level checks in the service (it gets `X-User-Id`, `X-Role`); upstreams ignore `_method` |
| Upstream DNS checked only when a route is saved | DNS rebinding could later point a host at a blocked address | Resolve and check per connection |
| Token issuance checks role assignments in this instance's snapshot | Assigning a role through one instance and issuing a token through another within ~5s can fail with 400 "does not hold role" | Retry, or issue on the same instance; pub/sub invalidation |
| Operators and their roles live in config | Adding an operator needs a config change and restart (custom roles and tenant users do not) | Operators from SSO with groups mapped to roles |
| Sessions cannot be revoked one by one | A leaked session works until it expires (≤ 1h) unless the user's role is removed (login guessing is throttled per address) | Session `jti` deny-list |
| Behind App Platform the client address is the CDN edge's | `X-Forwarded-For`, the audit `clientIp` and the per-address limit see Cloudflare edge addresses (not in `trusted-proxies`): unrelated clients can share one bucket, one client can spread over several | Add the edge's address ranges to `gateway.trusted-proxies` (and keep them current) |
| Admin API on the same port as the data plane | Reachable from wherever tenants are | Serve it on an internal-only port or network |
| No mTLS to upstreams | Upstreams must trust the network | mTLS / workload identities |
| Only bodiless idempotent requests are retried or hedged; the hedge delay is fixed per route | A POST that failed to connect is not retried even though it never reached the upstream; `hedgeAfterMs` must be tuned by hand | Retry any method on a proven connect failure; derive the hedge delay from the route's measured p95 |
| No WebSocket / HTTP upgrade, no per-chunk flush | Not suitable for WebSocket or SSE traffic | A dedicated streaming path |
| Audit entries can be dropped when the queue is full | Gap in the audit trail under extreme load (counted, alertable) | Kafka producer with local buffering |
| Requests rejected by Tomcat itself (e.g. `%2F` in the path, malformed request line) | Plain 400 without an audit entry | Tomcat access log, or an error valve that emits the same entry |

## How this was built (one concept per commit)

| Commit | Step |
|---|---|
| step 1 | Reverse proxy with a runtime route table |
| step 2 | Authentication: JWT for tenants, key for admins |
| step 3 | Per-tenant policy: path allowlist, scopes, quota |
| step 4 | Audit log and centralised error handling |
| step 5 | Docs, Docker compose demo (the MVP is complete here) |
| step 6 | Rate limiting in Redis: shared, sliding window, two dimensions |
| step 7 | Circuit breakers for upstreams and for Redis |
| step 8 | Apache HttpClient 5 connection pool with explicit settings |
| step 9 | Async audit log and Prometheus metrics |
| step 10 | Weighted upstreams and canary routing (Vose alias method) |
| step 11 | Tenants and routes persisted in Redis, served from a snapshot |
| step 12 | Hardening: request body limit, upstream URL validation |
| step 13 | Documentation |
| step 14 | Fix: config generation token survives Redis data loss; CI timing margin |
| step 15 | Rate limiting as a token bucket: one rule, one key per tenant |
| step 16 | RBAC for the admin API: user → roles → permissions, login with one active role, fail-closed permission checks |
| step 17 | RBAC as a module (`Authorizer`) for the data plane too: scope = which service, role = which action |
| step 18 | RBAC management API: custom roles (service permissions only) and tenant users' assignments in Redis, built-ins read-only |
| step 19 | Rate limiting behind a `RateLimiter` interface: token buckets in memory (Caffeine) instead of Redis, wrapped in a circuit-breaking, fail-open decorator |
| step 20 | Upstream fallback: if the picked upstream's breaker is open, the route's heaviest healthy upstream serves the request |
| step 21 | Config in a database (JDBC, portable SQL, H2 by default) instead of Redis; Redis and Testcontainers gone |
| step 22 | Tenants loaded on demand through a single flight (`SingleFlightCache`): one database read per tenant however many requests arrive at once |
| step 23 | Multi-dimensional rate limiting: per client address before authentication (trusted-proxy `X-Forwarded-For`), per user, per tenant |
| step 24 | Bulkhead per tenant: at most N requests in flight (default 50), refused at once beyond it |
| step 25 | Retries for idempotent requests after a connect failure or timeout: exponential backoff with full jitter, a retry budget, another upstream preferred |
| step 26 | Connection-churn metric (new upstream connections) and Prometheus alerting rules: SLO burn rate (multi-window), latency, saturation |
| step 27 | Token revocation by `jti`: a `revoked_tokens` table in the config database, read per token through a single-flight cache, fail open |
| step 28 | Sticky canary: `"sticky": true` on a route picks the upstream by tenant (weighted rendezvous hashing), so a tenant always sees the same version |
| step 29 | Hedging: `hedgeAfterMs` on a route sends a second copy of a slow idempotent request, first answer wins, the loser is cancelled; hedges spend the retry budget |
| step 30 | Circuit breaker around the config database reads on the request path (`config-db`) and a 2s connection timeout: an outage fails fast instead of every cache miss waiting |
| step 31 | Tracing with W3C Trace Context and no library: the gateway joins or starts the trace, each upstream call (retries, hedges) is a child span; ids in the audit entry |
| step 32 | Rate limit per route (`requestsPerMinute`, `burst`): all tenants together, checked last so its shared bucket is spent only by requests every other check allowed |
| step 33 | Design brief (`docs/design-brief.pdf`): every component, then the end-to-end workflow |
| step 34 | Load-test kit (vegeta, no-op nginx upstream) and stress test 1: 20,000 requests/s at p99 4.7 ms on a laptop; the defaults stop at 4,000 (pool per upstream, breaker window, client ports) |
| step 35 | Stress test 2: an upstream that answers in 100 ms – 5 s; bottlenecks in order (pool per upstream, Docker port forwarding, Tomcat `max-connections`, memory per request in flight), 20 → 5,000 requests/s by configuration |
| step 36 | Documentation checked against the assignment: request-flow diagram fixed (a merged node, missing branches), sequence diagrams render again, README and threat model corrected |
| step 37 | A real upstream: `upstream-app` (Go, standard library: echo, delays, status codes, injected failures), App Platform specs for a stable and a canary app, a demo script, and a CI job |
| step 38 | Only the stress test report (`loadtest-results/REPORT.md`) stays in the repository; the per-run result directories stay on the machine that ran them |
| step 39 | DigitalOcean App Platform: one app (`deploy/do-app.yaml`) with the gateway and internal stable/canary upstreams, health check on the management port, secrets kept outside the repo (`scripts/do-app.sh`); the demo and load-test scripts run without the management port |
