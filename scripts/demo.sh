#!/usr/bin/env bash
# Walks through the main behaviours against the docker compose stack.
set -euo pipefail

G="${GATEWAY:-http://localhost:8080}"
# Log in as user "ops", choosing its "admin" role (one role per session).
SESSION=$(curl -sf -X POST "$G/admin/login" -H 'Content-Type: application/json' \
  -d "{\"user\": \"${OPS_USER:-ops}\", \"key\": \"${OPS_KEY:-local-ops-key}\", \"role\": \"admin\"}" |
  python3 -c 'import sys, json; print(json.load(sys.stdin)["token"])')
ADMIN=(-H "Authorization: Bearer $SESSION" -H 'Content-Type: application/json')
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
status() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }

step "Create tenant 'acme': 5 requests/minute, burst 5, may call /orders, may reach service orders"
curl -s -X POST "$G/admin/tenants" "${ADMIN[@]}" -d '{
  "id": "acme", "name": "Acme", "requestsPerMinute": 5, "burst": 5,
  "allowedPathPrefixes": ["/orders"], "grantableScopes": ["orders"]}'; echo

step "RBAC: assign acme's user rita the built-in role orders-reader; create a custom role orders-auditor"
curl -s -X PUT "$G/admin/rbac/tenants/acme/users/rita" "${ADMIN[@]}" -d '{"roles": ["orders-reader"]}'; echo
curl -s -X PUT "$G/admin/rbac/roles/orders-auditor" "${ADMIN[@]}" -d '{"permissions": ["orders:read"]}'; echo

step "Create routes /orders (service orders) and /billing (service billing), both to the echo upstream"
curl -s -X POST "$G/admin/routes" "${ADMIN[@]}" -d '{
  "id": "orders", "pathPrefix": "/orders", "upstreams": [{"url": "http://echo:8080", "weight": 1}]}'; echo
curl -s -X POST "$G/admin/routes" "${ADMIN[@]}" -d '{
  "id": "billing", "pathPrefix": "/billing", "upstreams": [{"url": "http://echo:8080", "weight": 1}]}'; echo

step "Issue a token for rita, acting as orders-reader, for service orders"
TOKEN=$("$(dirname "$0")/issue-token.sh" acme rita orders-reader orders)
echo "${TOKEN:0:40}..."

step "Allowed -> 200. The echo shows what the upstream received: X-Tenant-Id, X-User-Id, X-Role set by the gateway, no Authorization"
curl -s "$G/orders/42?x=1" -H "Authorization: Bearer $TOKEN" -H 'X-Tenant-Id: spoofed' -H 'X-Role: admin' |
  python3 -c 'import sys,json; h=json.load(sys.stdin)["headers"]; print({k: v for k, v in h.items() if k.startswith("x-") or k == "authorization"})'

step "No token -> 401";              status "$G/orders/1"
step "Path not in allowlist -> 403"; status "$G/billing/1" -H "Authorization: Bearer $TOKEN"
step "DELETE as orders-reader -> 403 (RBAC: no orders:delete)"; status -X DELETE "$G/orders/1" -H "Authorization: Bearer $TOKEN"
step "Traversal attempt -> 400";     status --path-as-is "$G/orders/../billing" -H "Authorization: Bearer $TOKEN"
step "Unknown route -> 404";         status "$G/nope" -H "Authorization: Bearer $TOKEN"
step "6 more requests -> the bucket (5, refilled at 5/min) runs dry -> 429"
for _ in $(seq 6); do status "$G/orders/1" -H "Authorization: Bearer $TOKEN"; done | sort | uniq -c

step "Audit log (last 5 lines)"
docker compose logs gateway --no-log-prefix 2>/dev/null | grep '"requestId"' | tail -5 || true
