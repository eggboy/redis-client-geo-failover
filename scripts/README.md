# `scripts/` — AKS-based failover demo

The app runs as a Deployment on AKS. The "outage" is induced by applying a
`CiliumNetworkPolicy` that denies egress from the app pods to one AMR FQDN.
This is the cloud-native equivalent of pulling the cable: it severs both new
and existing TCP flows from inside the pod's network namespace, which is what
a real regional outage looks like to a client.

## Why Cilium FQDN policy, not the Azure control plane

`az redisenterprise update --public-network-access Disabled` is a firewall
change — it blocks NEW handshakes at the Azure edge but does **not** drop
warm sockets already established. Jedis's pooled connections keep returning
PING/PONG, the per-DB circuit breaker never opens, and the demo misses the
very failover it claims to showcase. AKS + Cilium FQDN filtering with
`egressDeny` drops packets in the pod's eBPF datapath, so the next probe
fails immediately.

Requires an AKS cluster with `--network-dataplane cilium --enable-acns` and
Workload Identity + OIDC issuer enabled. See
[Apply FQDN filtering policies](https://learn.microsoft.com/en-us/azure/aks/how-to-apply-fqdn-filtering-policies?tabs=cilium).

## End-to-end workflow

```bash
# 0. One-time: terraform-provision the two AMR caches.
cd infra && terraform apply && cd ..

# 1. Bootstrap (idempotent). Verifies AKS prerequisites, creates the UAMI,
#    federates it to system:serviceaccount:amr-demo:amr-demo-sa, assigns the
#    AMR `default` access policy on both caches, attaches ACR. Emits an env
#    block on stdout.
export ACR_NAME=...  AKS_NAME=...  AKS_RG=...
eval "$(./scripts/aks-bootstrap.sh)"

# 2. Build the image in ACR and deploy. Waits for the LoadBalancer IP and
#    prints APP_URL.
eval "$(./scripts/aks-deploy.sh | grep ^export)"

# 3. Run scenarios.
./scripts/scenario-1-manual-failover.sh           # uses APP_URL
./scripts/scenario-2-organic-failover.sh          # uses APP_URL + AMR_PRIMARY_HOST
./scripts/scenario-3-all-endpoints-down.sh        # uses APP_URL + az + terraform
```

## Files

| File | Purpose |
|---|---|
| `aks-bootstrap.sh`       | UAMI + AMR data-policy + OIDC federation + ACR attach + namespace. Idempotent. |
| `aks-deploy.sh`          | `az acr build` + render manifests via `envsubst` + `kubectl apply` + wait for LB IP. |
| `scenario-1-manual-failover.sh` | Proves `PUT /redis/active-endpoint {"endpoint":"secondary"}` actually routes traffic by writing a session via the active endpoint and reading it back through the pinned-read admin route on the OTHER endpoint. |
| `scenario-2-organic-failover.sh` | Applies `block-amr-primary` CiliumNetworkPolicy → asserts `/status.activeEndpoint` flips to `secondary` → removes the policy → asserts it flips back to `primary`. Plain PASS / FAIL. |
| `scenario-3-all-endpoints-down.sh` | Disables both caches' public network access via `az` → asserts `/actuator/health` reports `DOWN` and `POST /sessions` returns `503` → restores → asserts `UP`. |
| `lib/azure-injection.sh` | Helpers (`disable_primary`, `enable_primary`, `disable_secondary`, `enable_secondary`, `restore_all`, `load_terraform_outputs`) used by scenario-3 for the Azure-side disable/enable toggles. |

## Required environment per scenario

| Scenario | `APP_URL` | `AMR_PRIMARY_HOST` | `APP_NAMESPACE` | `kubectl` ctx | `az login` | `terraform output` |
|---|---|---|---|---|---|---|
| 1 | ✅ | — | — | — | — | — |
| 2 | ✅ | ✅ | ✅ (default `amr-demo`) | ✅ | — | — |
| 3 | ✅ | — | — | — | ✅ | ✅ |

All values are exported by `aks-bootstrap.sh` / `aks-deploy.sh`; just
`eval "$(...)"` their stdout.

## Tunables

Common knobs (override via env):

```bash
APP_URL=http://20.x.y.z                          ./scripts/scenario-1-manual-failover.sh
FAILOVER_TIMEOUT=120 FAILBACK_TIMEOUT=240 HOLD_S=15 \
                                                 ./scripts/scenario-2-organic-failover.sh
DOWN_TIMEOUT=120 UP_TIMEOUT=240                  ./scripts/scenario-3-all-endpoints-down.sh
```

Scenarios 2 and 3 register `EXIT` traps that restore state on Ctrl-C: scenario-2
deletes the CiliumNetworkPolicy; scenario-3 re-enables `publicNetworkAccess`
on both caches.

## What was deliberately removed

- Latency / MTTR measurement scenarios (4 and 5) and the `k6` harness.
- `lib/report.sh` and the JSON report envelope — scenarios now print plain
  PASS/FAIL.
- Local `pfctl`-based scenario-2 — superseded by the in-cluster Cilium policy.
