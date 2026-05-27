#!/usr/bin/env bash
#
# aks-deploy.sh
#
# Build the container image in ACR and deploy/refresh the K8s objects.
# Expects the env block emitted by aks-bootstrap.sh to be in the environment:
#   ACR_NAME, ACR_LOGIN_SERVER, UAMI_CLIENT_ID,
#   AMR_PRIMARY_HOST, AMR_SECONDARY_HOST, APP_NAMESPACE
#
# Optional:
#   IMAGE_TAG        defaults to `git rev-parse --short HEAD` (or `latest`)
#   IMAGE_NAME       defaults to amr-geo-failover
#   LB_WAIT_TIMEOUT  defaults to 300 (seconds)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"

IMAGE_NAME="${IMAGE_NAME:-amr-geo-failover}"
IMAGE_TAG="${IMAGE_TAG:-$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo latest)}"
LB_WAIT_TIMEOUT="${LB_WAIT_TIMEOUT:-300}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { printf "${BLUE}[%(%H:%M:%S)T] %s${NC}\n"  -1 "$*"; }
warn() { printf "${YELLOW}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*"; }
ok()   { printf "${GREEN}[%(%H:%M:%S)T] ✓ %s${NC}\n" -1 "$*"; }
fail() { printf "${RED}[%(%H:%M:%S)T] ✗ %s${NC}\n"   -1 "$*"; }
die()  { fail "$*"; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }
require az
require kubectl
require envsubst

for v in ACR_NAME ACR_LOGIN_SERVER UAMI_CLIENT_ID AMR_PRIMARY_HOST AMR_SECONDARY_HOST APP_NAMESPACE; do
  [[ -n "${!v:-}" ]] || die "$v not set. Run aks-bootstrap.sh first and source its output."
done

# ---------------------------------------------------------------------------
# 1. Build the image in ACR
# ---------------------------------------------------------------------------
IMAGE="${ACR_LOGIN_SERVER}/${IMAGE_NAME}:${IMAGE_TAG}"
log "Building image in ACR: $IMAGE"
az acr build -r "$ACR_NAME" -t "${IMAGE_NAME}:${IMAGE_TAG}" \
  -f "$DEPLOY_DIR/Dockerfile" "$REPO_ROOT"
ok "Image built: $IMAGE"

# ---------------------------------------------------------------------------
# 2. Render + apply manifests
# ---------------------------------------------------------------------------
export IMAGE UAMI_CLIENT_ID AMR_PRIMARY_HOST AMR_SECONDARY_HOST

# envsubst lists only the variables we control so it won't accidentally
# expand $(literal) shell-ish strings inside the YAML.
SUBST_VARS='${IMAGE} ${UAMI_CLIENT_ID} ${AMR_PRIMARY_HOST} ${AMR_SECONDARY_HOST}'

log "Applying manifests under $DEPLOY_DIR/k8s …"
for f in "$DEPLOY_DIR"/k8s/*.yaml; do
  envsubst "$SUBST_VARS" < "$f" | kubectl apply -n "$APP_NAMESPACE" -f -
done
ok "Manifests applied"

log "Rolling out new image …"
kubectl -n "$APP_NAMESPACE" set image deployment/amr-geo-failover "app=$IMAGE"
kubectl -n "$APP_NAMESPACE" rollout status deployment/amr-geo-failover --timeout=180s
ok "Rollout complete"

# ---------------------------------------------------------------------------
# 3. Wait for LoadBalancer IP
# ---------------------------------------------------------------------------
log "Waiting up to ${LB_WAIT_TIMEOUT}s for LoadBalancer ingress IP …"
LB_IP=""
for _ in $(seq 1 "$LB_WAIT_TIMEOUT"); do
  LB_IP="$(kubectl -n "$APP_NAMESPACE" get svc amr-geo-failover \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  [[ -n "$LB_IP" ]] && break
  sleep 1
done
[[ -n "$LB_IP" ]] || die "LoadBalancer never received an external IP"

APP_URL="http://${LB_IP}"
ok "App reachable at $APP_URL"
warn "Source this before running scenarios:"
echo "export APP_URL=\"$APP_URL\""
