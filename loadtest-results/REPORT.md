# Stress test report: requests per second the gateway serves within its SLO

> **Note (kit changed after these runs):** the load-test kit no longer uses Docker. The gateway and the
> upstreams now run as Spring Boot processes (`./scripts/stack.sh loadtest up`; the upstreams are
> `upstream-app` with `PLAIN_OK` / `DELAY_MIN_MS`/`DELAY_MAX_MS` instead of nginx). The figures below were
> measured with the Docker Desktop kit described here and have not been re-measured.

Two tests on one laptop (Apple M4 Max, Docker Desktop VM with 16 CPUs and 8 GB), both with vegeta
(constant arrival rate) against the gateway at step 32:

| | Test 1: upstream answers at once | Test 2: upstream answers in 100 ms – 5 s (uniform, mean 2.55 s) |
|---|---|---|
| What limits throughput | CPU per request (~0.3 ms) | Requests in flight: rate × 2.55 s (Little's law) |
| With the default settings | **4,000 requests/s** (pool per upstream, breaker window, client ports) | **20 requests/s** (pool of 100 per upstream) |
| Best measured | **20,000 requests/s**, p99 4.7 ms, 100% (highest tried) | **5,000 requests/s** (~12,800 in flight), p99 4.95 s = the upstream's own, 100% |
| What it took | pool 400 per upstream; client capped at 2,000 connections | pool 20,000; Tomcat `max-connections` 20,000; load generated inside the Docker network |
| What stopped it next | not reached | memory: ~145 KB live per request in flight, mostly Tomcat and HttpClient buffers, on a VM already near its 8 GB |
| Code changed | no | no (configuration only; two GC alternatives were tried and were worse) |

## Test 1: an upstream that answers at once

**Result:** on one laptop, the gateway served **20,000 requests/s with p99 4.7 ms and 100% success**
(the highest rate tried), using about 5.5 CPU cores. With the default upstream pool (100 connections
per upstream) and an uncapped load generator, the limit was **4,000 requests/s**; 8,000 failed. That
limit came from configuration and the test rig, not from the request path.

| | |
|---|---|
| Date | 2026-11-14 |
| Code | branch `feature/GW-3-in-memory-demo`, `abb5dae` (gateway code as of step 32, `bf9269c`) |
| Tool | vegeta 12.13.0, open model (constant arrival rate), so a slowing gateway cannot hide latency by slowing the load |
| SLO for a step to pass | p99 ≤ 50 ms and success ≥ 99.9% |
| Kit | `ops/loadtest/docker-compose.yml`, `ops/loadtest/nginx.conf`, `scripts/loadtest.sh` |

### 1.1 Setup

```
vegeta (macOS host) ──► gateway container :9080 ──► nginx container (returns "ok")
     4 tenants, tokens rotated          one route /lt, service orders, one upstream
```

| Component | Detail |
|---|---|
| Machine | Apple M4 Max, 64 GB, macOS 26.6 |
| Docker | Docker Desktop 29.4, VM with 16 CPUs and 8 GB; gateway and upstream share it |
| Gateway | the repo's Docker image (Java 21, `-XX:MaxRAMPercentage=75`), config in in-memory H2 |
| Gateway settings changed for the test | per-address limit off (`GATEWAY_IPRATELIMIT_REQUESTSPERMINUTE=0`; one load generator would otherwise be capped at 20 requests/s). Runs 2 and 3: upstream pool 400 per upstream (`LT_POOL_PER_ROUTE=400`; default 100) |
| Tenants | 4 tenants, 1,000,000 requests/min each (burst 1,000,000), `maxConcurrentRequests` 10,000; one user each with role `orders-reader` |
| Upstream | nginx 1.27, `return 200 "ok"`, keep-alive 75 s, no access log: as close to free as an upstream gets |
| Request | `GET /lt/items/1` with a tenant JWT: path canonicalisation, JWT verification (RS256), revocation check (cached), tenant (cached), route, policy (allowlist, scope, RBAC, tenant quota), bulkhead, breaker, pooled proxy, async audit entry, metrics |
| Steps | 30 s each after a warm-up; the run stops at the first step that misses the SLO |

`gw-cpu` in the tables is the gateway container's CPU from `docker stats`, sampled halfway through each
step (100% = one core). Latencies are measured by vegeta, so they include the Docker Desktop port
forwarding between the host and the VM.

### 1.2 Results

#### Run 1: default settings (`20261114-172903`)

Upstream pool 100 per upstream; vegeta without a connection cap.

| Step | Target rate/s | Served/s | Mean ms | p50 ms | p90 ms | p99 ms | Max ms | Success | gw-cpu | Status codes |
|---|---|---|---|---|---|---|---|---|---|---|
| upstream directly | 12,000 | 12,000 | 0.20 | 0.13 | 0.19 | 1.60 | 29.8 | 100% | 0.1% | 200 × 360,002 |
| gateway | 500 | 500 | 0.82 | 0.62 | 1.26 | 3.35 | 33.2 | 100% | 23% | 200 |
| gateway | 1,000 | 1,000 | 0.58 | 0.44 | 0.65 | 3.36 | 52.8 | 100% | 34% | 200 |
| gateway | 2,000 | 2,000 | 0.46 | 0.38 | 0.48 | 1.32 | 50.6 | 100% | 55% | 200 |
| gateway | **4,000** | 4,000 | 0.50 | 0.34 | 0.43 | **2.61** | 50.6 | **100%** | 112% | 200 × 120,000 |
| gateway | 8,000 | 3,142 | 1,056 | 0.62 | 5,337 | 6,910 | 10,276 | **55.97%** | 222% | 200 × 107,666, 503 × 49,740, 502 × 34, no response × 34,907 |

The upstream alone handles 12,000/s at p99 1.6 ms, so the backend is not what limits the gateway rows.

#### Run 2: pool 400 per upstream, load generator capped at 2,000 connections (`20261114-173340`)

| Step | Target rate/s | Served/s | Mean ms | p50 ms | p90 ms | p99 ms | Max ms | Success | gw-cpu |
|---|---|---|---|---|---|---|---|---|---|
| gateway | 4,000 | 4,000 | 0.37 | 0.34 | 0.44 | 0.80 | 8.4 | 100% | 102% |
| gateway | 5,000 | 5,000 | 0.37 | 0.33 | 0.43 | 1.08 | 15.6 | 100% | 118% |
| gateway | 6,000 | 6,000 | 0.39 | 0.36 | 0.45 | 0.88 | 14.4 | 100% | 169% |
| gateway | 7,000 | 7,000 | 0.40 | 0.37 | 0.45 | 1.24 | 8.1 | 100% | 206% |
| gateway | **8,000** | 8,000 | 0.42 | 0.38 | 0.48 | **1.46** | 17.2 | **100%** | 235% |

#### Run 3: same settings, higher rates (`20261114-173640`)

| Step | Target rate/s | Served/s | Mean ms | p50 ms | p90 ms | p99 ms | Max ms | Success | gw-cpu |
|---|---|---|---|---|---|---|---|---|---|
| gateway | 10,000 | 10,000 | 0.48 | 0.42 | 0.55 | 2.09 | 15.5 | 100% | 301% |
| gateway | 12,000 | 12,000 | 0.54 | 0.46 | 0.66 | 2.84 | 13.2 | 100% | 370% |
| gateway | 14,000 | 14,000 | 0.63 | 0.52 | 0.81 | 3.29 | 36.6 | 100% | 425% |
| gateway | 16,000 | 16,000 | 0.67 | 0.57 | 0.87 | 3.05 | 22.1 | 100% | 484% |
| gateway | **20,000** | 19,999 | 0.96 | 0.72 | 1.43 | **4.71** | 63.4 | **100%** | 552% |

During runs 2 and 3: no audit entries dropped, every breaker closed at the end, and the pool opened 23 and
195 new upstream connections in total (connections were reused, so no churn).

Latency over time: `plot.html` (vegeta plot) in each run's directory.

### 1.3 Analysis

#### Why run 1 failed at 8,000 requests/s

It was a chain of three failures, each triggering the next.

1. **The upstream pool ran out.** With one upstream, the gateway can hold at most 100 connections to
   it. By Little's law, 100 connections serve about 100 ÷ (time per call) requests per second. As soon
   as calls slowed briefly, requests queued for a connection; 232 of them waited the full 500 ms lease
   timeout and got 503 `UPSTREAM_SATURATED`, and 34 failed with `UPSTREAM_ERROR`
   (HttpClient `RequestFailedException`).
2. **The upstream's breaker opened on a 2.5 ms sample.** The breaker judges the last 20 calls (at least
   10). At 8,000/s that is about 2.5 ms of traffic, so a few dozen errors in a burst are enough to trip
   it, and it then stays open for 10 s: **49,511 requests got 503 `CIRCUIT_OPEN`** for an upstream that
   was healthy. This, not the pool, caused most of the failures.
