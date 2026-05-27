#!/usr/bin/env bash
#
# scenario-2-organic-failover.sh
#
# Apply a CiliumNetworkPolicy that denies egress from the app pods to the
# PRIMARY AMR FQDN, watch the app fail over to secondary, remove the policy,
# watch the app fail back to primary.
#
# This replaces the earlier Azure-control-plane approach (toggling
# publicNetworkAccess) because that toggle only blocks NEW handshakes at the
# Azure firewall — warm Jedis sockets keep working and PingStrategy never
# trips. A Cilium L4 deny on the FQDN drops packets in-pod for both existing
# and new flows, which is what a real outage looks like to the client.
#
# Prereqs:
#   - AKS cluster with Cilium dataplane and ACNS security (FQDN filtering).
#   - amr-geo-failover deployed via scripts/aks-deploy.sh into $APP_NAMESPACE.
#   - $APP_URL points at the LoadBalancer service (printed by aks-deploy.sh).
#   - $AMR_PRIMARY_HOST set (printed by aks-bootstrap.sh).
#
# Asserts pass/fail only. No MTTR or latency measurement.

set -euo pipefail

APP_URL="${APP_URL:?APP_URL not set. Run aks-deploy.sh and source its output.}"
APP_NAMESPACE="${APP_NAMESPACE:-amr-demo}"
AMR_PRIMARY_HOST="${AMR_PRIMARY_HOST:?AMR_PRIMARY_HOST not set. Run aks-bootstrap.sh.}"
FAILOVER_TIMEOUT="${FAILOVER_TIMEOUT:-90}"
FAILBACK_TIMEOUT="${FAILBACK_TIMEOUT:-180}"
HOLD_S="${HOLD_S:-10}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
POLICY_TEMPLATE="$SCRIPT_DIR/../deploy/k8s/policies/block-primary.yaml"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { printf "${BLUE}[%(%H:%M:%S)T] %s${NC}\n"  -1 "$*"; }
warn() { printf "${YELLOW}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
ok()   { printf "${GREEN}[%(%H:%M:%S)T] ✓ %s${NC}\n" -1 "$*"; }
fail() { printf "${RED}[%(%H:%M:%S)T] ✗ %s${NC}\n"   -1 "$*"; }
die()  { fail "$*"; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }
require curl
require jq
require kubectl
require envsubst
require dig

[[ -f "$POLICY_TEMPLATE" ]] || die "Policy template not found: $POLICY_TEMPLATE"

POLICY_APPLIED=false

# Resolve FQDN to CIDR for the Cilium policy.
# toFQDNs only exists in `egress` (allow), not `egressDeny` — it's an allow-list mechanism.
AMR_PRIMARY_IP="$(dig +short "$AMR_PRIMARY_HOST" | head -1)"
[[ -n "$AMR_PRIMARY_IP" ]] || die "Could not resolve $AMR_PRIMARY_HOST"
export AMR_PRIMARY_CIDR="${AMR_PRIMARY_IP}/32"
log "Resolved $AMR_PRIMARY_HOST → $AMR_PRIMARY_CIDR"

render_policy() { envsubst '${AMR_PRIMARY_CIDR}' < "$POLICY_TEMPLATE"; }

on_exit() {
  if [[ "$POLICY_APPLIED" == "true" ]]; then
    log "Cleanup: removing block-primary CiliumNetworkPolicy …"
    render_policy | kubectl delete -n "$APP_NAMESPACE" -f - --ignore-not-found >/dev/null || true
  fi
}
trap on_exit EXIT

active_endpoint() { curl -fsS "$APP_URL/status" | jq -r '.activeEndpoint'; }

wait_for_active() {
  local needle="$1" timeout="$2" label="$3"
  local started_at=$SECONDS
  local deadline=$(( started_at + timeout ))
  while (( SECONDS < deadline )); do
    local current
    current="$(active_endpoint 2>/dev/null || true)"
    if [[ "$current" == *"$needle"* ]]; then
      local elapsed=$(( SECONDS - started_at ))
      ok "$label: activeEndpoint contains '$needle' after ${elapsed}s"
      return 0
    fi
    printf "." >&2
    sleep 2
  done
  echo >&2
  die "$label: timed out after ${timeout}s. Current activeEndpoint: $(active_endpoint 2>/dev/null || echo unreachable)"
}

log "Probing $APP_URL/actuator/health …"
curl -fsS "$APP_URL/actuator/health" >/dev/null \
  || die "App not reachable at $APP_URL. Did aks-deploy.sh finish?"

log "Baseline activeEndpoint: $(active_endpoint)"
[[ "$(active_endpoint)" == *primary* ]] \
  || die "Expected baseline activeEndpoint to contain 'primary'."

# --------------------------------------------------------------------------
# Failover: apply block-primary policy and wait for app to switch
# --------------------------------------------------------------------------
log "Applying CiliumNetworkPolicy block-amr-primary (FQDN deny on $AMR_PRIMARY_HOST) …"
render_policy | kubectl apply -n "$APP_NAMESPACE" -f -
POLICY_APPLIED=true
ok "Policy applied — egress to primary FQDN is now denied for app pods"

wait_for_active "secondary" "$FAILOVER_TIMEOUT" "Failover"

log "Holding ${HOLD_S}s with secondary as active to confirm steady state …"
sleep "$HOLD_S"
[[ "$(active_endpoint)" == *secondary* ]] \
  || die "activeEndpoint flipped off secondary during hold — unexpected."
ok "Secondary remained active for the hold window"

# --------------------------------------------------------------------------
# Failback: remove policy and wait for app to revert
# --------------------------------------------------------------------------
log "Deleting CiliumNetworkPolicy block-amr-primary …"
render_policy | kubectl delete -n "$APP_NAMESPACE" -f - --ignore-not-found >/dev/null
POLICY_APPLIED=false
ok "Policy removed — primary FQDN reachable again"

wait_for_active "primary" "$FAILBACK_TIMEOUT" "Failback"

log "Final /status:"
curl -fsS "$APP_URL/status" | jq '{activeEndpoint, lastSwitch}'

ok "Scenario 2 PASS (Cilium FQDN deny → failover → failback)."
