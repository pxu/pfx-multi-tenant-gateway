#!/usr/bin/env bash
# Step load test with vegeta: how many requests per second the gateway serves within an SLO.
#
#   docker compose -f ops/loadtest/docker-compose.yml up -d --build
#   ./scripts/loadtest.sh
#
# Open model (constant arrival rate), so a slow gateway cannot hide latency by slowing the load down
# (coordinated omission). Each step runs DURATION at one rate; the run stops at the first step that
# misses the SLO (success < SUCCESS_MIN or p99 > P99_MS). Results: loadtest-results/<time>/.
#
# Env (defaults in brackets):
#   RATES ["500 1000 2000 4000 8000 12000"]  requests/s per step     DURATION [30s]  WARMUP [20s]
#   P99_MS [50]  SUCCESS_MIN [0.999]  TENANTS [4] (tokens rotate across them; one tenant's quota tops
#   out at 1,000,000/min)  BASELINE [1] (also load the upstream directly at the highest rate)
#   GATEWAY [http://localhost:9080]  MANAGEMENT [http://localhost:9081]  UPSTREAM [http://localhost:9090]
#   UPSTREAM_IN_DOCKER [http://upstream:80] (the upstream as the gateway reaches it)
#   CONTAINER [gateway-loadtest-gateway-1] (for CPU samples; empty = skip)
#   TIMEOUT [5s] per request (raise it for a slow upstream)
#   MAX_CONNECTIONS [2000] caps the load generator's connections: without a cap, a slow step makes vegeta
#   open thousands of new ones and the client runs out of ephemeral ports (EADDRNOTAVAIL; macOS has ~16k)
set -euo pipefail

command -v vegeta >/dev/null || { echo "vegeta not found: brew install vegeta (or go install github.com/tsenart/vegeta/v12@latest)"; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }

G="${GATEWAY:-http://localhost:9080}"
M="${MANAGEMENT:-http://localhost:9081}"
UP="${UPSTREAM:-http://localhost:9090}"
UP_INTERNAL="${UPSTREAM_IN_DOCKER:-http://upstream:80}"
RATES="${RATES:-500 1000 2000 4000 8000 12000}"
DURATION="${DURATION:-30s}"
WARMUP="${WARMUP:-20s}"
P99_MS="${P99_MS:-50}"
SUCCESS_MIN="${SUCCESS_MIN:-0.999}"
TENANTS="${TENANTS:-4}"
CONTAINER="${CONTAINER-gateway-loadtest-gateway-1}"
OUT="loadtest-results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }
seconds() { python3 -c "import re, sys; v = sys.argv[1]; m = re.fullmatch(r'(\d+)(ms|s|m)', v); \
print(int(m[1]) / {'ms': 1000, 's': 1, 'm': 60}[m[2]])" "$1"; }

# ---- setup: tenants with quotas far above the test, one route to the no-op upstream, tokens ----
curl -sf "$M/actuator/health/readiness" >/dev/null || { echo "gateway not ready at $M (start the stack first)"; exit 1; }
SESSION=$(curl -sf -X POST "$G/admin/login" -H 'Content-Type: application/json' \
  -d "{\"user\": \"${OPS_USER:-ops}\", \"key\": \"${OPS_KEY:-local-ops-key}\", \"role\": \"admin\"}" | json '["token"]')
ADMIN=(-H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json')

# POST, or PUT when it already exists (re-runs against the same stack)
upsert() {
  local collection="$1" id="$2" body="$3"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$G/admin/$collection" "${ADMIN[@]}" -d "$body")
  if [ "$code" = 409 ]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$G/admin/$collection/$id" "${ADMIN[@]}" -d "$body")
  fi
  case "$code" in 200|201) ;; *) echo "setting up $collection/$id failed: HTTP $code"; exit 1 ;; esac
}

upsert routes lt "{\"id\": \"lt\", \"pathPrefix\": \"/lt\", \"service\": \"orders\",
  \"upstreams\": [{\"url\": \"$UP_INTERNAL\", \"weight\": 1}]}"
: > "$OUT/targets.txt"
for i in $(seq "$TENANTS"); do
  t="lt-$i"
  upsert tenants "$t" "{\"id\": \"$t\", \"name\": \"$t\", \"requestsPerMinute\": 1000000, \"burst\": 1000000,
    \"maxConcurrentRequests\": 10000, \"allowedPathPrefixes\": [\"/lt\"], \"grantableScopes\": [\"orders\"]}"
  curl -sf -o /dev/null -X PUT "$G/admin/rbac/tenants/$t/users/loader" "${ADMIN[@]}" -d '{"roles": ["orders-reader"]}'
  token=$(curl -sf -X POST "$G/admin/tenants/$t/tokens" "${ADMIN[@]}" \
    -d '{"user": "loader", "role": "orders-reader", "scopes": ["orders"], "ttlSeconds": 3600}' | json '["token"]')
  printf 'GET %s/lt/items/1\nAuthorization: Bearer %s\n\n' "$G" "$token" >> "$OUT/targets.txt"
