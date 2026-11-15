# Design decisions

Each entry: the choice, why, what it costs, and what I would do next.

## 1. Spring MVC on virtual threads, hand-written proxy

**Choice:** blocking servlet stack with `spring.threads.virtual.enabled=true`; the pipeline is plain
sequential code in `ProxyController`, not Spring Cloud Gateway / WebFlux.

**Why:** a gateway is I/O-bound. Virtual threads give one cheap thread per request (Go's
goroutine-per-request model), so straightforward blocking code scales without callback chains. The
interesting part of this service is the auth / policy / proxy / audit logic, and straight-line code
keeps every step and failure branch visible and easy to test.

**Costs:** on JDK 21 a virtual thread that blocks while holding a `synchronized` monitor pins its
carrier thread (fixed in JDK 24, JEP 491), so locks here are `ReentrantLock` and are never held while
blocking. Virtual threads make waiting cheap but do not make upstreams faster: back-pressure comes from
connection-pool limits (503 when full), timeouts and circuit breakers. Spring Cloud Gateway would fit
better for very high connection counts, long-lived streams or WebSockets, or a team that already knows
Reactor.

## 2. Errors are exceptions, rendered in one place

Every stage rejects by throwing `GatewayException(code, detail, headers)`. The outermost filter
(`AuditFilter`) and the controller advice render it as RFC 9457 problem+json and mark the audit
record. One mechanism means a rejection cannot accidentally fall through to the proxy, every error
looks the same to clients, and every rejection is audited the same way. Exceptions are created without
stack traces: a 429 storm is normal traffic, not a bug. (Exceptions thrown inside a servlet filter
never reach `@RestControllerAdvice`; that is why the outermost filter also renders them.)

## 3. Tokens: RS256 JWT, verified locally

RS256 JWTs carrying `tid` (tenant), `sub` (the tenant's user), `role` (the one role it acts as) and
`scope` (the services it may reach), issued by the admin API. Verification needs no network
call. Asymmetric keys mean a verifier only needs the public key; with HS256 anything that can verify a
token can also mint one. Nimbus checks the signature with a key selector that accepts RS256 only, which
closes `alg:none` and algorithm confusion. TTL is capped at 1h.

Tokens can be revoked one by one by their `jti` (section 15). **Next:** moving issuance to its own
service so gateways hold only the public key.

## 4. Configuration: a database as source of truth, an immutable snapshot on the hot path

Tenants, routes, custom roles and role assignments live in a relational database (step 21; Redis
hashes in steps 11–20). Each instance holds an immutable snapshot of routes and RBAC data behind an
`AtomicReference`; a request does one volatile read for those: no lock, no I/O. Tenants are loaded on
demand instead (§14).

* **Transactional versioning.** Every write changes the data and replaces `config_version` with a new
  random token **in one transaction**, so whoever reads the new version reads the new data (with Redis
  this was "write data, then version"). Instances poll that one row every 5s ±20% jitter and reload only
  on change. A random token, not a counter: after a data loss a counter restarts at 1 and can collide
  with the version an instance already holds, so a real change would be skipped (regression-tested).
* **Constraints in the database.** A route's `path_prefix` is `UNIQUE` (two admins can no longer race
  past a check-then-write), and deleting a tenant deletes its role assignments (`ON DELETE CASCADE`).
* **Static stability.** If the database is unreachable, or its data is gone (missing version), the last
  snapshot keeps serving. An empty snapshot would 403/404 every request.
* **H2 by default, any JDBC database by configuration.** The demo runs one process with an embedded H2
  database in a file (`./data/gateway`, survives restarts), so there is nothing else to deploy.
  Switching to Postgres or MySQL is three things, no code:

  1. add the JDBC driver to the pom;
  2. set `GATEWAY_DB_URL` (and user, password);
  3. copy `schema-h2.sql` to `schema-postgresql.sql` / `schema-mysql.sql`, changing the large-text
     column type (`CLOB` → `TEXT` / `LONGTEXT`), and set `GATEWAY_DB_PLATFORM`.

  That works because the code keeps to portable SQL on purpose: plain SELECT/INSERT/UPDATE/DELETE with
  bound parameters; **no vendor upsert** (H2 `MERGE`, Postgres `ON CONFLICT`, MySQL `ON DUPLICATE KEY`):
  create-or-replace is "UPDATE, then INSERT if no row changed" in one transaction; JSON stored as text,
  no `JSONB`; `CREATE TABLE IF NOT EXISTS` and standard constraints. Only H2 is tested here.
* **One more seam: `ConfigRepository`.** `ConfigStore` (snapshot, refresh, error mapping) talks to an
  interface; `JdbcConfigRepository` is the JDBC implementation. A store that is not SQL at all (etcd, a
  config service) is another implementation, not a change to the snapshot logic.
* **Several instances** need one shared database (Postgres, MySQL) instead of an embedded H2 each; the
  version poll is then what makes them agree, as it did with Redis.

