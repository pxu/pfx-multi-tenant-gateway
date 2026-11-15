#!/usr/bin/env bash
# The gateway in front of real upstreams (upstream-app: echo, delays, status codes, injected failures):
# a stable and a canary (60% of its requests fail), on DigitalOcean App Platform or locally.
#
#   docker compose -f upstream-app/docker-compose.yml up -d --build        # gateway + both upstreams, locally
#   ./scripts/demo-do-upstream.sh
#
#   STABLE_URL=https://gw-upstream-stable-....ondigitalocean.app \
#   CANARY_URL=https://gw-upstream-canary-....ondigitalocean.app ./scripts/demo-do-upstream.sh
#
# Env: GATEWAY [http://localhost:9180], MANAGEMENT [http://localhost:9181],
#      STABLE_URL [http://stable:8080], CANARY_URL [http://canary:8080] (as the gateway reaches them)
set -euo pipefail

G="${GATEWAY:-http://localhost:9180}"
M="${MANAGEMENT:-http://localhost:9181}"
STABLE="${STABLE_URL:-http://stable:8080}"
CANARY="${CANARY_URL:-http://canary:8080}"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }

curl -sf "$M/actuator/health/readiness" >/dev/null || { echo "gateway not ready at $M"; exit 1; }
SESSION=$(curl -sf -X POST "$G/admin/login" -H 'Content-Type: application/json' \
  -d "{\"user\": \"${OPS_USER:-ops}\", \"key\": \"${OPS_KEY:-local-ops-key}\", \"role\": \"admin\"}" | json '["token"]')
ADMIN=(-H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json')

# POST, or PUT when it already exists (re-runs)
upsert() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$G/admin/$1" "${ADMIN[@]}" -d "$3")
  [ "$code" = 409 ] && code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$G/admin/$1/$2" "${ADMIN[@]}" -d "$3")
  case "$code" in 200|201) ;; *) echo "setting up $1/$2 failed: HTTP $code"; exit 1 ;; esac
}
route() { # id prefix upstreams-json [extra fields]
  upsert routes "$1" "{\"id\": \"$1\", \"pathPrefix\": \"$2\", \"service\": \"orders\", \"upstreams\": $3 ${4:-}}"
}

step "Setup: tenant 'demo', user dora (orders-reader), routes to stable $STABLE and canary $CANARY"
upsert tenants demo '{"id": "demo", "name": "Demo", "requestsPerMinute": 6000, "burst": 300,
  "allowedPathPrefixes": ["/anything", "/orders", "/delay", "/random-delay", "/status"], "grantableScopes": ["orders"]}'
curl -sf -o /dev/null -X PUT "$G/admin/rbac/tenants/demo/users/dora" "${ADMIN[@]}" -d '{"roles": ["orders-reader"]}'
route anything /anything "[{\"url\": \"$STABLE\", \"weight\": 1}]"
route orders /orders "[{\"url\": \"$STABLE\", \"weight\": 90}, {\"url\": \"$CANARY\", \"weight\": 10}]"
route delay /delay "[{\"url\": \"$STABLE\", \"weight\": 1}]"
route random-delay /random-delay "[{\"url\": \"$STABLE\", \"weight\": 1}]" ', "hedgeAfterMs": 1500'
route status /status "[{\"url\": \"$STABLE\", \"weight\": 1}]"
TOKEN=$(curl -sf -X POST "$G/admin/tenants/demo/tokens" "${ADMIN[@]}" \
  -d '{"user": "dora", "role": "orders-reader", "scopes": ["orders"]}' | json '["token"]')
AUTH=(-H "Authorization: Bearer $TOKEN")
echo "ok"

step "1. Faithful proxying: what the upstream received (client sent a spoofed X-Tenant-Id and X-Role)"
curl -sf "$G/anything/hello?x=1" "${AUTH[@]}" -H 'X-Tenant-Id: someone-else' -H 'X-Role: admin' | python3 -c '
import sys, json
e = json.load(sys.stdin); h = e["headers"]
print("upstream", e["version"], "got", e["method"], e["path"] + "?" + e["query"])
for k in ("x-tenant-id", "x-user-id", "x-role", "x-request-id", "x-forwarded-for", "traceparent", "authorization"):
    print(f"  {k:<16} {h.get(k, ["(not sent)"])[0]}")'

step "2. Canary 90/10, canary failing 60%: 200 requests (its breaker opens, traffic falls back to stable)"
for _ in $(seq 200); do
  curl -s -o /dev/null -D - "$G/orders/1" "${AUTH[@]}" | python3 -c '
import sys
lines = sys.stdin.read().splitlines()
status = lines[0].split()[1] if lines else "?"
version = next((l.split(":", 1)[1].strip() for l in lines if l.lower().startswith("x-upstream-version:")), "-")
print(status, version)'
done | sort | uniq -c | sort -rn
curl -s "$M/actuator/prometheus" | grep -E '^gateway_circuit_state\{|^gateway_upstream_fallbacks_total' |
  sed 's/^/  /' || true
echo "  (gateway_circuit_state: 0 closed, 1 open, 2 half-open)"

step "3. Sticky canary: the same tenant always lands on the same upstream (10 requests)"
route orders /orders "[{\"url\": \"$STABLE\", \"weight\": 50}, {\"url\": \"$CANARY\", \"weight\": 50}]" ', "sticky": true'
for _ in $(seq 10); do
  curl -s -o /dev/null -D - "$G/orders/2" "${AUTH[@]}" | grep -i '^x-upstream-version' | tr -d '\r'
done | sort | uniq -c

step "4. Slow upstream: /delay/12000 > the gateway's 10s upstream timeout -> 504 after ~20s (a timed-out GET is retried once)"
curl -s -o /dev/null -w '  HTTP %{http_code} after %{time_total}s\n' "$G/delay/12000" "${AUTH[@]}"

step "5. Hedging: /random-delay?min=100&max=4000 with hedgeAfterMs 1500 (10 requests)"
for _ in $(seq 10); do
  curl -s -o /dev/null -w '%{http_code} %{time_total}s\n' "$G/random-delay?min=100&max=4000" "${AUTH[@]}"
done | awk '{print "  " $0}'
curl -s "$M/actuator/prometheus" | grep -E '^gateway_upstream_hedges' | sed 's/^/  /' || true

step "6. Upstream errors pass through unchanged: /status/418"
curl -s -w '  HTTP %{http_code}\n' "$G/status/418" "${AUTH[@]}" | sed 's/^/  /'

step "7. Policy rejections never reach the upstream"
printf '  no token -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' "$G/orders/1")"
printf '  DELETE as orders-reader -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$G/orders/1" "${AUTH[@]}")"
