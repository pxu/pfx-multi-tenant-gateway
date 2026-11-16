# Multi-Tenant API Gateway

A reverse proxy in front of internal services, shared by many tenants. For every request it
authenticates the caller (RS256 JWT), applies the tenant's policy (path allowlist, scopes, RBAC, rate
limits, concurrency), and proxies to a weighted or canary upstream with circuit breakers, retries and
hedging. Every request produces exactly one audit entry.

Java 21 · Spring Boot 3.5 (MVC on virtual threads) · Apache HttpClient 5 · Caffeine · JDBC + H2 · Nimbus JOSE

| Document | |
|---|---|
| [demo/Multi-Tenant-API-Gateway-Design.pdf](demo/Multi-Tenant-API-Gateway-Design.pdf) | Design overview: architecture, data model, data flow, audit samples, load test, metrics, known gaps (source: [docs/demo-overview.md](docs/demo-overview.md)) |
| [docs/architecture.md](docs/architecture.md) | Request flow with every failure branch, components, sequences |
| [docs/threat-model.md](docs/threat-model.md) | What an authenticated-but-malicious tenant can and cannot do |
| [docs/design-decisions.md](docs/design-decisions.md) | Trade-offs and what was left out |
| [loadtest-results/REPORT.md](loadtest-results/REPORT.md) | Stress tests: 20,000 requests/s at p99 4.7 ms; bottlenecks with a slow upstream |

Live deployment (DigitalOcean App Platform): https://pfx-multi-tenant-gateway-5drq9.ondigitalocean.app/
(every request needs a token or an admin session; a bare browser request gets 401).

## Request flow

```
client ─> AuditFilter ─> IpRateLimitFilter ─> AuthFilter ─> ProxyController ─────────────> UpstreamProxy ─> upstream
          request id     429 per address      /admin/**:     1. tenant enabled      (403)    HttpClient 5 pool;
          canonical      (before auth)        session        2. longest-prefix route (404)    strips Authorization
          path (400)                          else JWT,      3. body size           (413)    and client identity
          one audit                           not revoked    4. allowlist, scope,   (403)    headers, sets
          entry                               (401)             RBAC by method      (403)    X-Tenant-Id, X-User-Id,
          (finally)                                          5. user/tenant/route   (429)    X-Role, traceparent
                                                                buckets, bulkhead            (502 / 503 / 504)
                                                             6. pick upstream, breaker,
                                                                retry or hedge      (503)
```

Every stage passes or throws a `GatewayException`, rendered once as RFC 9457 `application/problem+json`,
so a rejected request never reaches an upstream. Routes, RBAC data and rate-limit buckets are in
memory; tenants and token revocations come from the config database through a 5 s single-flight cache.

## Setup

**Local (Docker):**

```bash
docker compose up -d --build   # gateway :8080 (data plane + admin API), :8081 (health, metrics), echo upstream
./scripts/demo.sh              # creates a tenant, users, a route and a token; shows 200/401/403/400/404/429
```

Operator `ops` has the key `local-ops-key` in the compose stack.

**Without Docker** (JDK 21; config in an embedded H2 file under `./data/`):

```bash
GATEWAY_OPS_KEY_SHA256=$(printf %s local-ops-key | shasum -a 256 | cut -d' ' -f1) ./mvnw spring-boot:run
```

**With the Go upstream** ([upstream-app/](upstream-app/README.md): echo, delays, any status, a canary
that fails 60% of requests):

```bash
docker compose -f upstream-app/docker-compose.yml up -d --build   # gateway :9180, stable :9201, canary :9202
./scripts/demo-do-upstream.sh   # identity headers, canary fallback, sticky canary, timeout + retry, hedging
```

