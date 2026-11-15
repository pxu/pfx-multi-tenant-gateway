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

```bash
go test -race ./...                                   # from upstream-app/
docker compose -f upstream-app/docker-compose.yml up -d --build   # gateway :9180 + stable :9201 + canary :9202
./scripts/demo-do-upstream.sh
```

## On DigitalOcean App Platform

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
