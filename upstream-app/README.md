# upstream-app: a demo upstream for the gateway

A small Go service (standard library only) to put behind the gateway.

| Endpoint | |
|---|---|
| `GET /delay/{ms}` | wait `ms` milliseconds (at most `MAX_DELAY_MS`, default 30000), then echo |
| `GET /random-delay?min=100&max=5000` | wait a random time in [min, max] ms, then echo |
| `GET /status/{code}` | answer with that status code |
| `GET /healthz` | `ok`; never fails or waits |
| anything else | echo method, path, query and headers as JSON |

Env:
- `VERSION`: sent back in `X-Upstream-Version` on every response.
- `FAIL_PCT` (0–100): that share of requests, except `/healthz`, gets 503. This makes a canary the "bad release".
- `PORT`: defaults to 8080.

A delay stops early if the caller goes away (a cancelled hedge, a gateway timeout).

## Run

```bash
go test -race ./...                                               # from upstream-app/
docker compose -f upstream-app/docker-compose.yml up -d --build   # gateway :9180, stable :9201, canary :9202
./scripts/demo-do-upstream.sh                                      # from the repo root
```

On DigitalOcean App Platform it runs as two internal components of the gateway's app, `stable` and
`canary` (`FAIL_PCT=60`), reached as `http://stable:8080` and `http://canary:8080`
([deploy/do-app.yaml](../deploy/do-app.yaml)). Deploy and demo with `./scripts/do-app.sh deploy` and
`./scripts/do-app.sh demo`.

## Calling an upstream directly

**Locally:**

```bash
curl -i localhost:9201/healthz                                    # 200 ok, X-Upstream-Version: stable
curl -s 'localhost:9201/orders/7?x=1' -H 'X-Tenant-Id: acme'      # echo
curl -s localhost:9201/delay/1500                                 # echo after 1.5 s
curl -i localhost:9201/status/418                                 # 418
for i in $(seq 10); do curl -s -o /dev/null -w '%{http_code} ' localhost:9202/orders/1; done; echo
                                                                  # canary: about 6 in 10 are 503
```

**On App Platform:** `stable` and `canary` have no public port. Call them from a console in the
gateway's container, which has `curl`; the upstream image has no shell:

```bash
doctl apps console $(doctl apps list --format ID,Spec.Name --no-header | awk '$2 == "pfx-multi-tenant-gateway" {print $1}') \
  pfx-multi-tenant-gateway
# The console shows no prompt; wait a few seconds, then type. `exit` leaves.
curl -i http://stable:8080/healthz
curl -i http://canary:8080/status/418
```

**Through the gateway:** a route whose only upstream is `http://stable:8080` sends everything under
its prefix to stable; `demo-do-upstream.sh` creates `/anything`, `/delay`, `/random-delay` and
`/status` that way.

## As separate public apps

For a gateway running elsewhere (e.g. locally), `deploy/stable.yaml` and `deploy/canary.yaml` deploy
each variant as its own app with its own host. The gateway keeps one breaker per host:port, so the two
variants must not share a host.

```bash
doctl apps create --spec upstream-app/deploy/stable.yaml
doctl apps create --spec upstream-app/deploy/canary.yaml
doctl apps list --format Spec.Name,DefaultIngress                   # the two URLs
STABLE_URL=https://gw-upstream-stable-... CANARY_URL=https://gw-upstream-canary-... ./scripts/demo-do-upstream.sh
doctl apps delete <app-id>                                          # afterwards (about $5/month each)
```
