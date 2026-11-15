#!/usr/bin/env bash
# Starts and stops the local stacks: the gateway and the upstream app, each a plain Spring Boot process
# (java -jar), no containers. JDK 21 is the only requirement.
#
#   ./scripts/stack.sh <stack> up|down|status
#
# Stacks (ports; the three can run side by side):
#   demo           gateway :8080/:8081, echo upstream :9200                        -> ./scripts/demo.sh
#   upstream-demo  gateway :9180/:9181, stable :9201, canary :9202 (60% fail)      -> ./scripts/demo-do-upstream.sh
#   loadtest       gateway :9080/:9081, upstream "ok" :9090, slow upstream :9091   -> ./scripts/loadtest.sh
#
# Logs and pid files: .run/<stack>/<process>.{log,pid}. Both jars are (re)built on "up"; SKIP_BUILD=1
# reuses the existing ones. Load-test knobs passed through to the gateway: LT_POOL_PER_ROUTE,
# LT_POOL_TOTAL, LT_TOMCAT_MAX_CONNECTIONS (config/application-loadtest.yml) and LT_JAVA_OPTS (JVM flags,
# e.g. "-XX:+UseZGC -XX:+ZGenerational" or "-Xms3500m -Xmx3500m").
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
usage="usage: stack.sh demo|upstream-demo|loadtest up|down|status"
STACK="${1:?$usage}"; ACTION="${2:?$usage}"
RUN="$ROOT/.run/$STACK"

case "$STACK" in demo|upstream-demo|loadtest) ;; *) echo "$usage"; exit 1 ;; esac

gateway_jar() { ls "$ROOT"/target/multi-tenant-api-gateway-*.jar 2>/dev/null | grep -v original | head -1; }
upstream_jar() { ls "$ROOT"/upstream-app/target/upstream-app-*.jar 2>/dev/null | grep -v original | head -1; }

build() {
  if [ "${SKIP_BUILD:-0}" = 1 ] && [ -n "$(gateway_jar)" ] && [ -n "$(upstream_jar)" ]; then return; fi
  echo "building the gateway and the upstream app (SKIP_BUILD=1 to reuse the jars)"
  (cd "$ROOT" && ./mvnw -q -B package -DskipTests && ./mvnw -q -B -f upstream-app/pom.xml package -DskipTests)
}

running() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }

# start <name> <health-url> <jar> [ENV=value ...] [-- java flags...]
start() {
  local name="$1" health="$2" jar="$3"; shift 3
  local envs=() flags=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do envs+=("$1"); shift; done
  [ "${1:-}" = "--" ] && { shift; flags=("$@"); }
  if running "$name"; then echo "$name already running (pid $(cat "$RUN/$name.pid"))"; return; fi
  # From the repository root, so the gateway finds ./config/application-<profile>.yml and ./data/
  (cd "$ROOT" && exec env ${envs[@]+"${envs[@]}"} java ${flags[@]+"${flags[@]}"} -jar "$jar" \
    > "$RUN/$name.log" 2>&1) &
  echo $! > "$RUN/$name.pid"
  for _ in $(seq 120); do
    if curl -sf -o /dev/null "$health"; then echo "$name up (pid $(cat "$RUN/$name.pid"), log $RUN/$name.log)"; return; fi
    if ! running "$name"; then break; fi
    sleep 0.5
  done
  echo "$name did not become ready at $health; last log lines:"; tail -20 "$RUN/$name.log"; exit 1
}

stop() {
  local name="$1"
  if running "$name"; then
    local pid; pid=$(cat "$RUN/$name.pid")
    kill "$pid"
    for _ in $(seq 60); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done  # graceful shutdown
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    echo "$name stopped"
  fi
  rm -f "$RUN/$name.pid"
}

upstream() { # name port [ENV=value ...]
  local name="$1" port="$2"; shift 2
  start "$name" "http://localhost:$port/healthz" "$(upstream_jar)" "PORT=$port" "$@"
}

gateway() { # port management-port profiles [ENV=value ...] [-- java flags]
  local port="$1" mgmt="$2" profiles="$3"; shift 3
  start gateway "http://localhost:$mgmt/actuator/health/readiness" "$(gateway_jar)" \
    "SERVER_PORT=$port" "MANAGEMENT_SERVER_PORT=$mgmt" "SPRING_PROFILES_ACTIVE=$profiles" "$@"
}

processes() {
  case "$STACK" in
    demo) echo "echo gateway" ;;
    upstream-demo) echo "stable canary gateway" ;;
    loadtest) echo "upstream slow-upstream gateway" ;;
  esac
}

case "$ACTION" in
  up)
    mkdir -p "$RUN"
    build
    case "$STACK" in
      demo)
        upstream echo 9200 VERSION=echo
        gateway 8080 8081 local   # config in the H2 file ./data/gateway (survives restarts)
        ;;
      upstream-demo)
        upstream stable 9201 VERSION=stable
        upstream canary 9202 VERSION=canary FAIL_PCT=60
        # The demo sends ~220 requests in a few seconds from one address: more than the per-address burst (100).
        gateway 9180 9181 local "GATEWAY_DB_URL=jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1" GATEWAY_IPRATELIMIT_BURST=1000
        ;;
      loadtest)
        upstream upstream 9090 PLAIN_OK=true
        upstream slow-upstream 9091 PLAIN_OK=true DELAY_MIN_MS=100 DELAY_MAX_MS=5000
        # shellcheck disable=SC2086  # LT_JAVA_OPTS is a list of flags
        gateway 9080 9081 local,loadtest -- ${LT_JAVA_OPTS:-}
        ;;
    esac
    ;;
  down)
    stop gateway   # first, so it drains in-flight requests while its upstreams are still there
    for p in $(processes); do [ "$p" = gateway ] || stop "$p"; done
    ;;
  status)
    for p in $(processes); do
      if running "$p"; then echo "$p running (pid $(cat "$RUN/$p.pid"))"; else echo "$p stopped"; fi
    done
    ;;
  *) echo "$usage"; exit 1 ;;
esac