**Trade-off at scale:** a full snapshot would hold every tenant in every instance, so tenants are loaded
on demand through a single flight (§14). Routes stay in the snapshot: there are few of them, and
matching needs all of them at once.

**Next:** push instead of poll for a shared database (Postgres `LISTEN/NOTIFY`, or a message bus),
keeping the poll as the safety net.

## 5. Rate limiting: a token bucket in memory (Caffeine)

* **Token bucket:** each tenant has a bucket of `burst` tokens that refills at `requestsPerMinute`.
  A request takes one token. One rule gives both a sustained rate and a cap on how much may arrive at
  once, so a tenant cannot spend its whole minute in one second, and traffic stays smooth instead of
  "use the minute in 30 seconds, then get rejected for 30".

  | Option | Why not |
  |---|---|
  | Fixed window | 2× the limit can pass around a window boundary |
  | Sliding log | Exact, but stores one timestamp per request |
  | Sliding-window counter (steps 6–14) | Needs a second rule for bursts |
  | **Token bucket (chosen, step 15)** | Two numbers per tenant (`tokens`, last update) |

* **Where the buckets live (step 19): in this process**, a Caffeine cache of buckets keyed by tenant.
  The demo deploys as one process, and a quota check is a memory operation: no network
  round trip. **Cost:** with N instances behind a load balancer,
  a tenant gets up to N× its quota, because each instance counts on its own.
* **Open for extension:** `RateLimiter` is an interface; `InMemoryRateLimiter` is today's
  implementation, chosen in one bean method in `GatewayConfig`, and `PolicyEngine` sees only the
  interface. Several instances need a shared bucket: a Redis implementation (one atomic Lua script per
  request, as steps 15–18 had), plugged in at that one place.
* **Resilience is a decorator, independent of the store:** `CircuitBreakingRateLimiter` wraps whatever
  `RateLimiter` is configured with a circuit breaker and an explicit failure policy.
  * While the store keeps failing, the breaker opens and calls skip it, so a slow store (Redis timing
    out) does not add its timeout to every request; after the cool-down one probe decides.
  * **Fail open** (`rate-limit-fail-open: true`, the default): serve without a limit. Rate limiting
    exists to protect availability, so it should not cause an outage by itself.
  * **Fail closed** (`false`): 503 `RATE_LIMITER_UNAVAILABLE`, when unlimited traffic is the bigger risk.
  * A rejection (no tokens) is a successful call; only exceptions count as failures.

  With the in-memory store the delegate never fails and the breaker stays closed; the decorator is wired
  from the start, so a remote store can be swapped in without touching the policy. Tested with a store
  that can be made to fail.
* **Semantics:** a token bucket bounds the *rate*, not a count per calendar minute. Any 60s window can
  see up to `requestsPerMinute + burst`. That is right for protecting upstreams; billing-style quotas
  (exactly N per day) would want counters.
* **`burst` defaults to one second's worth** (`requestsPerMinute / 60`, at least 1) and may not exceed
  `requestsPerMinute`: a bucket the size of the whole minute is the classic misconfiguration that lets
  a tenant send its entire quota at once.
* **Atomic per tenant:** each bucket has its own `ReentrantLock` (not `synchronized`, for virtual
  threads), so concurrent requests cannot overshoot (tested with 300 concurrent requests against a
  bucket of 50), and tenants never wait for each other. A rejected request takes nothing.
* **Bounded memory:** a bucket untouched for a minute is full again (the burst is at most a minute's
  quota), so Caffeine evicts it after a minute without access, which changes nothing; the cache is
  also capped at 100,000 tenants.
* **Retry-After** is the exact time until the next token, `(1 − tokens) / rate`, rounded up to seconds.
* **Clock:** an injected `Clock` drives both the refill and Caffeine's eviction (its ticker), so tests
  move time by hand.

**Multi-dimensional (step 23).** One limit per identity misses half the problem: by address alone it
punishes an office behind one NAT, by user alone it misses many accounts working together. So three
dimensions, all on the same `RateLimiter` with prefixed keys:

| Dimension | Where | Key | Default | Why there |
|---|---|---|---|---|
| Client address | `IpRateLimitFilter`, **before authentication** | `ip:<address>` | 1200/min, burst 100 (generous) | The only dimension that covers unauthenticated traffic: a junk-token flood stops costing signature checks after its burst, and `/admin/login` key guessing is throttled |
| User | `PolicyEngine`, before the tenant quota | `user:<tenant>/<user>` | off; `Tenant.userRequestsPerMinute`, bucket of one second | One runaway user cannot spend the whole tenant's quota; checked first for exactly that reason |
| Tenant | `PolicyEngine` | `tenant:<id>` | the tenant's quota | The contract with the tenant |
| Route (step 32) | `PolicyEngine`, last | `route:<id>` | off; `Route.requestsPerMinute` / `burst` | The service owner's protection for the upstream: all tenants together. Last, because the bucket is shared: a request another limit rejects must not spend a token every tenant needs |

