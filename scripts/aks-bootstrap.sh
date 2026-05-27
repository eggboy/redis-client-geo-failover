#!/usr/bin/env bash
#
# aks-bootstrap.sh
#
# One-shot, idempotent bootstrap for running amr-geo-failover on a pre-existing
# AKS cluster with a pre-existing ACR. Does:
#
#   1. Verifies the AKS cluster has the required features enabled
#      (OIDC issuer, Workload Identity, Cilium dataplane, ACNS security).
#   2. Attaches the ACR to the AKS cluster (if not already attached).
#   3. Creates (or reuses) a User-Assigned Managed Identity in the AMR resource
#      group: amr-demo-app-mi.
#   4. Federates that UAMI to the in-cluster ServiceAccount
#      `system:serviceaccount:amr-demo:amr-demo-sa` via the AKS OIDC issuer.
#   5. Assigns the AMR `default` access policy to the UAMI's principal id on
#      BOTH caches.
#   6. Creates the `amr-demo` namespace if missing.
#   7. Emits a shell-sourceable env block on stdout:
#        export ACR_LOGIN_SERVER=...
#        export UAMI_CLIENT_ID=...
#        export AMR_PRIMARY_HOST=...
#        export AMR_SECONDARY_HOST=...
#      …which aks-deploy.sh consumes.
#
# Required env (or pass --flags):
#   ACR_NAME, AKS_NAME, AKS_RG
#
# Optional env:
#   INFRA_DIR        defaults to ../infra relative to this script
#   APP_NAMESPACE    defaults to amr-demo
#   APP_SA_NAME      defaults to amr-demo-sa
#   UAMI_NAME        defaults to amr-demo-app-mi
#
# Re-run safe: every Azure call uses an "exists → reuse, else create" pattern.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INFRA_DIR="${INFRA_DIR:-$(cd "$SCRIPT_DIR/../infra" && pwd)}"

