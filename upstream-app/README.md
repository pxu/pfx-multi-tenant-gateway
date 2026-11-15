# upstream-app: a real upstream for the gateway

A small service to put behind the gateway: it echoes what it receives, waits as long as it is told,
answers any status code, and fails a share of requests on purpose. It is here in two implementations
with the same endpoints, settings and echo format:

| | Source | Used by |
|---|---|---|
| **Spring Boot** (Java 21) | `pom.xml`, `src/` | the local stacks (`scripts/stack.sh`): demo, upstream demo, load tests |
| **Go** (standard library only) | `main.go`, `main_test.go`, `go.mod` | kept as the original; run it with `go run .` |

## Endpoints

| Endpoint | |
|---|---|
| `GET /delay/{ms}` | wait `ms` milliseconds (at most `MAX_DELAY_MS`), then echo |
| `GET /random-delay?min=100&max=5000` | wait a uniform random time in [min, max] ms, then echo |
| `GET /status/{code}` | answer with that status code |
| `GET /healthz` | `ok`; never fails, never waits |
| anything else | echo the method, path, query and headers as JSON: `{"version", "method", "path", "query", "delayMs", "headers": {"x-tenant-id": ["acme"], ...}}` (header names lower case) |

Every response carries `X-Upstream-Version`.

## Settings (environment variables)

| Variable | Default | |
|---|---|---|
| `PORT` | `8080` | listen port |
| `VERSION` | `stable` | sent as `X-Upstream-Version` and in the echo |
| `FAIL_PCT` | `0` | 0-100: that share of requests (except `/healthz`) is answered 503, which makes a canary the "bad release" |
| `MAX_DELAY_MS` | `30000` | cap for `/delay` and `/random-delay` |
| `PLAIN_OK` | `false` | Spring Boot only: echo paths answer `ok` as text (the cheapest upstream, for load tests) |
| `DELAY_MIN_MS`, `DELAY_MAX_MS` | `0`, `0` | Spring Boot only: every request (except `/healthz`) first waits a uniform random time in [min, max] ms (the slow load-test upstream) |
| `MAX_CONNECTIONS` | `30000` | Spring Boot only: Tomcat's connection limit, so thousands of slow requests can wait at once |

Invalid values (`FAIL_PCT` outside 0-100, a negative delay, min above max) stop the application at startup.

Difference between the two: if the caller goes away (a cancelled hedge, a gateway timeout), the Go
version stops waiting at once; a servlet cannot cheaply detect that, so the Spring Boot version finishes
its delay (at most `MAX_DELAY_MS`) before discarding the response. The gateway behaves the same either way.

## Spring Boot

```bash
./mvnw -f upstream-app/pom.xml verify                 # from the repository root: tests + coverage gate (>= 80% lines)
./mvnw -f upstream-app/pom.xml package -DskipTests
VERSION=canary FAIL_PCT=60 PORT=9202 java -jar upstream-app/target/upstream-app-0.1.0-SNAPSHOT.jar
```

Virtual threads are on, so a delayed request parks a virtual thread rather than holding a platform one.
Code: `UpstreamController` (endpoints), `VersionAndFailureFilter` (version header, injected failures,
slow mode), `UpstreamProperties` (settings and their validation).

The local stacks start it for you:

```bash
./scripts/stack.sh upstream-demo up    # gateway :9180/:9181 + stable :9201 + canary :9202 (FAIL_PCT=60)
./scripts/demo-do-upstream.sh
./scripts/stack.sh upstream-demo down
```

## Go

```bash
go test -race ./...                                   # from upstream-app/
VERSION=canary FAIL_PCT=60 PORT=9202 go run .
```

## On DigitalOcean App Platform

`deploy/stable.yaml` and `deploy/canary.yaml` are two separate apps (each its own `*.ondigitalocean.app`
host, because the gateway keeps one circuit breaker per upstream host:port).

> **Needs updating:** both specs still build with `dockerfile_path: upstream-app/Dockerfile`, which was
> removed along with the rest of the Docker setup. Point them at a buildpack build (Go or Java) before
> using them again.

```bash
doctl auth init                                        # once, in your own terminal (API token, scope: app)
doctl apps create --spec upstream-app/deploy/stable.yaml
doctl apps create --spec upstream-app/deploy/canary.yaml
doctl apps list --format Spec.Name,DefaultIngress      # the two URLs
./scripts/stack.sh upstream-demo up                    # the gateway (the local upstreams start too; unused)
STABLE_URL=https://gw-upstream-stable-....ondigitalocean.app \
CANARY_URL=https://gw-upstream-canary-....ondigitalocean.app ./scripts/demo-do-upstream.sh
doctl apps delete <app-id>                             # afterwards (each app costs about $5/month while it exists)
```
