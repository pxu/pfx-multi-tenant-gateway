#!/usr/bin/env bash
# The gateway on DigitalOcean App Platform (deploy/do-app.yaml): one app with the gateway and the
# stable/canary upstreams, each built by App Platform from its Dockerfile.
#
#   ./scripts/do-app.sh validate   fill in the secrets and validate the spec with doctl (changes nothing)
#   ./scripts/do-app.sh deploy     create the app, or update it to the spec in deploy/do-app.yaml; waits
#   ./scripts/do-app.sh demo       run scripts/demo-do-upstream.sh against the deployed gateway
#   ./scripts/do-app.sh login      print an admin session token (role admin) for curl
#   ./scripts/do-app.sh logs       follow the gateway's logs (audit lines included)
#   ./scripts/do-app.sh url        print the app's public URL
#
# Needs doctl, logged in with a token that may read and write apps (doctl auth init). Secrets live in
# ~/.config/pfx-gateway/ (mode 600) and are created on first deploy:
#   ops-key               the key of operator "ops" (the app only stores its SHA-256)
#   jwt-private-key.pem   the RSA key that signs tokens (so they survive redeploys)
# Env: APP_NAME [pfx-multi-tenant-gateway], SECRETS_DIR [~/.config/pfx-gateway]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC="$ROOT/deploy/do-app.yaml"
APP_NAME="${APP_NAME:-pfx-multi-tenant-gateway}"
SECRETS="${SECRETS_DIR:-$HOME/.config/pfx-gateway}"
usage="usage: do-app.sh validate|deploy|demo|login|logs|url"

command -v doctl >/dev/null || { echo "doctl not found: brew install doctl, then doctl auth init"; exit 1; }

app_id() { doctl apps list --format ID,Spec.Name --no-header | awk -v n="$APP_NAME" '$2 == n {print $1}'; }
app_url() { doctl apps get "$1" --format DefaultIngress --no-header; }

require_app() {
  local id; id=$(app_id)
  [ -n "$id" ] || { echo "no app named $APP_NAME (run: ./scripts/do-app.sh deploy)" >&2; exit 1; }
  echo "$id"
}

ensure_secrets() {
  mkdir -p "$SECRETS"; chmod 700 "$SECRETS"
  if [ ! -s "$SECRETS/ops-key" ]; then
    openssl rand -hex 32 > "$SECRETS/ops-key"
    echo "created the ops key: $SECRETS/ops-key"
  fi
  if [ ! -s "$SECRETS/jwt-private-key.pem" ]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SECRETS/jwt-private-key.pem" 2>/dev/null
    echo "created the JWT signing key: $SECRETS/jwt-private-key.pem"
  fi
  chmod 600 "$SECRETS"/*
}

ops_key() { tr -d '\n' < "$SECRETS/ops-key"; }

# The spec with its placeholders filled in, written to a private temp file (the caller deletes it).
render_spec() {
  local out="$1"
  GATEWAY_OPS_KEY_SHA256=$(printf %s "$(ops_key)" | shasum -a 256 | cut -d' ' -f1) \
  GATEWAY_JWT_PRIVATE_KEY=$(grep -v -- '-----' "$SECRETS/jwt-private-key.pem" | tr -d '\n') \
  python3 - "$SPEC" "$out" <<'PY'
import os, re, sys
text = open(sys.argv[1]).read()
def fill(m):
    value = os.environ.get(m.group(1))
    if not value:
        sys.exit(f"missing value for {m.group(1)}")
    return value
open(sys.argv[2], "w").write(re.sub(r"\$\{([A-Z0-9_]+)\}", fill, text))
PY
}

login() { # prints an admin session token
  local g="$1"
  curl -sf -X POST "$g/admin/login" -H 'Content-Type: application/json' \
    -d "{\"user\": \"ops\", \"key\": \"$(ops_key)\", \"role\": \"admin\"}" |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["token"])'
}

case "${1:-}" in
  validate)
    ensure_secrets
    umask 077
    tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
    render_spec "$tmp"
    doctl apps spec validate "$tmp" >/dev/null && echo "deploy/do-app.yaml is valid"
    ;;
  deploy)
    ensure_secrets
    umask 077
    tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
    render_spec "$tmp"
    doctl apps spec validate "$tmp" >/dev/null
    id=$(app_id)
    if [ -z "$id" ]; then
      echo "creating app $APP_NAME (App Platform builds the images; this takes a few minutes)"
      id=$(doctl apps create --spec "$tmp" --wait --format ID --no-header)
    else
      echo "updating app $APP_NAME ($id) (App Platform rebuilds; this takes a few minutes)"
      doctl apps update "$id" --spec "$tmp" --wait --format ID --no-header >/dev/null
    fi
    url=$(app_url "$id")
    echo "deployed: $url"
    echo "admin login: user ops, key in $SECRETS/ops-key (or ./scripts/do-app.sh login)"
    echo "note: the config (tenants, routes) starts empty after every deploy"
    ;;
  demo)
    url=$(app_url "$(require_app)")
    # The management port is internal on App Platform: MANAGEMENT= makes the demo skip the metric reads.
    GATEWAY="$url" MANAGEMENT="" OPS_KEY="$(ops_key)" "$ROOT/scripts/demo-do-upstream.sh"
    ;;
  login)
    login "$(app_url "$(require_app)")"
    ;;
  logs)
    doctl apps logs "$(require_app)" pfx-multi-tenant-gateway --type run --follow
    ;;
  url)
    app_url "$(require_app)"
    ;;
  *)
    echo "$usage"; exit 1
    ;;
esac
