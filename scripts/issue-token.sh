#!/usr/bin/env bash
# Issue a token for one of a tenant's users, acting as one of that user's roles.
#   ./scripts/issue-token.sh <tenant-id> <user> <role> [service ...]   (no services = all the tenant may reach)
# Env: GATEWAY (default http://localhost:8080), OPS_USER (default ops), OPS_KEY (default local-ops-key),
#      TTL_SECONDS (default 900). Logs in with the admin role (the one that has tokens:issue).
set -euo pipefail

usage="usage: issue-token.sh <tenant-id> <user> <role> [service ...]"
tenant="${1:?$usage}"; user="${2:?$usage}"; role="${3:?$usage}"
shift 3
gateway="${GATEWAY:-http://localhost:8080}"
session=$(curl -sf -X POST "$gateway/admin/login" -H 'Content-Type: application/json' \
  -d "{\"user\": \"${OPS_USER:-ops}\", \"key\": \"${OPS_KEY:-local-ops-key}\", \"role\": \"admin\"}" |
  python3 -c 'import sys, json; print(json.load(sys.stdin)["token"])')
scopes=$(printf '"%s",' "$@")
scopes="[${scopes%,}]"
[ "$scopes" = '[""]' ] && scopes='[]'

curl -sf -X POST "$gateway/admin/tenants/$tenant/tokens" \
  -H "Authorization: Bearer $session" -H 'Content-Type: application/json' \
  -d "{\"user\": \"$user\", \"role\": \"$role\", \"scopes\": $scopes, \"ttlSeconds\": ${TTL_SECONDS:-900}}" |
  python3 -c 'import sys, json; print(json.load(sys.stdin)["token"])'