done
printf 'GET %s/lt/items/1\n\n' "$UP" > "$OUT/baseline-targets.txt"

# ---- helpers ----------------------------------------------------------------------------------
metric() { # sum of one counter in the Prometheus scrape (0 if absent)
  curl -sf "$M/actuator/prometheus" | python3 -c "import sys; print(sum(float(l.split()[-1]) for l in sys.stdin \
if l.startswith('$1') and not l.startswith('#')))"
}

attack() { # name rate duration targets -> results bin, prints a summary row
  local name="$1" rate="$2" duration="$3" targets="$4" cpu="" pool="-"
  ( sleep "$(python3 -c "print($(seconds "$duration") / 2)")"
    if [ -n "$CONTAINER" ] && command -v docker >/dev/null; then
      docker stats --no-stream --format '{{.CPUPerc}}' "$CONTAINER" > "$OUT/$name.cpu" 2>/dev/null || true
    fi
    # upstream pool halfway through: leased/pending (pending > 0 = requests waiting for a connection)
    echo "$(metric 'httpcomponents_httpclient_pool_total_connections{httpclient="upstream",state="leased"')/$(metric \
      'httpcomponents_httpclient_pool_total_pending')" > "$OUT/$name.pool" 2>/dev/null || true ) &
  vegeta attack -targets="$targets" -rate="$rate/s" -duration="$duration" -timeout="${TIMEOUT:-5s}" \
    -max-connections="${MAX_CONNECTIONS:-2000}" > "$OUT/$name.bin"
  wait
  [ -f "$OUT/$name.cpu" ] && cpu=$(cat "$OUT/$name.cpu")
  [ -f "$OUT/$name.pool" ] && pool=$(python3 -c "a, b = open('$OUT/$name.pool').read().split('/'); print(f'{float(a):.0f}/{float(b):.0f}')" 2>/dev/null || echo -)
  vegeta report -type=json "$OUT/$name.bin" > "$OUT/$name.json"
  vegeta report "$OUT/$name.bin" > "$OUT/$name.txt"
  python3 - "$OUT/$name.json" "$name" "$rate" "${cpu:--}" "$pool" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
lat = {k: v / 1e6 for k, v in r["latencies"].items()}
codes = " ".join(f"{k}:{v}" for k, v in sorted(r["status_codes"].items()))
print(f"{sys.argv[2]:<14}{sys.argv[3]:>7}{r['throughput']:>10.0f}{lat['50th']:>9.2f}{lat['99th']:>9.2f}"
      f"{lat['max']:>9.1f}{100 * r['success']:>9.2f}%  {sys.argv[4]:>8}  {sys.argv[5]:>11}  {codes}")
PY
}

passes() { # SLO check on a step's json
  python3 - "$1" "$P99_MS" "$SUCCESS_MIN" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
ok = r["latencies"]["99th"] / 1e6 <= float(sys.argv[2]) and r["success"] >= float(sys.argv[3])
sys.exit(0 if ok else 1)
PY
}

header() { printf '%-14s%7s%10s%9s%9s%9s%10s  %8s  %11s  %s\n' step rate/s served/s p50ms p99ms maxms success gw-cpu leased/pend status-codes; }

# ---- run --------------------------------------------------------------------------------------
{
echo "Gateway $G, upstream $UP, $TENANTS tenants, SLO: p99 <= ${P99_MS}ms and success >= $SUCCESS_MIN"
echo "Steps: $RATES (each $DURATION), warm-up $WARMUP"
echo
header
first=${RATES%% *}
attack warmup "$first" "$WARMUP" "$OUT/targets.txt"
if [ "${BASELINE:-1}" = 1 ]; then
  last=${RATES##* }
  attack "upstream-$last" "$last" "$DURATION" "$OUT/baseline-targets.txt"
fi
dropped0=$(metric gateway_audit_dropped_total); opened0=$(metric gateway_upstream_connections_opened_total)
best=""
for rate in $RATES; do
  attack "gateway-$rate" "$rate" "$DURATION" "$OUT/targets.txt"
  if passes "$OUT/gateway-$rate.json"; then best="$rate"; else echo "-> $rate/s misses the SLO; stopping"; break; fi
done
echo
echo "Highest step within the SLO: ${best:-none} requests/s"
echo "During the steps: audit entries dropped $(python3 -c "print(int($(metric gateway_audit_dropped_total) - $dropped0))")," \
     "new upstream connections $(python3 -c "print(int($(metric gateway_upstream_connections_opened_total) - $opened0))"),"\
     "breakers not closed $(curl -sf "$M/actuator/prometheus" | grep '^gateway_circuit_state{' | grep -vc ' 0.0$' || true)"
echo "Latency plots: vegeta plot $OUT/gateway-*.bin > $OUT/plot.html"
} | tee "$OUT/summary.txt"
