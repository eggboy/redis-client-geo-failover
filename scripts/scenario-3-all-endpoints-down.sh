#!/usr/bin/env bash
#
# scenario-3-all-endpoints-down.sh
#
# Disables public network access on BOTH AMR caches, asserts /actuator/health
# reports DOWN and POST /sessions returns HTTP 503, then restores both and asserts
# recovery. Verifies AllDatabasesUnhealthyEvent + GlobalExceptionHandler.
#
# Requires: curl, jq, az (logged in), terraform state in $INFRA_DIR, app running.

set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
INFRA_DIR="${INFRA_DIR:-$(cd "$(dirname "$0")/../infra" && pwd)}"
DOWN_TIMEOUT="${DOWN_TIMEOUT:-90}"
UP_TIMEOUT="${UP_TIMEOUT:-180}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { printf "${BLUE}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
warn() { printf "${YELLOW}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
ok()   { printf "${GREEN}[%(%H:%M:%S)T] ✓ %s${NC}\n" -1 "$*"; }
fail() { printf "${RED}[%(%H:%M:%S)T] ✗ %s${NC}\n" -1 "$*"; }

require() { command -v "$1" >/dev/null 2>&1 || { fail "$1 is required but not installed"; exit 1; }; }
require curl
require jq
require az
require terraform

# shellcheck source=lib/azure-injection.sh
source "$(dirname "$0")/lib/azure-injection.sh"

on_exit() {
  restore_all || true
}
trap on_exit EXIT

abort() { fail "$1"; exit 1; }

health_status() {
  # Don't use -f: Spring Boot returns HTTP 503 when status=DOWN, but the
  # body still carries the JSON we need.
  curl -sS "$APP_URL/actuator/health" | jq -r .status
}

wait_for_health() {
  local want="$1" timeout="$2"
  local started_at=$SECONDS
  local deadline=$(( started_at + timeout ))
  while (( SECONDS < deadline )); do
    local s
    s="$(curl -sS "$APP_URL/actuator/health" 2>/dev/null | jq -r .status 2>/dev/null || echo UNKNOWN)"
    if [[ "$s" == "$want" ]]; then
      local elapsed=$(( SECONDS - started_at ))
      ok "Health = $want after ${elapsed}s" >&2
      printf '%s' "$elapsed"
      return 0
    fi
    printf "." >&2
    sleep 3
  done
  echo >&2
  abort "Timed out after ${timeout}s waiting for health=$want."
}

log "Probing app at $APP_URL …"
curl -fsS "$APP_URL/actuator/health" >/dev/null \
  || abort "App not reachable. Start it with: mvn spring-boot:run"

load_terraform_outputs
log "Baseline health: $(health_status)"

disable_primary
disable_secondary

log "Waiting up to ${DOWN_TIMEOUT}s for /actuator/health to report DOWN …"
DOWN_AT_S="$(wait_for_health "DOWN" "$DOWN_TIMEOUT")"

log "Calling POST /sessions — expecting HTTP 503 …"
HTTP_CODE=$(curl -s -o /tmp/sess-resp.$$ -w '%{http_code}' \
  -X POST "$APP_URL/sessions" -H 'Content-Type: application/json' \
  -d '{"userId":"probe-scenario-3","attributes":{}}' || true)
BODY=$(cat /tmp/sess-resp.$$); rm -f /tmp/sess-resp.$$
if [[ "$HTTP_CODE" == "503" ]]; then
  ok "POST /sessions returned HTTP 503 as expected"
  echo "$BODY" | jq .
  POST_503="true"
else
  warn "POST /sessions returned HTTP $HTTP_CODE (expected 503). Body: $BODY"
  POST_503="false"
fi

log "Re-enabling public network access on both clusters …"
enable_primary
enable_secondary

log "Waiting up to ${UP_TIMEOUT}s for /actuator/health to report UP …"
UP_AT_S="$(wait_for_health "UP" "$UP_TIMEOUT")"

log "Final /status:"
curl -fsS "$APP_URL/status" | jq '{activeEndpoint, lastSwitch, lastUnhealthy}'

if [[ "$POST_503" == "true" ]]; then
  ok "Scenario 3 complete (PASS)."
else
  fail "Scenario 3 FAIL: POST /sessions did not return 503 while both endpoints were down"
  exit 1
fi