APP_NAMESPACE="${APP_NAMESPACE:-amr-demo}"
APP_SA_NAME="${APP_SA_NAME:-amr-demo-sa}"
UAMI_NAME="${UAMI_NAME:-amr-demo-app-mi}"
FED_CRED_NAME="${FED_CRED_NAME:-amr-demo-aks-fed}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { printf "${BLUE}[%(%H:%M:%S)T] %s${NC}\n"  -1 "$*" >&2; }
warn() { printf "${YELLOW}[%(%H:%M:%S)T] %s${NC}\n" -1 "$*" >&2; }
ok()   { printf "${GREEN}[%(%H:%M:%S)T] ✓ %s${NC}\n" -1 "$*" >&2; }
fail() { printf "${RED}[%(%H:%M:%S)T] ✗ %s${NC}\n"   -1 "$*" >&2; }
die()  { fail "$*"; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"; }
require az
require kubectl
require terraform
require jq

[[ -n "${ACR_NAME:-}" ]] || die "ACR_NAME not set (e.g. export ACR_NAME=myregistry)"
[[ -n "${AKS_NAME:-}" ]] || die "AKS_NAME not set (e.g. export AKS_NAME=my-aks)"
[[ -n "${AKS_RG:-}"   ]] || die "AKS_RG not set (e.g. export AKS_RG=my-aks-rg)"

# ---------------------------------------------------------------------------
# 1. Verify AKS prerequisites
# ---------------------------------------------------------------------------
log "Reading AKS cluster $AKS_NAME ($AKS_RG) …"
AKS_JSON="$(az aks show -g "$AKS_RG" -n "$AKS_NAME" -o json)" \
  || die "az aks show failed — is $AKS_NAME/$AKS_RG correct and are you logged in?"

OIDC_ENABLED="$(jq -r '.oidcIssuerProfile.enabled // false' <<<"$AKS_JSON")"
WI_ENABLED="$(jq   -r '.securityProfile.workloadIdentity.enabled // false' <<<"$AKS_JSON")"
DATAPLANE="$(jq   -r '.networkProfile.networkDataplane // "unknown"' <<<"$AKS_JSON")"
ACNS_SEC="$(jq    -r '.networkProfile.advancedNetworking.security.enabled // false' <<<"$AKS_JSON")"

missing=()
[[ "$OIDC_ENABLED" == "true" ]] || missing+=("--enable-oidc-issuer")
[[ "$WI_ENABLED"   == "true" ]] || missing+=("--enable-workload-identity")
[[ "$DATAPLANE"    == "cilium" ]] || missing+=("--network-dataplane cilium")
[[ "$ACNS_SEC"     == "true" ]] || missing+=("--enable-acns (with security)")

if (( ${#missing[@]} > 0 )); then
  fail "AKS cluster is missing required features: ${missing[*]}"
  fail "Remediate with: az aks update -g $AKS_RG -n $AKS_NAME ${missing[*]}"
  exit 1
fi
ok "AKS prerequisites met (OIDC + Workload Identity + Cilium + ACNS)"

OIDC_ISSUER="$(jq -r '.oidcIssuerProfile.issuerUrl' <<<"$AKS_JSON")"
log "OIDC issuer: $OIDC_ISSUER"

# ---------------------------------------------------------------------------
# 2. Attach ACR (idempotent — `az aks update --attach-acr` is a no-op if
#    AcrPull is already granted)
# ---------------------------------------------------------------------------
log "Attaching ACR $ACR_NAME to AKS $AKS_NAME …"
az aks update -g "$AKS_RG" -n "$AKS_NAME" --attach-acr "$ACR_NAME" -o none \
  || die "Failed to attach ACR. Need 'Owner' or 'User Access Administrator' on the AKS RG."

ACR_LOGIN_SERVER="$(az acr show -n "$ACR_NAME" --query loginServer -o tsv)" \
  || die "az acr show failed — is $ACR_NAME the right registry?"
ok "ACR attached: $ACR_LOGIN_SERVER"

# ---------------------------------------------------------------------------
# 3. Read AMR coordinates from terraform
# ---------------------------------------------------------------------------
log "Reading AMR coordinates from terraform in $INFRA_DIR …"
pushd "$INFRA_DIR" >/dev/null
AMR_RG="$(terraform output -raw resource_group_name)"
AMR_PRIMARY_HOST="$(terraform output -raw primary_hostname)"
AMR_SECONDARY_HOST="$(terraform output -raw secondary_hostname)"
PRIMARY_CACHE_ID="$(terraform output -raw primary_cache_id)"
SECONDARY_CACHE_ID="$(terraform output -raw secondary_cache_id)"
popd >/dev/null
PRIMARY_CACHE_NAME="$(basename "$PRIMARY_CACHE_ID")"
SECONDARY_CACHE_NAME="$(basename "$SECONDARY_CACHE_ID")"
log "AMR_RG=$AMR_RG  primary=$PRIMARY_CACHE_NAME  secondary=$SECONDARY_CACHE_NAME"

# ---------------------------------------------------------------------------
# 4. Create / reuse UAMI in the AMR resource group
# ---------------------------------------------------------------------------
log "Ensuring UAMI $UAMI_NAME in $AMR_RG …"
if az identity show -g "$AMR_RG" -n "$UAMI_NAME" -o none 2>/dev/null; then
  ok "UAMI already exists"
else
  AMR_LOCATION="$(az group show -n "$AMR_RG" --query location -o tsv)"
  az identity create -g "$AMR_RG" -n "$UAMI_NAME" --location "$AMR_LOCATION" -o none
  ok "UAMI created"
fi
UAMI_CLIENT_ID="$(az identity show -g "$AMR_RG" -n "$UAMI_NAME" --query clientId -o tsv)"
UAMI_PRINCIPAL_ID="$(az identity show -g "$AMR_RG" -n "$UAMI_NAME" --query principalId -o tsv)"
log "UAMI clientId=$UAMI_CLIENT_ID principalId=$UAMI_PRINCIPAL_ID"

# ---------------------------------------------------------------------------
# 5. Federated credential: UAMI ↔ AKS OIDC ↔ ServiceAccount
# ---------------------------------------------------------------------------
SUBJECT="system:serviceaccount:${APP_NAMESPACE}:${APP_SA_NAME}"
log "Ensuring federated credential $FED_CRED_NAME → $SUBJECT …"
if az identity federated-credential show -g "$AMR_RG" --identity-name "$UAMI_NAME" \
     -n "$FED_CRED_NAME" -o none 2>/dev/null; then
  # Update in case OIDC issuer or subject changed.
  az identity federated-credential update -g "$AMR_RG" --identity-name "$UAMI_NAME" \
    -n "$FED_CRED_NAME" --issuer "$OIDC_ISSUER" --subject "$SUBJECT" \
    --audiences api://AzureADTokenExchange -o none
  ok "Federated credential updated"
else
  az identity federated-credential create -g "$AMR_RG" --identity-name "$UAMI_NAME" \
    -n "$FED_CRED_NAME" --issuer "$OIDC_ISSUER" --subject "$SUBJECT" \
    --audiences api://AzureADTokenExchange -o none
  ok "Federated credential created"
fi

# ---------------------------------------------------------------------------
# 6. AMR data-access policy assignment for the UAMI on both caches
# ---------------------------------------------------------------------------
assign_amr_policy() {
  local cache_name="$1" assignment_name="$2"
  log "Assigning 'default' access policy to UAMI on $cache_name …"
  if az redisenterprise database access-policy-assignment show \
       --cluster-name "$cache_name" --database-name default --resource-group "$AMR_RG" \
       --access-policy-assignment-name "$assignment_name" -o none 2>/dev/null; then
    ok "Access policy assignment already exists on $cache_name"
    return 0
  fi
  az redisenterprise database access-policy-assignment create \
    --cluster-name "$cache_name" \
    --database-name default \
    --resource-group "$AMR_RG" \
    --access-policy-assignment-name "$assignment_name" \
    --access-policy-name default \
    --user-object-id "$UAMI_PRINCIPAL_ID" -o none
  ok "Access policy assigned on $cache_name"
}
assign_amr_policy "$PRIMARY_CACHE_NAME"   "amrdemoaks${RANDOM}p"
assign_amr_policy "$SECONDARY_CACHE_NAME" "amrdemoaks${RANDOM}s"

# ---------------------------------------------------------------------------
# 7. Cluster credentials + namespace
# ---------------------------------------------------------------------------
log "Fetching AKS credentials …"
az aks get-credentials -g "$AKS_RG" -n "$AKS_NAME" --overwrite-existing -o none
kubectl create namespace "$APP_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ok "Namespace $APP_NAMESPACE ensured"

# ---------------------------------------------------------------------------
# 8. Emit env block for aks-deploy.sh
# ---------------------------------------------------------------------------
ok "Bootstrap complete."
warn "Source the following into your shell before running aks-deploy.sh:"
cat <<EOT
export ACR_NAME="$ACR_NAME"
export ACR_LOGIN_SERVER="$ACR_LOGIN_SERVER"
export AKS_NAME="$AKS_NAME"
export AKS_RG="$AKS_RG"
export UAMI_CLIENT_ID="$UAMI_CLIENT_ID"
export AMR_PRIMARY_HOST="$AMR_PRIMARY_HOST"
export AMR_SECONDARY_HOST="$AMR_SECONDARY_HOST"
export APP_NAMESPACE="$APP_NAMESPACE"
EOT
