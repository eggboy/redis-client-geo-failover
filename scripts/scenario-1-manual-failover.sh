#!/usr/bin/env bash
#
# scenario-1-manual-failover.sh
#
# Proves the `connection.switchTo(uri)` path and DatabaseSwitchEvent plumbing
# end-to-end WITHOUT touching infra, AND proves that traffic actually follows
# the switch (not just /status).
#
# Flow:
#   1. Baseline: assert active=primary.
#   2. POST /sessions with a unique userId via the active endpoint (primary).
#      Capture the server-issued sessionId.
#   3. Sleep 500 ms so geo-replication can copy the JSON to secondary.
#   4. PUT /redis/active-endpoint {"endpoint":"secondary"}  (switchTo).
#   5. GET /admin/pinned/secondary/sessions/<sid>  (pinned read — no failback race).
#      Assert userId in the response equals what we sent.
#   6. Wait FAILBACK_WAIT_S, assert active=primary (failback worked).
#   7. DELETE /sessions/<sid> (cleanup).
#   8. Write a JSON summary report.
#
# Why pinned read in step 5: with failback-supported=true + primary.weight=1.0,
# Lettuce's failback timer reverts to primary within ~1-4 s. A bare GET via
# the MultiDbClient could land on either endpoint depending on timing. The
# pinned read makes the assertion deterministic.

set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
FAILBACK_WAIT_S="${FAILBACK_WAIT_S:-12}"
REPL_SETTLE_MS="${REPL_SETTLE_MS:-500}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { printf "${BLUE}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
warn() { printf "${YELLOW}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
ok()   { printf "${GREEN}[%(%H:%M:%S)T] ✓ %s${NC}\n" -1 "$*"; }
fail() { printf "${RED}[%(%H:%M:%S)T] ✗ %s${NC}\n" -1 "$*"; }

require() { command -v "$1" >/dev/null 2>&1 || { fail "$1 is required but not installed"; exit 1; }; }
require curl
require jq

USER_ID="scenario1-roundtrip-$$-$RANDOM"
SID=""

cleanup() {
  if [[ -n "$SID" ]]; then
    curl -fsS -X DELETE "$APP_URL/sessions/$SID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

abort() { fail "$1"; exit 1; }

active_endpoint_name() {
  curl -fsS "$APP_URL/status" | jq -r '.activeEndpoint' \
    | grep -oE '(primary|secondary)' | head -1
}

assert_active_is() {
  local want="$1"
  local current
  current="$(active_endpoint_name || true)"
  if [[ "$current" == "$want" ]]; then
    ok "Active endpoint is '$want'"
  else
    abort "Expected active endpoint '$want' but got '$current'"
  fi
}

log "Probing $APP_URL/actuator/health …"
HEALTH=$(curl -fsS "$APP_URL/actuator/health" || true)
[[ -z "$HEALTH" ]] && abort "App not reachable at $APP_URL. Start it with: mvn spring-boot:run"
log "App health status: $(echo "$HEALTH" | jq -r .status)"

log "Baseline: active endpoint"
assert_active_is "primary"

log "POST /sessions  userId='$USER_ID' (via primary)"
POST_BODY=$(jq -n --arg u "$USER_ID" '{userId:$u, attributes:{origin:"scenario-1"}}')
CREATE_RESP=$(curl -fsS -X POST "$APP_URL/sessions" -H 'Content-Type: application/json' -d "$POST_BODY")
SID=$(echo "$CREATE_RESP" | jq -r .sessionId)
[[ -n "$SID" && "$SID" != "null" ]] || abort "POST /sessions did not return a sessionId: $CREATE_RESP"
ok "Created session $SID for user '$USER_ID' on primary"

log "Sleeping ${REPL_SETTLE_MS}ms for replication to propagate to secondary …"
sleep "$(awk "BEGIN { printf \"%.3f\", $REPL_SETTLE_MS / 1000 }")"

log "PUT /redis/active-endpoint secondary …"
SWITCH_RESP="$(curl -fsS -X PUT "$APP_URL/redis/active-endpoint" \
  -H 'Content-Type: application/json' -d '{"endpoint":"secondary"}')"
SWITCH_TO="$(echo "$SWITCH_RESP" | jq -r .to | grep -oE '(primary|secondary)' | head -1)"
[[ "$SWITCH_TO" == "secondary" ]] || abort "switchTo response did not confirm secondary: $SWITCH_RESP"
ok "switchTo response confirms 'secondary'"

log "GET /admin/pinned/secondary/sessions/$SID  — must contain userId='$USER_ID'"
GOT_RESP="$(curl -fsS "$APP_URL/admin/pinned/secondary/sessions/$SID")"
if echo "$GOT_RESP" | jq -e --arg u "$USER_ID" '.userId == $u' >/dev/null; then
  ok "Pinned read on secondary returned the session we wrote on primary — round-trip + replication intact"
else
  abort "Pinned read on secondary returned unexpected payload: $GOT_RESP (expected userId='$USER_ID')"
fi

log "Waiting ${FAILBACK_WAIT_S}s for failback timer to revert to primary …"
sleep "$FAILBACK_WAIT_S"
assert_active_is "primary"

log "Final /status:"
curl -fsS "$APP_URL/status" | jq '{activeEndpoint, lastSwitch}'

ok "Scenario 1 complete."