3. **The load generator ran out of ports.** As responses slowed, vegeta opened new connections, and
   requests that timed out closed theirs, leaving local ports in `TIME_WAIT`. macOS has about 16,000
   ephemeral ports (49152–65535). vegeta's errors read `bind: can't assign requested address`
   (`EADDRNOTAVAIL`): 34,907 requests got no response at all, and vegeta could only send 6,595/s of the
   8,000 asked. This is the "day we ran out of ports" failure, on the client side.

Raising the pool to 400 and capping vegeta at 2,000 connections removed all three: 8,000/s then ran at
p99 1.46 ms.

#### How the gateway scales

| Rate/s | gw-cpu | Requests/s per core |
|---|---|---|
| 4,000 | 102–112% | ~3,700 |
| 8,000 | 235% | ~3,400 |
| 12,000 | 370% | ~3,200 |
| 16,000 | 484% | ~3,300 |
| 20,000 | 552% | ~3,600 |

CPU grows linearly with the rate: about **3,500 requests/s per core**, or roughly 0.3 ms of CPU per
request for the whole path (JWT verification, policy, proxying, the audit entry, metrics). Latency stays
under 1 ms at p50 and under 5 ms at p99 throughout. Up to 20,000/s nothing saturated: no pool waits, no
dropped audit entries, no breaker trips. The ceiling on this machine was not reached; the VM has 16
CPUs, shared with nginx and Docker's port forwarding, while vegeta runs on the host.

### 1.4 What to tune for more requests per second

Confirmed by these runs:

| Change | Why | Where |
|---|---|---|
| Size the upstream pool with Little's law: connections ≥ rate × time per call, with headroom | The default of 100 per upstream was the first limit with a single upstream | `gateway.proxy.max-connections-per-route` (and `max-total-connections` for many upstreams) |
| Judge breakers over time, not over a handful of calls | A 20-call window is 2.5 ms at 8,000/s: a burst of errors opens the breaker for 10 s on a healthy upstream. A time-based sliding window (e.g. the last 10 s), or a minimum number of calls that scales with the rate, makes the decision on enough evidence | `CircuitBreaker` (window), `gateway.circuit-breaker` |
| Cap the load generator's connections, and run it on another machine | Otherwise the client, not the gateway, runs out of ports and CPU | `MAX_CONNECTIONS` in `scripts/loadtest.sh` |

Likely next wins, **not measured here** (each should be confirmed with a profiler such as JFR before changing code):

| Candidate | Why it may matter at 0.3 ms of CPU per request |
|---|---|
| Cache verified tokens (token → principal, until expiry, bounded) | An RS256 signature check costs tens of microseconds and runs on every request; the revocation check would stay as it is |
| Audit serialisation and stdout | One JSON line per request on a single writer thread; at higher rates it may fall behind (watch `gateway_audit_dropped_total`) |
| Per-request metric registration | Looking up the timer by name and tags on every request; caching the meters avoids the lookup |
| Request ids from `UUID.randomUUID()` | Uses a shared `SecureRandom`; a cheaper random source would avoid contention |
| `PoolConcurrencyPolicy.STRICT` | One lock across the pool; `LAX` contends less at the cost of a less exact total limit |

### 1.5 Limits of this test

- **One machine.** vegeta, the gateway, nginx and Docker's port forwarding share one laptop, so the
  numbers are a lower bound for the gateway itself. Run the load generator on another machine (and the
  gateway on Linux) for numbers to plan capacity with.
- **One upstream, no latency.** A real backend that takes 20 ms per call needs 20× the connections
  for the same rate (Little's law), so pool limits and the per-tenant bulkhead matter much more.
- **One request shape on the happy path.** Not covered: many tenants not yet cached (single flight),
  rejections (401, 429), POST bodies, slow upstreams (bulkhead, hedging), and more than one instance.
- **30-second steps.** Long enough for steady state, not for slow effects such as memory growth.

### 1.6 Reproduce

```bash
brew install vegeta
LT_POOL_PER_ROUTE=400 docker compose -f ops/loadtest/docker-compose.yml up -d --build
RATES="4000 8000 12000 16000 20000" ./scripts/loadtest.sh      # results in loadtest-results/<time>/
docker compose -f ops/loadtest/docker-compose.yml down
```

Without `LT_POOL_PER_ROUTE` the gateway uses the default pool (run 1). Only this report is in the
repository. Each run writes a directory under `loadtest-results/` (named in the headings above) with
the raw vegeta results (`*.bin`, 0.2–0.7 GB per run), the target files (they contain bearer tokens),
`summary.txt`, per-step reports (`*.txt`, `*.json`), CPU samples (`*.cpu`) and `plot.html`; those stay
on the machine that ran the test (`.gitignore`). All numbers above are copied from them.

## Test 2: an upstream that answers in 100 ms – 5 s

**Result:** the gateway adds almost nothing to a slow upstream's latency (p99 4,951 ms against the
upstream's own 4,954 ms), but every request holds a connection, a virtual thread and its buffers for
2.55 s on average, so capacity is set by **requests in flight = rate × 2.55 s**. With the default
settings that is **20 requests/s** (the pool of 100 connections per upstream). After sizing the
pool and Tomcat's connection limit by Little's law, it served **5,000 requests/s (~12,800 in flight) at
100% success**. Beyond that the limit on this machine was memory, shared with the load generator and
other containers. No code change was needed or found to help.

| | |
|---|---|
| Upstream | nginx 1.27 with njs (`ops/loadtest/nginx-slow.conf`, `delay.js`): each response waits a random 100 ms – 5 s, then answers "ok"; timers hold no worker |
| SLO for a step to pass | p99 ≤ 5.3 s (the upstream alone takes up to 5 s) and success ≥ 99.9%; vegeta timeout 10 s |
| Gateway settings changed | per run, below: `LT_POOL_PER_ROUTE`, `LT_POOL_TOTAL`, `LT_TOMCAT_MAX_CONNECTIONS`, `LT_JAVA_OPTS` (all passed through by the compose file) |
| `leased/pend` | the upstream pool halfway through a step: connections in use / requests waiting for one. With one upstream, leased = requests in flight |
| `served/s` | vegeta's throughput: successful responses ÷ (attack time + time to drain the last responses, up to 5 s), so it reads below the rate for slow responses (1,000/s for 30 s = 30,000 ÷ 35 s = 859/s) even when every request succeeds |

### 2.1 Results

#### Run A: default settings, load from the Mac (`20261114-175403`)

Pool 100 per upstream / 500 in total, Tomcat `max-connections` 8,192.

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu | leased/pend | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| upstream directly | 320 | 276 | 2,558 | 4,957 | 5,004 | 100% | 0.4% | – | 200 |
| gateway | **20** | 18 | 2,485 | 4,956 | 5,004 | **100%** | 16% | 51/0 | 200 |
| gateway | 40 | 34 | 2,779 | 5,267 | 5,502 | **98.25%** | 9% | **100/9** | 200 × 1,179, 503 × 21 |

At 40/s the gateway needs about 40 × 2.55 ≈ 102 connections to the upstream; it may hold 100. The pool
was full with 9 requests waiting, and 21 waited the 500 ms lease timeout and got 503
`UPSTREAM_SATURATED`.

#### Run B: pool 20,000, load from the Mac (`20261114-175622`)

Pool 20,000 per upstream and in total, Tomcat 8,192, vegeta capped at 15,000 connections.

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu | leased/pend | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| upstream directly | 4,000 | 3,430 | 2,997 | **6,474** | 7,128 | 100% | 0.1% | – | 200 |
| gateway | 250 | 214 | 2,524 | 4,954 | 5,005 | 100% | 47% | 613/0 | 200 |
| gateway | 500 | 430 | 2,580 | 4,949 | 5,001 | 100% | 38% | 1,281/0 | 200 |
| gateway | **1,000** | 859 | 2,520 | 4,952 | 5,002 | **100%** | 41% | 2,555/0 | 200 |
| gateway | 2,000 | 1,647 | 3,122 | 10,000 | 10,005 | 95.88% | 59% | 4,999/0 | 200 × 57,525, timeout × 2,475 |

The 2,000/s step failed, but not in the gateway: by the gateway's own timer, **all 113,870 requests it
answered took ≤ 5.73 s**, and the pool had no waiters. The 2,475 timeouts were requests that never got
through to it. The upstream baseline shows the same thing without the gateway: straight from the Mac
at 4,000/s, the upstream's p99 was 6.5 s although it never waits more than 5 s. **The limit here was
Docker Desktop's port forwarding** between macOS and the VM, carrying about 5,000 concurrent
connections. From here on, vegeta runs in a container inside the Docker network (the official Linux
build of vegeta 12.13.0).

#### Run C: load from inside the Docker network, Tomcat at its default (`20261114-180100-in-docker`)

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu | leased/pend | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| upstream directly | 4,000 | 3,432 | 2,554 | 4,954 | 5,002 | 100% | 0.1% | – | 200 |
| gateway | 2,000 | 1,718 | 2,538 | 4,948 | 5,026 | 100% | 74% | 5,146/0 | 200 |
| gateway | **3,000** | 2,578 | 2,549 | **4,953** | 5,039 | **100%** | 117% | 7,668/0 | 200 |
| gateway | 4,000 | 2,825 | 5,812 | 10,001 | 10,010 | 94.15% | 88% | **8,192**/0 | 200 × 112,981, timeout × 7,019 |

Inside the network the upstream is fine at 4,000/s (p99 4,954 ms), confirming run B's diagnosis. The
gateway at 3,000/s adds nothing measurable. At 4,000/s it needs about 10,200 requests in flight, and
in-flight requests stop at exactly **8,192: Tomcat's default `max-connections`**. Each request holds
one connection to Tomcat; beyond the limit new connections wait in the accept queue until the client
times out.

#### Run D: Tomcat `max-connections` 20,000 (`20261114-180426-in-docker`)

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu, memory | leased/pend | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| warm-up | 2,000 | 1,720 | 2,549 | 4,949 | 5,002 | 100% | 115%, 2.3 GB | 5,061/0 | 200 |
| gateway | **4,000** | 3,431 | 2,548 | **4,952** | 5,054 | **100%** | 115%, 3.8 GB | 10,163/0 | 200 |
| gateway | 5,000 | 2,026 | 2,781 | 25,467 | 26,862 | 92.92% | 258%, 4.8 GB | 12,796/0 | 200 × 84,422, timeout × 6,429 |
| gateway | 6,000 | 28 | 101,937 | 115,771 | 116,191 | 11.05% | 172%, 5.5 GB | 17/0 | 200 × 3,219, timeout × 25,908 |

Raising Tomcat's limit fixed 4,000/s (10,163 in flight). At 5,000/s and above, the gateway stalled:
**one G1 young-generation pause took 27.3 s** (76 pauses, 40.3 s in total). The gateway container's
memory grew from 2.3 GB to 5.5 GB.

#### Run E: generational ZGC (`20261114-181040-in-docker-zgc`)

Same as run D, plus `-XX:+UseZGC -XX:+ZGenerational`.

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu, memory | leased/pend | Status codes |
|---|---|---|---|---|---|---|---|---|---|
| warm-up | 2,000 | 1,719 | 2,541 | 4,953 | 5,008 | 100% | 79%, 3.6 GB | 5,084/0 | 200 |
| gateway | 4,000 | 38 | 310,591 | 318,241 | 319,107 | 49.89% | 178%, 5.3 GB | 1/0 | 200 × 12,025, timeout × 11,998, 503 × 71, 502 × 7 |

Worse than G1. ZGC's pauses stayed tiny (2.4 s in total), but its 8 major cycles took **317 s** of
concurrent work (~40 s each): with this heap it could not reclaim memory as fast as requests allocated
it, so threads stalled waiting for memory (allocation stalls). ZGC needs more headroom than this
machine had. The run was stopped after this step.

#### Run F: what the memory is (`20261114-1822-in-docker-jdk-histogram`)

The same jar in `eclipse-temurin:21-jdk` (for `jcmd`), G1, pool and Tomcat as in run D. A class
histogram of live objects (`jcmd GC.class_histogram`) at idle and 25 s into a 4,000/s step:

| | |
|---|---|
| 4,000/s for 40 s | 159,998 requests, **100%**, p50 2,554 ms, p99 4,952 ms, max 5,123 ms |
| In flight at the histogram | 10,218 |
| Live heap | idle 43 MB → under load **1,546 MB** = **~144 KB per request in flight** |

| Per request in flight | KB | What it is |
|---|---|---|
| `byte[]` (76 arrays) | 83.0 | most likely I/O buffers: Tomcat's (socket read/write, request header, response) and HttpClient's (8 KB in and out per pooled connection). A histogram counts arrays, not who holds them |
| `char[]` (6 arrays) | 32.6 | most likely Tomcat's request/response character buffers |
| `jdk.internal.vm.StackChunk` | 6.1 | the parked virtual thread's stack |
| Tomcat `ByteChunk`, `CharChunk`, `MessageBytes` | 6.3 | Tomcat's parsed request (headers, URI) |
| everything else (maps, strings, headers, locks, request objects) | ~16 | the gateway's own classes (audit record, principal, trace context) do not appear among the largest entries |

Then 5,000/s for 30 s on a fresh JVM: **100%**, p99 4,954 ms, max 5,137 ms, 150,000 × 200, longest G1
pause **0.16 s**. During that step the gateway used 4.6 GB, and the Docker VM had **437 MB available
and 80 MB of its 1 GB swap left**. The VM also runs another container (`kind-control-plane`, 0.8 GB),
nginx (0.4 GB) and the load generator (0.7 GB). Run D's 27 s "pause" was the VM swapping, not G1.

#### Run G: a fixed 3.5 GB heap (`20261114-182639-in-docker-xmx3500m`)

To keep the JVM from growing into memory it does not need (the image's `-XX:MaxRAMPercentage=75` sizes
the heap at 75% of the container's memory, which here is the whole shared VM): `-Xms3500m -Xmx3500m`, G1, `eclipse-temurin:21-jre`.

| Step | Rate/s | Served/s | p50 ms | p99 ms | Max ms | Success | gw-cpu, memory | leased | VM available | Status codes |
|---|---|---|---|---|---|---|---|---|---|---|
| gateway | **4,000** | 3,434 | 2,560 | **4,951** | 5,039 | **100%** | 153%, 3.8 GB | 10,228 | 1.2 GB | 200 |
| gateway | 5,000 | 1,387 | 6,390 | 22,452 | 32,491 | 48.60% | 332%, 4.1 GB | 2,202 | 0.6 GB | 200 × 55,446, 503 × 39,862, timeout × 18,535, 502 × 255 |

Worse at 5,000/s: about 1.9 GB live with ~12,800 in flight leaves G1 too little room in 3.5 GB. The run
was stopped after this step.

### 2.2 Analysis

**The gateway is not slow; it is occupied.** In every passing step the gateway's p99 equals the
upstream's (4.95 s) and its CPU stays under 1.6 cores even with 10,000 requests in flight. What a slow
upstream costs is concurrency: by Little's law, in flight = rate × mean latency = rate × 2.55 s, and
each request in flight holds:

| Resource | Per request in flight | Limit met in this test | Default | Requests/s it allows at 2.55 s |
|---|---|---|---|---|
| Upstream pool connection | 1 | run A | 100 per upstream, 500 in total | **~39** per upstream |
| Tomcat connection | 1 | run C | 8,192 (Spring Boot) | ~3,200 |
| Per-tenant bulkhead permit | 1 | not met (test tenants set 10,000) | **50 per tenant** | **~20 per tenant** |
| Live memory | ~145 KB | runs D–G | heap = 75% of the container's memory | ~7,000 in flight per GB of live heap |
| Gateway → upstream local port | 1 | not met | ~28,000 per destination (Linux 32768–60999) | ~11,000 per upstream address |
| Load generator connection and port (test rig) | 1 | run B | macOS ~16,000 ports, Docker Desktop port forwarding | ~2,000 from the Mac |

So the bottlenecks, in the order they appeared, and what was done:

| # | Bottleneck | Found by | Improvement | Effect |
|---|---|---|---|---|
| 1 | Upstream pool (100 per upstream) | run A: leased 100, 9 waiting, 503 `UPSTREAM_SATURATED` | size it by Little's law (`gateway.proxy.max-connections-per-route` / `max-total-connections`) | 20 → 1,000+ requests/s |
| 2 | Docker Desktop port forwarding (test rig) | run B: gateway timer ≤ 5.73 s while the client timed out; slow baseline | generate load inside the Docker network | 1,000 → 3,000 requests/s measurable |
| 3 | Tomcat `max-connections` (8,192) | run C: in flight stuck at exactly 8,192 | raise `server.tomcat.max-connections` | 3,000 → 4,000–5,000 requests/s |
| 4 | Memory per request in flight (~145 KB) on a shared 8 GB VM | runs D–G: VM swapping at ~12,800 in flight | none that helped here: ZGC (run E) and a fixed smaller heap (run G) were worse; the default G1 sizing was best | limit on this machine: ~5,000 requests/s |

**Could code raise limit 4?** About 115 KB of the 145 KB are byte and char arrays, most likely the
buffers of Tomcat and Apache HttpClient rather than the gateway's; the next largest item is the
virtual thread's stack (6 KB), and the gateway's own classes are not among the largest entries. Smaller buffers (Tomcat's socket and response buffers, HttpClient's `Http1Config`
buffer size) could cut that, at the cost of more system calls per large body; whether it pays off
needs a machine with memory to spare, since here the results at 5,000/s varied with whatever else the
VM was doing (run D failed, run F passed). Not changed.

**What this means for running it:**
- Size the limits for the slowest upstream, not the average: connections ≥ rate × latency, with
  headroom, at the pool (per upstream and in total), at Tomcat, and in the per-tenant bulkhead (the
  default 50 allows a tenant only ~20 requests/s against a 2.5 s upstream).
- Budget memory as ~150 KB × requests in flight + ~0.5 GB, and give the container that much more than
  the heap; do not run the gateway in a VM it shares with its own load generator.
- With one upstream address, the gateway's local ports cap it at ~11,000 requests/s at 2.55 s (~28,000
  connections); more upstream instances or addresses lift that.
- Shorter upstream timeouts and hedging (step 29) bound the time a slow upstream can hold a request,
  and so the concurrency it costs.

### 2.3 Limits of this test

- One machine and one 8 GB Docker VM shared by the gateway, nginx, the load generator and an unrelated
  Kubernetes-in-Docker container, which caused the swapping in runs D and G. The 5,000/s result is a
  lower bound for the gateway.
- The delay is uniform 100 ms – 5 s; real latency distributions have long tails, which raise the mean
  (and so the concurrency) further.
- 30–40 s steps; one upstream address; GET only.

### 2.4 Reproduce

```bash
LT_POOL_PER_ROUTE=20000 LT_POOL_TOTAL=20000 LT_TOMCAT_MAX_CONNECTIONS=20000 \
  docker compose -f ops/loadtest/docker-compose.yml up -d --build
# from the Mac (runs A and B): limited by Docker Desktop's port forwarding at ~1,000-2,000/s
UPSTREAM=http://localhost:9091 UPSTREAM_IN_DOCKER=http://slow-upstream:80 TIMEOUT=10s P99_MS=5300 \
  MAX_CONNECTIONS=15000 RATES="250 500 1000 2000" ./scripts/loadtest.sh
# from inside the Docker network (runs C-G): the Linux build of vegeta in a container, same targets with
# the gateway's in-network address (the script writes targets.txt into the run's directory)
sed 's|http://localhost:9080|http://gateway:8080|' loadtest-results/<run>/targets.txt > /tmp/vegeta/targets.txt
docker run --rm --network gateway-loadtest_default --ulimit nofile=65536:65536 -v /tmp/vegeta:/w alpine:3.20 \
  /w/vegeta attack -targets=/w/targets.txt -rate=4000/s -duration=30s -timeout=10s -max-connections=30000 > 4000.bin
vegeta report 4000.bin
```

Each run's directory (not in the repository, see 1.6) has the per-step reports, CPU and memory
samples (`*.cpu`), pool samples (`*.pool`), `summary.txt` and `plot.html`; run F also has both class
histograms.

