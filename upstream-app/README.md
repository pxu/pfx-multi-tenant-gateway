# upstream-app: a real upstream for the gateway

A small Go service (standard library only) to put behind the gateway: it echoes what it receives, waits
as long as it is told, answers any status code, and fails a share of requests on purpose.

| Endpoint | |
|---|---|
| `GET /delay/{ms}` | wait `ms` milliseconds (at most `MAX_DELAY_MS`), then echo |
| `GET /random-delay?min=100&max=5000` | wait a uniform random time in [min, max] ms, then echo |
| `GET /status/{code}` | answer with that status code |
| `GET /healthz` | `ok`; never fails, never waits |
| anything else | echo the method, path, query and headers as JSON |

Every response carries `X-Upstream-Version` (env `VERSION`). `FAIL_PCT` (0-100) answers that share of
requests (except `/healthz`) with 503, which makes a canary the "bad release". `PORT` defaults to 8080.
If the caller goes away (a cancelled hedge, a gateway timeout), a delay stops waiting.

Where it runs: locally in Docker (below), and on DigitalOcean App Platform as two internal components
of the gateway's app, `stable` and `canary` (`FAIL_PCT=60`), built from this directory's `Dockerfile`
([deploy/do-app.yaml](../deploy/do-app.yaml)).

```bash
go test -race ./...                                   # from upstream-app/
docker compose -f upstream-app/docker-compose.yml up -d --build   # gateway :9180 + stable :9201 + canary :9202
./scripts/demo-do-upstream.sh
```

## On DigitalOcean App Platform

**Together with the gateway (recommended):** [deploy/do-app.yaml](../deploy/do-app.yaml) runs this
service twice (`stable`, and `canary` with `FAIL_PCT=60`) as internal components of the gateway's app;
the gateway reaches them as `http://stable:8080` and `http://canary:8080`. Deploy and demo with
`./scripts/do-app.sh deploy` and `./scripts/do-app.sh demo` (see the main README).

## Calling an upstream directly (without the gateway)

Useful to see what an upstream does on its own: its version, an injected failure, a delay, before
putting the gateway in front of it. No token is needed; the upstream has no authentication.

**Locally.** Either run it straight from source, or use the ports the compose stack publishes:

```bash
VERSION=canary FAIL_PCT=60 PORT=9202 go run .        # from upstream-app/ (Go 1.22), or:
docker compose -f upstream-app/docker-compose.yml up -d stable canary   # stable :9201, canary :9202

curl -i localhost:9201/healthz                       # 200 ok, X-Upstream-Version: stable
curl -s 'localhost:9201/orders/7?x=1' -H 'X-Tenant-Id: acme'   # echo: version, method, path, query, headers
curl -s localhost:9201/delay/1500                    # echo after 1.5 s ("delayMs": 1500)
curl -s 'localhost:9201/random-delay?min=100&max=2000'
curl -i localhost:9201/status/418                    # 418, "status 418 from stable"
for i in $(seq 10); do curl -s -o /dev/null -w '%{http_code} ' localhost:9202/orders/1; done; echo
                                                     # canary: about 6 in 10 are 503 "injected failure (canary)"
```

**On App Platform.** `stable` and `canary` have no public port, so nothing outside the app can reach
them. Call them from a console in the gateway's container (it has `curl` and `wget`; the upstream's own
image is distroless and has no shell):

```bash
doctl apps console $(doctl apps list --format ID,Spec.Name --no-header | awk '$2 == "pfx-multi-tenant-gateway" {print $1}') \
  pfx-multi-tenant-gateway
# The console shows NO prompt (the container's bash has an empty PS1), so it looks stuck: it is
# waiting for input. Wait a few seconds for it to connect, then type commands; `exit` leaves.
PS1='gateway$ '                                      # optional: get a visible prompt first
curl -i http://stable:8080/healthz
curl -s http://stable:8080/anything/hello?x=1
curl -i http://canary:8080/status/418
for i in $(seq 10); do curl -s -o /dev/null -w '%{http_code} ' http://canary:8080/orders/1; done; echo
exit
```

**Through the gateway, one upstream only.** The gateway appends the client's path to the upstream URL
unchanged, so a route whose only upstream is `http://stable:8080` (or `canary`) sends everything under
its prefix to that one upstream; `demo-do-upstream.sh` creates `/anything`, `/delay`, `/random-delay` and
`/status` that way (all to stable). A token and an allowed prefix are needed, as for any route.

**As two separate public apps** (for a gateway running elsewhere, such as locally in Docker):

`deploy/stable.yaml` and `deploy/canary.yaml` are two separate apps (each its own `*.ondigitalocean.app`
host, because the gateway keeps one circuit breaker per upstream host:port). The repository is
private, so first connect DigitalOcean's GitHub app to it (Apps > Create App > GitHub); the specs build
`upstream-app/` from the `main` branch.

```bash
doctl auth init                                        # once, in your own terminal (API token, scope: app)
doctl apps create --spec upstream-app/deploy/stable.yaml
doctl apps create --spec upstream-app/deploy/canary.yaml
doctl apps list --format Spec.Name,DefaultIngress      # the two URLs
docker compose -f upstream-app/docker-compose.yml up -d --build gateway
STABLE_URL=https://gw-upstream-stable-....ondigitalocean.app \
CANARY_URL=https://gw-upstream-canary-....ondigitalocean.app ./scripts/demo-do-upstream.sh
doctl apps delete <app-id>                             # afterwards (each app costs about $5/month while it exists)
```