The address comes from `ClientIpResolver`: the TCP peer, unless the peer is a configured trusted proxy;
then `X-Forwarded-For` is walked from the right, skipping trusted hops (the left part is written by the
client and never believed). IP literals only, never resolved through DNS. The same address goes into
the audit entry (`clientIp`) and the `X-Forwarded-For` sent upstream.

The route limit is one bucket for everyone, so one busy tenant can use most of it; the tenant quota is
what stops that. A limit per (tenant, route) would be the next dimension if service owners need it.

**Next:** a shared store when running more than one instance; behavioural signals (bot detection)
for floods spread over many addresses.

## 6. Connection pooling (Apache HttpClient 5)

The JDK HttpClient exposes no pool settings or statistics, and the pool is where a gateway fails in
production.

| Setting | Value | Why |
|---|---|---|
| `max-connections-per-route` / `max-total-connections` | 100 / 500 | Caps what one upstream can take. When the pool is full a request waits at most `pool-lease-timeout` (500ms), then gets 503 `UPSTREAM_SATURATED` instead of queueing without bound. This is our saturation, so it does not count against the upstream's breaker. |
| `idle-timeout` | 60s | Deliberately not aggressive. Closing idle connections quickly sounds tidy, but under bursty traffic it causes connect/close churn: each close parks the local port in `TIME_WAIT` (~60s), and with ~28k ephemeral ports per destination a burst can run out of ports (`EADDRNOTAVAIL`) while every dashboard is green. Go has the same trap with `http.Transport.MaxIdleConnsPerHost`, which defaults to 2. |
| `validate-after-inactivity` | 2s | A connection idle longer than this is checked before reuse, so one silently closed by the upstream or a NAT/LB is replaced instead of failing a real request. |
| LIFO reuse | | Reusing the most recent connection lets surplus connections go idle after a burst and age out. FIFO keeps cycling through all of them. |
| No retries, redirects, cookies, decompression, default User-Agent | | A proxy must be transparent. |

Pool metrics (`httpcomponents_httpclient_pool_*`) make saturation visible. **Churn needs its own
metric (step 26):** pool gauges show how many connections exist, not how often they are replaced, and
port exhaustion comes from replacement. The pool's connection factory counts every new connection
(`gateway_upstream_connections_opened_total`); divided by forwarded requests that is the churn ratio,
near 0 with working keep-alive and near 1 when each request opens (and later closes) a connection. An
alert fires above 0.5 for 15 minutes, long before `EADDRNOTAVAIL`; a test checks that five sequential
requests to one upstream open exactly one connection.

**Alerts are part of the design (step 26).** `ops/prometheus/gateway-rules.yml` alerts on the error
budget of a 99.9% SLO with multi-window, multi-burn-rate rules (SRE Workbook): page at 14.4x burn
(1h window, confirmed by 5m so it clears quickly), ticket at 6x (6h, confirmed by 30m). Burn rate
rather than "error rate > X%" because it ties the alert to what users lose and ignores a short blip that
costs nothing. The other rules cover saturation and silent loss: p99 latency, open breakers, requests
waiting for a pooled connection, churn, retry budget exhausted, audit entries dropped. An integration
test checks that every metric name the rules use is in the scrape (it caught a wrong pool metric name).

**Bulkhead per tenant (step 24).** Rate limits count requests per second; they do not stop a tenant
whose requests are *slow* from holding most of an upstream's 100 pooled connections (and the threads
waiting on them) while other tenants' requests queue for a connection and time out with 503. So each
tenant gets at most N requests in flight (`gateway.tenant-max-concurrent-requests`, default 50, half
the per-upstream pool; `Tenant.maxConcurrentRequests` overrides). Over it: **429
`CONCURRENCY_LIMITED` at once, no queue** (load shedding: a queue would only turn a slow tenant into a
growing backlog). The permit is taken just before the upstream is picked, so a refused request holds no
breaker permit, and released when the response has been streamed, whatever happens. One semaphore per
tenant, never evicted (evicting one with permits out would let the tenant past its limit); a changed
limit gets a new semaphore while permits already out return to the old one. Cost: a request refused
here has already spent a rate-limit token.

## 7. Circuit breaker (hand-written)

Count-based sliding window per upstream `scheme://host:port`. CLOSED → OPEN when the failure rate over
the last N calls reaches the threshold (after a minimum number of calls, so 1 failure out of 1 is not
100%). OPEN fails fast with `Retry-After`. After `open-duration`, HALF_OPEN admits a few probes: all
succeed → CLOSED, any fails → OPEN with a fresh timer. 5xx, connect errors and timeouts are failures;
4xx are not. Each permit carries a generation number, so a slow call admitted before a state change
cannot affect the new state, and a `finally release()` guarantees a probe slot is never leaked.

Written by hand rather than with Resilience4j because the half-open logic is the interesting part and
it is about 200 lines with tests.