**DigitalOcean App Platform:** see [below](#deploy-to-digitalocean-app-platform).

**Tests:** `./mvnw verify` runs unit and integration tests (real HTTP with WireMock upstreams, in-memory
H2, no Docker) and a coverage gate (≥ 80% of lines). CI (`.github/workflows/ci.yml`) runs it on every push.

## Token issuance

Two layers decide what a tenant token may do:

- **Scope = which services.** A token lists the services it may reach, at most the tenant's
  `grantableScopes`. Each route belongs to a `service` (default: the route id).
- **RBAC = which actions.** User → roles → permissions (`resource:action`). A token acts as **one** of
  the user's roles. The action comes from the HTTP method: GET/HEAD/OPTIONS = `read`, DELETE =
  `delete`, anything else = `write`. The user must still hold the role on every request.

Operators log in to the admin API with a key and one role (`POST /admin/login`), and get a session
token; tenant tokens never work on the admin API, nor sessions on the data plane.

```bash
G=localhost:8080
SESSION=$(curl -s -X POST $G/admin/login -H 'Content-Type: application/json' \
  -d '{"user": "ops", "key": "local-ops-key", "role": "admin"}' | jq -r .token)
A=(-H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json')

# 1. A tenant: quota, allowed paths, services its tokens may name
curl -X POST $G/admin/tenants "${A[@]}" -d '{"id": "acme", "name": "Acme", "requestsPerMinute": 600,
  "burst": 20, "allowedPathPrefixes": ["/orders"], "grantableScopes": ["orders"]}'

# 2. A tenant user and their role
curl -X PUT $G/admin/rbac/tenants/acme/users/rita "${A[@]}" -d '{"roles": ["orders-reader"]}'

# 3. A route for service "orders", 90/10 canary
curl -X POST $G/admin/routes "${A[@]}" -d '{"id": "orders", "pathPrefix": "/orders",
  "upstreams": [{"url": "http://echo:8080", "weight": 90}, {"url": "http://echo:8080/canary", "weight": 10}]}'

# 4. A token for rita acting as orders-reader (TTL 900 s by default, at most 3600)
TOKEN=$(curl -s -X POST $G/admin/tenants/acme/tokens "${A[@]}" \
  -d '{"user": "rita", "role": "orders-reader", "scopes": ["orders"]}' | jq -r .token)
# or: ./scripts/issue-token.sh acme rita orders-reader orders

curl $G/orders/42 -H "Authorization: Bearer $TOKEN"              # 200
curl -X DELETE $G/orders/42 -H "Authorization: Bearer $TOKEN"    # 403 PERMISSION_DENIED (a reader)
```

The token is an RS256 JWT: `{"iss", "aud", "sub": "rita", "tid": "acme", "role": "orders-reader",
"scope": "orders", "exp", "jti"}`. Revoke one with `POST /admin/tokens/{tokenId}/revoke`.

| Built-in role (`application.yml`) | Permissions |
|---|---|
| `viewer` | `tenants:read`, `routes:read`, `rbac:read` |
| `operator` | `tenants:*`, `routes:*` (read, write) |
| `admin` | all of the above + `tokens:issue`, `tokens:revoke`, `rbac:write` |
| `orders-reader` / `orders-writer` | `orders:read` / `orders:read`, `orders:write`, `orders:delete` |

### Admin API

| Endpoint | Permission | Notes |
|---|---|---|
| `POST /admin/login` | none | `{user, key, role}` → session token (1 h) for that role |
| `/admin/tenants`, `/admin/tenants/{id}` | `tenants:read` / `write` | `requestsPerMinute`, `burst`, `userRequestsPerMinute`, `maxConcurrentRequests` (default 50), `allowedPathPrefixes`, `grantableScopes`, `disabled` |
| `POST /admin/tenants/{id}/tokens` | `tokens:issue` | `{user, role, scopes, ttlSeconds}` |
| `POST /admin/tokens/{tokenId}/revoke` | `tokens:revoke` | 401 on the token's next request; ≤ 5 s on other instances |
| `/admin/routes`, `/admin/routes/{id}` | `routes:read` / `write` | `pathPrefix`, `upstreams[url, weight]`, `service`, `sticky` (tenant always on one upstream), `hedgeAfterMs` (0 = off), `requestsPerMinute`/`burst` (route as a whole), `fallback` (default true) |
| `/admin/rbac/roles`, `/admin/rbac/roles/{role}` | `rbac:read` / `write` | Custom roles hold service permissions only; built-ins are read-only; an assigned role cannot be deleted (409) |
| `/admin/rbac/tenants/{t}/users/{u}` | `rbac:read` / `write` | `{"roles": [...]}` replaces a tenant user's roles |

## Errors, audit log, metrics

Errors are `application/problem+json` with a `code` and the `requestId`:

| Status | Codes |
|---|---|
| 400 | `BAD_PATH` (traversal, encoded slashes, ...), `VALIDATION_ERROR` |
| 401 | `UNAUTHENTICATED`, `ADMIN_UNAUTHORIZED` |
| 403 | `TENANT_DISABLED`, `PATH_NOT_ALLOWED`, `INSUFFICIENT_SCOPE`, `PERMISSION_DENIED` |
| 404 / 409 / 413 | `ROUTE_NOT_FOUND`, `NOT_FOUND` / `CONFLICT` / `PAYLOAD_TOO_LARGE` |
| 429 (+ `Retry-After`) | `IP_RATE_LIMITED`, `USER_RATE_LIMITED`, `RATE_LIMITED`, `ROUTE_RATE_LIMITED`, `CONCURRENCY_LIMITED` |
| 502 / 504 | `UPSTREAM_ERROR` / `UPSTREAM_TIMEOUT` |
| 503 | `CIRCUIT_OPEN`, `UPSTREAM_SATURATED`, `RATE_LIMITER_UNAVAILABLE`, `CONFIG_UNAVAILABLE` |

Upstream responses, 4xx and 5xx included, pass through unchanged.

**Audit log:** one JSON line per request on stdout (logger `AUDIT`), written by a background thread:
`requestId`, `traceId`, `spanId`, `tenantId`, `actor`, `role`, `clientIp`, `method`, `path`, `routeId`,
`upstream`, `decision`, `reason`, `detail` (internal, e.g. `auth:MISSING_TOKEN`), `upstreamStatus`,
`upstreamSpans`, `status`, `latencyMs`. Tracing follows W3C Trace Context (no library): each upstream
call, retry and hedge is a child span.

**Metrics:** `:8081/actuator/prometheus`; probes at `:8081/actuator/health/liveness` and `/readiness`.

| Metric | |
|---|---|
| `gateway_requests_seconds{route,decision,reason,status}` | latency, traffic, errors (never tagged by tenant, path or IP) |
| `gateway_circuit_state{name}` | 0 closed, 1 open, 2 half-open: per upstream, `rate-limiter`, `config-db` |
| `gateway_upstream_{retries,retries_denied,hedges,hedges_won,hedges_denied,fallbacks}_total{route}` | resilience at work |
| `gateway_upstream_connections_opened_total`, `httpcomponents_httpclient_pool_*` | connection churn and pool saturation |
| `gateway_audit_dropped_total`, `gateway_auth_revocation_unavailable_total` | audit back-pressure; revocation checks that failed open |

Alerting rules (SLO burn rate, multi-window; latency; saturation) are in
[ops/prometheus/gateway-rules.yml](ops/prometheus/gateway-rules.yml); a test checks every metric they
use is exported.

## Configuration

All settings are in [application.yml](src/main/resources/application.yml) under `gateway.*`, with comments.

| Setting | Default | |
|---|---|---|
| `GATEWAY_OPS_KEY_SHA256` | none | SHA-256 (hex) of operator `ops`'s key; `ops` cannot log in without it |
| `GATEWAY_JWT_PRIVATE_KEY` | ephemeral | PKCS#8 RSA key; set the same one on every instance, or tokens die on restart |
| `GATEWAY_DB_URL`, `_USER`, `_PASSWORD`, `_PLATFORM` | `jdbc:h2:file:./data/gateway`, `sa`, empty, `h2` | Config database; any JDBC URL (add its driver and `schema-<platform>.sql`) |
| `gateway.ip-rate-limit` | 1200/min, burst 100 | Per client address, before auth; `requests-per-minute: 0` turns it off |
| `gateway.trusted-proxies` | none | Load balancers whose `X-Forwarded-For` is believed |
| `gateway.revocation-fail-open` | true | Revocation state unreadable: serve the token (`true`) or 503 (`false`) |
| `gateway.rate-limit-fail-open` | true | Rate limiter failing: serve without limits (`true`) or 503 (`false`) |
| `gateway.tenant-max-concurrent-requests` | 50 | Bulkhead per tenant |

Other defaults:

| Area | Default |
|---|---|
| Upstream timeouts | 2 s connect, 10 s response |
| Request body | 10 MB at most |
| Connection pool | 100 per upstream, 500 in total, idle timeout 60 s |
| Circuit breaker | opens at 50% failures over the last 20 calls, stays open 10 s |
| Retries | at most 2 attempts, within a budget of about 10% of requests |
| Config | refreshed every 5 s |

## Deploy to DigitalOcean App Platform

[deploy/do-app.yaml](deploy/do-app.yaml) is one app with three components, each built by App Platform
from its Dockerfile. Every merge to `main` redeploys it.

| Component | Reachable |
|---|---|
| `pfx-multi-tenant-gateway` | public HTTPS (port 8080); 8081 (health, metrics) inside the app only |
| `stable`, `canary` (`FAIL_PCT=60`) | inside the app only, as `http://stable:8080`, `http://canary:8080` |

```bash
brew install doctl && doctl auth init   # once
./scripts/do-app.sh deploy              # create or update the app; waits until live
./scripts/do-app.sh demo                # runs demo-do-upstream.sh against it
./scripts/do-app.sh login | logs | url  # admin session token; follow the audit log; the app URL
```

Secrets live in `~/.config/pfx-gateway/` (created on first use, never committed): `ops-key` (the app
stores only its SHA-256) and `jwt-private-key.pem`. Differences from local runs:

- The config is the H2 file in the container, so **it starts empty after every deploy**; the scripts
  recreate what they need.
- The management port is internal: the scripts take `MANAGEMENT=` (empty) and skip metrics.
- App Platform's edge shows an upstream 502/503 as 504 (the original is in `X-Do-Orig-Status`).
- One instance: rate limits and the H2 config are per instance.

## Load testing

```bash
brew install vegeta
LT_POOL_PER_ROUTE=400 docker compose -f ops/loadtest/docker-compose.yml up -d --build
RATES="4000 8000 12000 16000 20000" ./scripts/loadtest.sh   # stops at the first step missing the SLO
```

The script creates its own tenants, route and tokens, and writes each run to `loadtest-results/<time>/`.
Settings (`RATES`, `DURATION`, `P99_MS`, `GATEWAY`, `MANAGEMENT`, ...) are listed at the top of
[scripts/loadtest.sh](scripts/loadtest.sh). Against App Platform:

```bash
GATEWAY=$(./scripts/do-app.sh url) MANAGEMENT= OPS_KEY=$(cat ~/.config/pfx-gateway/ops-key) \
UPSTREAM_IN_DOCKER=http://stable:8080 BASELINE=0 CONTAINER= \
RATES="10 15" DURATION=10s WARMUP=3s P99_MS=2000 ./scripts/loadtest.sh
```

Keep it at or below about 15 requests/s there. Above about 20 requests/s from one machine the
per-address limit answers 429, because the gateway sees App Platform's edge addresses (see known gaps).

## Security assumptions and known gaps

The gateway treats every client, including a tenant with a valid token, as untrusted. It assumes:

| Assumption | If it does not hold |
|---|---|
| Upstreams are reachable only through the gateway, so they can trust `X-Tenant-Id`, `X-User-Id` and `X-Role` | A direct caller could claim any tenant or role |
| Upstreams ignore method overrides (`_method` in a form or query) | A reader could turn a `POST` into a delete; the gateway strips override headers but not parameters |
| TLS ends at the edge (load balancer); the gateway speaks plain HTTP | Tokens and admin sessions could be read in transit |
| The signing key and operator keys stay secret and are the same on every instance | Anyone holding the signing key can mint a token for any tenant |
| The config database is on a private network and written only by the gateway | Whoever writes to it controls routes, tenants and roles |
| `trusted-proxies` lists only real load balancers | A listed address could forge `X-Forwarded-For` |

**Known gaps:**

| Gap | Effect | Next step |
|---|---|---|
| Rate limits, bulkhead and breakers are per instance | With N instances a tenant gets up to N × its quota | A Redis `RateLimiter` (atomic Lua, keys per tenant) behind the existing breaker |
| Client address behind App Platform is the CDN edge's | The per-IP limit and audit `clientIp` see edge addresses | Add the edge's ranges to `gateway.trusted-proxies` |
| Token revocation is cached 5 s and fails open by default | A revoked token can work up to 5 s on another instance, or during a database outage | `gateway.revocation-fail-open: false`; push revocations into the snapshot |
| Breaker fallback can send all traffic to a canary | With stable's breaker open, a 90/10 route sends everything to the canary | `"fallback": false` on the route |
| Breakers are per upstream, not per tenant | One tenant can trip a shared upstream's breaker for everyone | Per-(tenant, upstream) error budgets |
| Upstream DNS checked only when a route is saved | DNS rebinding could later point a host at a blocked address | Check the resolved address per connection |
| No overall request deadline | Retries are bounded by attempts and timeouts, not a total budget | A per-request deadline |
| Config in the container on App Platform | Tenants and routes are lost on every redeploy | A managed Postgres (`GATEWAY_DB_URL`) |
| Admin API on the same port as the data plane | Reachable from wherever tenants are | An internal-only port or network |
| Spans are identified, not exported | Trace ids reach upstreams and the audit log, but no collector | OpenTelemetry SDK with OTLP |
| Audit entries can be dropped when the queue is full | A gap under extreme load (counted, alerted) | Kafka producer with local buffering |
| No WebSocket / SSE support | Not suitable for streaming upgrades | A dedicated streaming path |

The full analysis is in [docs/threat-model.md](docs/threat-model.md).
