#!/usr/bin/env bash
#
# scripts/lib/azure-injection.sh
#
# Shared functions for Azure-side failure injection on AMR caches, used by both
# scenario-2 (organic failover) and scenario-5 (load-driven MTTR).
#
# Source it from a scenario script AFTER colour/log helpers are defined and
# AFTER `set -euo pipefail`. It reads RG_NAME / PRIMARY_CLUSTER / SECONDARY_CLUSTER
# from the environment — populate them via `terraform output` before calling.
#
# Requires: az (logged in), $RG_NAME, $PRIMARY_CLUSTER, $SECONDARY_CLUSTER.

# shellcheck shell=bash

az_public_access() {
  local cluster="$1" access="$2"
  : "${RG_NAME:?RG_NAME must be set before calling azure-injection helpers}"
  log "Setting publicNetworkAccess=$access on '$cluster' …"
  az redisenterprise update --cluster-name "$cluster" \
    --resource-group "$RG_NAME" --public-network-access "$access" \
    --only-show-errors >/dev/null
}

disable_primary()   { az_public_access "${PRIMARY_CLUSTER:?PRIMARY_CLUSTER must be set}"   Disabled; }
enable_primary()    { az_public_access "${PRIMARY_CLUSTER:?PRIMARY_CLUSTER must be set}"   Enabled;  }
disable_secondary() { az_public_access "${SECONDARY_CLUSTER:?SECONDARY_CLUSTER must be set}" Disabled; }
enable_secondary()  { az_public_access "${SECONDARY_CLUSTER:?SECONDARY_CLUSTER must be set}" Enabled;  }

# Best-effort cleanup intended to be installed via `trap … EXIT`. Re-enables any
# cluster names that are set, swallows individual failures so one bad call
# doesn't strand the other.
restore_all() {
  local rc=$?
  if [[ -n "${PRIMARY_CLUSTER:-}" && -n "${RG_NAME:-}" ]]; then
    warn "Cleanup: re-enabling public network access on '$PRIMARY_CLUSTER' …"
    az_public_access "$PRIMARY_CLUSTER" Enabled || true
  fi
  if [[ -n "${SECONDARY_CLUSTER:-}" && -n "${RG_NAME:-}" ]]; then
    warn "Cleanup: re-enabling public network access on '$SECONDARY_CLUSTER' …"
    az_public_access "$SECONDARY_CLUSTER" Enabled || true
  fi
  return "$rc"
}

# Populate RG_NAME / PRIMARY_CLUSTER / SECONDARY_CLUSTER from `terraform output` in $INFRA_DIR.
load_terraform_outputs() {
  : "${INFRA_DIR:?INFRA_DIR must be set before calling load_terraform_outputs}"
  log "Reading Terraform outputs from $INFRA_DIR …"
  pushd "$INFRA_DIR" >/dev/null
  RG_NAME=$(terraform output -raw resource_group_name)
  PRIMARY_CLUSTER=$(terraform output -raw primary_cache_id   | awk -F/ '{print $NF}')
  SECONDARY_CLUSTER=$(terraform output -raw secondary_cache_id | awk -F/ '{print $NF}')
  popd >/dev/null
  export RG_NAME PRIMARY_CLUSTER SECONDARY_CLUSTER
  log "RG=$RG_NAME primary=$PRIMARY_CLUSTER secondary=$SECONDARY_CLUSTER"
}