## 8. Weighted upstreams (Vose alias method)

A route lists upstreams with relative weights (90/10 for a canary). The alias table is built once per
snapshot (O(n)); each pick is O(1): one random column and one biased coin flip, lock-free because the
table is immutable. The chosen upstream gets its own breaker and is recorded in the audit line, so the
log shows which variant served each request.

**Fallback when a breaker is open (step 20).** The pick is by weight; if the picked upstream's breaker
is open and the route has other upstreams, the request goes to the heaviest one whose breaker admits it.
With stable 90 / canary 10, a failing canary sends all traffic to stable instead of failing 10% of
requests; 503 `CIRCUIT_OPEN` only when every upstream of the route is open (`Retry-After` = the soonest
any of them retries). Upstreams on the same host:port share a breaker, so each breaker is asked once.
The audit entry says which variant was skipped (`detail: breaker-open:<host:port>`), and
`gateway_upstream_fallbacks_total{route}` counts fallbacks. Heaviest-first rather than re-weighting the
healthy ones keeps it deterministic and easy to reason about; with more than two upstreams the
fallback traffic is not spread proportionally.

**Sticky canary (step 28).** A weighted pick per request sends one tenant's requests to both versions,
alternating: a bug in the canary looks intermittent, and a tenant can read data written by the other
version. With `"sticky": true` the route picks by tenant instead, so whole tenants are on the canary and
the canary's share is a share of tenants. The pick is **weighted rendezvous hashing** (highest random
weight, `routing/RendezvousHash`): every upstream gets a score `weight / -ln(u)` from a hash of
(tenant, upstream URL), and the highest wins. Same tenant, same upstream, on every instance, with no
shared state.

| Choice | Why |
|---|---|
| Rendezvous hashing, not `hash % 100` against cumulative weights | Raising the canary's weight (10% to 20%) only moves tenants onto it; none bounce back to stable and then over again. Removing an upstream moves only its tenants. Cumulative ranges give the first property for two upstreams in a fixed order, but reshuffle tenants when upstreams are added, removed or reordered. |
| A hash written out in the code (FNV-1a + Murmur3 finalizer) | Every instance and every release must agree. `String.hashCode` is stable but puts `tenant-1` and `tenant-2` next to each other, so tenants created together would land together. |
| Keyed by the upstream URL | Unrelated routes that share a canary put the same tenants on it. Changing the canary's URL reshuffles who is on it (accepted). |
| Off by default, per route | Per-request weighting spreads load better (one huge tenant on the canary is a large share of its traffic). |
| Stickiness yields to availability | A retry still prefers another upstream, and an open breaker still falls back to the heaviest healthy one: a tenant on a failing canary is served by stable. The audit line shows which upstream served each request. |

O(n) per pick for n upstreams (a handful), against O(1) for the alias table; no table to build.

## 9. Audit: asynchronous, bounded, explicit about loss

The request thread builds the entry and does a non-blocking `offer()` into a bounded queue; a background
thread serialises and writes JSON lines to stdout (collected by the log shipper). If the writer falls
behind and the queue fills, entries are **dropped and counted** (`gateway_audit_dropped_total`, alert
on it) rather than slowing every request or growing memory without bound. In production the sink would
publish to Kafka behind the same `AuditSink` interface.

**Tracing (step 31).** The gateway takes part in W3C Trace Context without a tracing library
(`TraceContext`, ~80 lines). `AuditFilter` joins a valid client `traceparent` (or starts a sampled
trace) and gives the gateway a span id of its own. Each upstream call, retries and hedges included,
gets a new child span id; it goes upstream as `traceparent` (the client's `tracestate` is passed along)
and into the audit entry (`upstreamSpans`, in the order sent). A trace can therefore be followed from
the client through the gateway to the exact call an upstream served. The client's own `traceparent` is
never forwarded: the gateway is a span in between.

| Choice | Why |
|---|---|
| No OpenTelemetry SDK (yet) | It is a new dependency (approval). The ids are the part every service must agree on; exporting spans with timings to a collector adds a dependency, not a different design. |
| Malformed headers start a new trace, per the spec | Uppercase hex, all-zero ids, version `ff`, extra fields in version 00, two `traceparent` headers. `tracestate` is dropped with them, and when longer than 512 characters. |
| Only the sampled flag is kept | The other flag bits are undefined in version 00. |
| `continue-client-traces` (default true) | An edge facing the internet may prefer to start every trace itself: a caller can pick trace ids (to collide with others) or force sampling of every request. |

## 10. Retries: only when safe, backed off, and budgeted (step 25); hedging (step 29)

Retries trade load for availability: a retry storm (every client retrying a struggling upstream) can
turn a brown-out into an outage. So a retry happens only when all of these hold:

1. **Safe to repeat:** an idempotent method without a body (GET, HEAD, OPTIONS). A POST that timed out
   may already have been processed; retrying it could charge a card twice.
2. **Nothing reached the client yet:** the failure is a connect failure or a timeout, thrown before
   any response byte went out (a 5xx the upstream sent is passed through, not retried).
3. **Attempts left:** `gateway.retry.max-attempts` (2: one retry).
4. **Budget left:** `RetryBudget` (Finagle / gRPC style): each request deposits 0.1 of a token, each
   retry spends one, capped at a reserve of 10. Retries stay at about 10% of requests however many
   requests fail, so an upstream down for everyone sees ~1.1× load, not 2×. Integer arithmetic
   (thousandths): 0.1 is not exact in a double, and ten deposits must make one retry.
5. **Breaker:** every attempt asks its upstream's breaker, so nothing goes to an open one; failed
   attempts count as failures, which trips the breaker sooner and then stops the retries.

The retry **waits** a random time in `[0, min(1s, 50ms × 2^n)]` (full jitter: retries from many
requests spread over the window instead of arriving in waves), on the request's virtual thread, and
**prefers another upstream** of the route over the one that just failed. The audit entry says
`retried after <code> from <host:port>`; `gateway_upstream_retries_total{route}` and
`gateway_upstream_retries_denied_total{route}` (budget spent) count them.

**Hedging (step 29).** A retry helps when a call *fails*; a hedge helps when it is merely *slow* (one
bad instance, a GC pause, a lost packet). A route with `hedgeAfterMs` (off by default) sends a second copy
of an idempotent, bodiless request if the first has no response after that long, preferably to another
upstream, and streams whichever answers first; the other is cancelled. With `hedgeAfterMs` near the
route's p95, about 5% of requests are hedged and the p99 drops to roughly p95 + the second call's time.

It is a load multiplier even when nothing fails, so it is fenced in the same way as retries:

| Rule | Why |
|---|---|
| Idempotent, no body (GET/HEAD/OPTIONS) | Two copies of a POST could both be processed. |
| Each hedge spends a retry-budget token | Hedges and retries together stay at ~10% extra load. When an upstream is slow for *everyone*, every request would want a hedge; the budget runs dry after the reserve and the gateway stops doubling load on it. Counted as `gateway_upstream_hedges_denied_total`. |
| The hedge's upstream breaker must admit it | No second copy to an upstream already known to fail. No hedge = the request simply waits for the first call. |
| The cancelled call tells its breaker nothing | Being slower than the other call is not a failure; counting it would trip breakers on healthy upstreams. A call that really fails (connect error, timeout) before anyone answered still counts. |
| No retry after a hedge | The hedge was the second attempt. A first call that fails *before* the hedge delay goes through the normal retry path instead. |
| One audit entry, one bulkhead slot | It is one client request; the audit `detail` says `hedged after Nms, served by the hedge (host:port)`. |
| A fixed delay per route, not a measured p95 | A per-route latency estimate (a sliding histogram) is the next step; a fixed number is predictable and easy to test. |

How it runs: `UpstreamProxy.forward` was split into `prepare` (builds the outbound request from the
servlet request), `execute` (sends, waits for the status and headers) and `stream` (copies the body).
Both outbound requests are prepared on the request thread; each `execute` runs on its own virtual thread
and only hands back a response or an exception through a queue. Everything that touches the servlet
request or response, the audit record or a breaker stays on the request thread. The loser is cancelled
(`HttpUriRequestBase.cancel()`, which closes its connection; a response that arrives anyway is closed
by whichever side sees it last), so its pooled connection comes back at once: the test checks the pool
has nothing leased long before the slow call would have answered.

## 11. RBAC: user → role → permission, one role per session (guards the admin API)

Classic RBAC. A role has many permissions (`tenants:read`, `tenants:write`, `tokens:issue`,
`routes:read`, `routes:write`). A role can be assigned to many users, and a user can hold many roles.
Permissions are never assigned to users directly. At login the user picks **one** of their roles, and
the session has that role's permissions only, not the union of all their roles. An operator who
usually works as `viewer` has to log in again as `operator` to change anything, so routine work runs
with least privilege and the audit log shows which hat they wore (`actor`, `role`).

| Choice | Why |
|---|---|
| Users and roles in `application.yml` | Small and reviewable as code. Managing them at runtime (an API, or SSO groups) is a next step. |
| Login → short-lived RS256 session token carrying user + role, not permissions | Every request looks the role's permissions up and checks that the user still holds the role. Changing a role, or removing an assignment, applies to existing sessions immediately. |
| Same signing key as tenant tokens, different audience and a required `role` claim | One key to manage; the two kinds of token are never accepted in each other's place (tested both ways). |
| Authentication in `AuthFilter`, authorization in a `HandlerInterceptor` | The filter runs before Spring picks a handler. The interceptor can read the handler's `@RequiresPermission`. Both reject by throwing `GatewayException`, so denials are rendered and audited like any other. |
| Fail closed | An admin endpoint without `@RequiresPermission` is refused, and `AdminControllerPermissionsTest` fails the build if one is added. |
| Admin permissions as `String` constants (`AdminPermissions`) | Annotation values must be compile-time constants, and `Permission` is no longer an enum (data-plane permissions such as `orders:delete` are built at runtime). Constants keep typos a compile error; a test also checks that every declared permission is granted by some configured role, so a typo cannot silently make an endpoint unreachable. |
| Keys stored as SHA-256 | They are long random API keys. Human passwords would need a slow hash (bcrypt, argon2). |

**Next:** a login rate limit per user; per-session revocation (a `jti` deny-list); users from SSO with
groups mapped to roles; per-tenant role assignments (e.g. "admin of tenant acme") if tenants
self-serve.

## 12. One RBAC module for both planes: scope = which service, role = which action

The same User → Role → Permission model now also covers tenant users on the data plane, as a second
layer under scopes:

* **Scope** answers *which services* a token may reach (`orders`), capped by the tenant's
  `grantableScopes`. Coarse, and fixed in the token for its lifetime.
* **RBAC** answers *which actions*: the token's one role must have `<service>:<action>`, with the
  action taken from the HTTP method (GET/HEAD/OPTIONS read, DELETE delete, else write). Checked on
  every request, against current assignments.

| Choice | Why |
|---|---|
| A decision point (`rbac/Authorizer`) separate from the enforcement points (`PolicyEngine`, `PermissionInterceptor`) | PEP/PDP split. The gateway enforces, so a denied request never reaches an upstream and every service gets the same rules; the RBAC module decides. `RbacBoundaryTest` keeps the rest of the code on the module's API only. |
| In-process module, not a separate RBAC service | No network hop or extra dependency on the hot path. Splitting it out later means a `RemoteAuthorizer` behind the same interface; the enforcement code does not change. |
| Decisions from data pulled into memory, not a remote check per request | The same idea as the config snapshot: no I/O per request, and the gateway keeps working if the store is briefly unavailable. Costs: changes propagate within the refresh interval (≤ 5s), and all assignments must fit in memory. A per-request remote check (Envoy `ext_authz` style) fits when rules become resource-level or relationship-based (Zanzibar, OpenFGA); it then needs a decision cache, request coalescing, a timeout and a breaker, and must fail closed. |
| Role definitions global; assignments per tenant user (table `role_assignments`, managed through `/admin/rbac/tenants/{t}/users/{u}`) | One vocabulary for every tenant; tenant users are created with their tenant. Per-tenant custom roles are a next step. |
| The gateway checks method-level actions only | It does not know the domain. Resource-level rules ("only your own orders") belong to the service, which gets `X-User-Id` and `X-Role` from the gateway. |
| Service names may not be `tenants`, `routes`, `tokens` or `rbac` | Data-plane and management permissions share one `resource:action` namespace; a service named `routes` would otherwise give `routes:write` a second meaning. |
| Permission check before quota | A request the role may not make does not use up the tenant's quota (same rule as path and scope). |

**Next:** per-tenant roles; resource-level checks through a remote PDP if needed.

## 13. The RBAC management API

Custom roles and tenant users' role assignments are managed at runtime through `/admin/rbac`, and
stored in the config database next to tenants and routes. They are served from the same immutable snapshot, so
decisions still do no I/O, and a change applies at once on the instance that made it and within one
refresh interval on the others.

| Choice | Why |
|---|---|
| Built-in roles (config) are read-only; custom roles come from the API | Nobody can lock every operator out by editing or deleting `admin` through the API, and the roles the system depends on are reviewable as code. A custom role can never shadow a built-in one. |
| Custom roles hold service permissions only; management permissions exist only in built-in roles | Who could hold a custom role with `routes:write`? Operators are limited to built-in roles (config), tenant users never call the management API. So such a role would be dead weight, and refusing it (400) means `rbac:write` hands out access to services, never to the management API: there is nothing to escalate through. Once operator assignments become editable (API or SSO groups), add the Kubernetes "escalate" rule: a role may only carry management permissions the caller's active role has. |
| Tenant users may not hold roles with management permissions | This covers the built-in ones (`viewer`, `admin`): they never call the management API, so such a grant can only be a mistake. |
| A role still assigned cannot be deleted (409); deleting a tenant deletes its assignments | No silent loss of access, and a tenant re-created later does not inherit old grants. |
| One row per tenant user (`role_assignments`, key `tenant_id, user_id`) | Two admins editing different users never overwrite each other (no read-modify-write of a shared document). |
| Operators stay in config | Their keys are there; with SSO they would come from the IdP's groups instead. |
| The API lives in `admin/RbacController`, the store in `ConfigStore` | The RBAC module stays a pure decision point behind `Authorizer`; storage is reused rather than duplicated (same snapshot, versioning and data-loss handling). |

**Next:** an audit trail queryable per role and user (today the audit log has `actor`, `role` and the
path of each change); per-tenant custom roles; approvals for changes to roles that hold management
permissions.

## 14. Tenants loaded on demand, through a single flight

**Problem.** Every request needs its tenant's config (enabled, quota, path allowlist, services). Loading
all tenants into every instance makes memory grow with the total number of tenants. Loading a tenant
the first time a request needs it, and caching it briefly, fixes that, but creates a new failure mode:
when 1,000 requests for `acme` arrive at the same instant on a cold (or just expired) cache, they all
miss together and all query the database (cache stampede).

**Design.** `ConfigStore.tenant(id)` goes through `cache/SingleFlightCache` (Go: `singleflight.Group`
plus a TTL):

1. Fresh entry in memory: return it.
2. Otherwise one caller wins the right to load: it puts an in-flight `CompletableFuture` into a
   `ConcurrentHashMap` (compare-and-set) and queries the database (`SELECT data FROM tenants WHERE id = ?`).
3. Everyone else who arrives meanwhile finds that future and waits on it (`join`), each on its own
   virtual thread.
4. The loader completes the future: all 1,000 get the same tenant; the entry is cached for 5s.

`ConfigStoreIT.testTenant_1000ConcurrentRequestsOnAColdCache_OneDatabaseRead` shows it on H2: 1,000
concurrent lookups, `tenantLoads() == 1`.

| Choice | Why |
|---|---|
| TTL 5s, the same as the config refresh interval | A tenant change (e.g. disable) takes at most 5s to reach other instances, as before; the writing instance drops its own copy at once. |
| "Unknown tenant" is cached too | Tokens of a deleted tenant do not cost a database read each. Creating the tenant drops the cached "unknown". |
| Stale if error | If reloading fails (database down), the last known value is served for another TTL, so tenants already seen keep working (static stability) and the database is retried once per TTL, not per request. A tenant never seen before gets 503 `CONFIG_UNAVAILABLE`. |
| Data loss counts as an error | When the version disappears, "not found" would be a lie: tenant reloads fail on purpose, so the last known values are kept, as the snapshot does for routes. |
| Failures are not cached, and never strand waiters | A failed load (exception or `Error`) releases every waiter and removes the entry; the next request tries again. |
| Bounded | At 100,000 cached tenants, expired entries are purged; if still full, lookups bypass the cache rather than grow memory. |

**Cost:** the first request for a tenant on each instance pays one database read; and a tenant never
seen by an instance cannot be served by it while the database is down.

**A breaker around those reads (step 30).** Failures are not cached (on purpose: the next request
should try again), so during a database outage every request that misses the cache would wait for a
connection, and with Hikari's default that is 30 seconds. The gateway's own dependency needs the same
protection as an upstream. Tenant and revocation loads go through a `CircuitBreaker` named `config-db`
(the class the upstreams use): at 50% failures among the last 10 reads it opens for 5s, and reads fail
at once. What a failed read means does not change: a cached tenant is served stale, a tenant never seen
gets 503 `CONFIG_UNAVAILABLE` (now with `Retry-After` = when the breaker probes), a revocation check
fails open. After the cool-down one read is let through as a probe; success closes it. The connection
timeout is lowered to 2s so the reads that open the breaker do not each hang for 30s. Admin reads and
writes and the background refresh do not use it: an operator should see the real error, and the
refresh already keeps the last snapshot on failure. `gateway_circuit_state{name="config-db"}` shows it,
and the existing open-breaker alert covers it.

## 15. Token revocation, without a shared cache (step 27)

**Problem.** A leaked tenant token works until it expires (up to `jwt.max-ttl`, 1h). Disabling the
tenant stops it, and every other token of that tenant with it.

**Design.** A deny-list of token ids, in the config database, read through the same single flight as
tenants:

1. Every token has a `jti` (a UUID, returned as `tokenId` at issuance); a token without one is rejected,
   since it could never be revoked.
2. `POST /admin/tokens/{tokenId}/revoke` (`tokens:revoke`) inserts `(jti, expires_at)` into
   `revoked_tokens`. `expires_at` = now + `max-ttl` + clock skew: no token issued so far can be accepted
   after that, so the row can go then. The gateway keeps no list of issued tokens, so it cannot tell an
   unknown id from a known one, and does not try: always 204.
3. `AuthFilter` asks `ConfigStore.isRevoked(jti)` after the signature and claims check, so a junk
   token costs no lookup. The answer is cached per token for `tenant-cache-ttl` (5s), most answers being
   "no"; concurrent requests with one token share one read. 401 `UNAUTHENTICATED`, audit
   `detail: auth:REVOKED`.

| Choice | Why |
|---|---|
| Not in the snapshot, no version change | The snapshot is configuration, reloaded whole when the version changes; a revocation would make every instance reload everything. Revocations are per token, so they are read per token. |
| One cached read per token per TTL, not per request | The database sees at most (tokens in use) / 5s reads per instance, independent of request rate. The alternative, every revocation in each instance's memory, makes the check free but costs a sync of a list that only grows until rows expire. |
| The writing instance applies it at once, others within the TTL | As for tenant changes. |
| Expired rows are deleted by the next revocation | No sweeper thread; the table holds at most the revocations of the last hour. |
| **Fail open** when the database is down and the token was never checked here | Auth runs before the tenant lookup, so failing closed would turn a database outage into 401s for every new token while routes and cached tenants could still serve them. A token checked before keeps its last answer (stale if error), so one known to be revoked stays revoked. Counted (`gateway_auth_revocation_unavailable_total`) and alerted. |
| Tenant tokens only | Admin sessions still end when the user loses the role or the session expires (known gap). |

## How these interact

* **Refresh interval vs. revocation speed:** a longer interval means less database traffic but a disabled
  tenant, or a revoked role, stays usable on other instances for longer.
* **Revocation: cache TTL vs. speed vs. database load vs. failure mode:** the TTL is how long a revoked
  token keeps working on other instances, and also what keeps revocation checks off the database per
  request. Failing open keeps the data plane up when the database is not; failing closed would make
  every token as dependent on the database as an uncached tenant.
* **Local RBAC decisions vs. freshness:** deciding from in-memory data keeps the hot path free of I/O
  and the authorization service off the critical path, at the cost of a few seconds of staleness; a
  remote check per request flips that trade-off.
* **Snapshot vs. tenant count:** the snapshot removes per-request I/O and gives static stability, but
  costs memory per tenant; lazy loading needs request coalescing to stay safe under load.
* **Idle timeout vs. ports:** the setting that keeps the pool tidy is the one that exhausts ephemeral
  ports under bursty load, and no pool gauge shows it; only a count of new connections (churn) does.
* **Alerts vs. noise:** a burn-rate alert pages on what threatens the SLO and ignores a blip that
  costs nothing; the short confirming window makes it clear soon after recovery. Saturation alerts
  (pool waits, churn, open breakers) fire before users see errors.
* **Pool limits vs. breaker:** a full pool is our saturation, not the upstream's failure, so it must not
  trip the breaker.
* **Shared breaker vs. tenants:** a per-upstream breaker protects a failing upstream, but one tenant's
  bad requests can trip it for everyone.
* **Hedging vs. load vs. churn vs. stickiness:** a hedge cuts the tail only by adding a request; the
  shared budget keeps hedges plus retries at ~10% extra and stops them when slowness is everywhere. Each
  cancelled loser closes a connection, which shows in the churn metric (bounded by the same budget). On a
  sticky route the hedge goes to the other version, the same availability-over-stickiness rule as
  retries and fallbacks.
* **Retries vs. breaker vs. budget:** retries add load exactly when an upstream fails; the breaker
  removes it once failures are consistent (and every failed attempt counts towards tripping it), and the
  budget caps what retries can add before that. Backoff with jitter keeps the retries that do go out
  from arriving in synchronized waves.
* **Local vs. shared rate limits:** local buckets remove a network round trip and a failure mode from
  every request, but the effective quota grows with the number of instances. A shared store keeps it
  exact and brings back a remote dependency, and with it a breaker and a choice between an
  availability problem (fail closed) and a protection gap (fail open).
* **Sticky canary vs. load spread vs. availability:** stickiness makes a canary's effects attributable
  to whole tenants, but its traffic share is now a share of tenants, uneven when tenants differ in size;
  and stickiness gives way to a retry or an open breaker, so a tenant can be served by the other version
  exactly when one of them is failing.
* **Fallback vs. breaker:** the breaker protects a failing upstream by taking its traffic away; the
  fallback decides where that traffic goes. Moving it all to the heaviest healthy upstream can push that
  one harder than its usual share, which is why its own breaker still applies.
* **Trusting the client's trace vs. control:** joining the caller's `traceparent` gives an end-to-end
  trace across companies' systems, but lets the caller choose ids and the sampling decision; starting
  fresh at the edge keeps control and loses the link.
* **Route limit vs. tenant fairness:** a limit per route protects the service behind it from the sum
  of all tenants, but its bucket is shared, so a busy tenant can take most of it within its own quota.
* **Database breaker vs. stale data vs. fail open:** the breaker turns an outage into fast failures, but
  what a failure means is decided elsewhere: stale tenants (availability), 503 for unknown tenants
  (safety), and revocations failing open. The breaker makes each of those choices cheap; it does not
  change them.
* **Tenant cache TTL vs. propagation vs. database load:** one number. A longer TTL means fewer reads
  but a slower spread of tenant changes; the single flight caps a burst at one read per tenant
  regardless of the TTL.
* **Bulkhead vs. rate limit vs. pool:** the rate limit bounds how often a tenant may ask, the bulkhead how
  much of the gateway it may hold at once, and the pool how much of an upstream everyone together may
  hold. A slow upstream turns a tenant's normal rate into a pile of in-flight requests; only the bulkhead
  stops that pile from crowding out the other tenants.
