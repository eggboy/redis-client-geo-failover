# `infra/` — AMR geo-replication pair (Terraform)

Provisions everything the Spring Boot demo needs in a single `terraform apply`:

- One resource group (plain `azurerm_resource_group`).
- Two Azure Managed Redis (Redis Enterprise) caches in different regions.
- Both caches joined into one **active geo-replication group**, configured
  at database-creation time (the only time Azure allows it).
- Entra ID data-plane access for a single principal on both databases (the
  `default` access policy, full data-plane).
- TLS-only on port `10000`, clustering policy `EnterpriseCluster`, access
  keys disabled.

## Why no AVM modules?

The official AVM AMR module
[`Azure/avm-res-cache-redisenterprise/azurerm`](https://registry.terraform.io/modules/Azure/avm-res-cache-redisenterprise/azurerm)
**v0.2.0 does not yet expose `geoReplication`** (tracked in upstream
[PR #28](https://github.com/Azure/terraform-azurerm-avm-res-cache-redisenterprise/pull/28)).
Active geo-replication on AMR **must be configured at database-creation
time** — Microsoft docs explicitly state it cannot be added later or removed
once configured ([docs](https://learn.microsoft.com/azure/redis/how-to-active-geo-replication)).
So we drive the cache + database resources through `azapi_resource`
directly, against the same `Microsoft.Cache/redisEnterprise@2025-07-01` API
the AVM module uses internally.

The AVM resource-group module is also dropped in favour of the plain
`azurerm_resource_group` resource — we don't use any of the module's
value-adds (locks, role assignments, telemetry) and v0.4.0 carries an
upstream `retry.multiplier` deprecation warning.

When upstream AVM PR #28 ships, the AMR resources here can be replaced
with a call to the AVM module.

## Prerequisites

1. **Terraform 1.9+** and the `azurerm` (~> 4.0) + `azapi` (~> 2.0) providers
   (declared in `terraform.tf`).
2. **Authenticated Azure CLI** in a subscription where you have
   `Contributor` (or higher) at the subscription/resource-group scope.
3. **`Microsoft.Cache` provider registered** in the subscription:
   ```bash
   az provider register --namespace Microsoft.Cache
   ```
4. **AMR SKU availability in your chosen regions.** Verify before applying:
   ```bash
   az redisenterprise list-skus-for-scaling --location eastus
   az redisenterprise list-skus-for-scaling --location westus2
   ```
5. **An Entra principal object ID** (user, group, or service principal) that
   will be the data-plane identity for the Spring Boot demo. The simplest
   route is your own user object ID:
   ```bash
   az ad signed-in-user show --query id -o tsv
   ```
6. **An AKS cluster with OIDC issuer enabled.** The OIDC issuer URL is
   required for Workload Identity federation. Retrieve it with:
   ```bash
   az aks show --resource-group <rg> --name <cluster> \
     --query oidcIssuerProfile.issuerUrl -o tsv
   ```
   Copy the exact output (including any trailing slash) into
   `aks_oidc_issuer_url` — the federated credential issuer must match the
   token issuer exactly.

## Usage

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars

terraform init
terraform fmt -check
terraform validate
terraform plan  -out=tfplan
terraform apply tfplan
```

When apply completes, source the demo env vars and run the Spring Boot app:

```bash
eval "$(terraform output -raw spring_env)"
cd ..
mvn spring-boot:run
```

## SKU + geo-replication constraints

| SKU family      | Supports active geo-replication?               |
|-----------------|------------------------------------------------|
| `Balanced_B0/B1/B3/B5` | **No** (< 12 GB)                        |
| `Balanced_B10+` | Yes                                            |
| `MemoryOptimized_*`   | Yes                                      |
| `ComputeOptimized_*`  | Yes                                      |
| `EnterpriseFlash_*`   | Yes                                      |

`variables.tf` enforces the Balanced minimum via a `validation {}` block —
attempting `Balanced_B0` (etc.) fails at plan time. Default is
`Balanced_B10`.

## What gets created

| Logical name                       | Resource type                                                       |
|------------------------------------|---------------------------------------------------------------------|
| `<prefix>-rg`                      | `azurerm_resource_group`                                            |
| `<prefix>-primary` (eastus)        | `Microsoft.Cache/redisEnterprise`                                   |
| `<prefix>-primary`/databases/default | `Microsoft.Cache/redisEnterprise/databases` (geo-rep group joined) |
| `<prefix>-secondary` (westus2)     | `Microsoft.Cache/redisEnterprise`                                   |
| `<prefix>-secondary`/databases/default | `Microsoft.Cache/redisEnterprise/databases` (geo-rep group joined) |
| `demoappprimary` / `demoappsecondary` | `accessPolicyAssignments` (default policy → your Entra principal) |
| `<prefix>-app-mi`                  | `azurerm_user_assigned_identity` (UAMI for AKS Workload Identity)  |
| `<prefix>-aks-fed`                 | `azurerm_federated_identity_credential` (UAMI ↔ AKS OIDC ↔ SA)    |
| `aksmiprimary` / `aksmisecondary`  | `accessPolicyAssignments` (default policy → UAMI)                   |

## Outputs

| Output                            | Purpose                                                           |
|-----------------------------------|-------------------------------------------------------------------|
| `primary_hostname`                | Set as `AMR_PRIMARY_HOST` for the Spring Boot demo.               |
| `secondary_hostname`              | Set as `AMR_SECONDARY_HOST`.                                      |
| `primary_cache_id` / `secondary_cache_id` | ARM IDs for downstream tooling (e.g. RBAC scripts).       |
| `primary_location` / `secondary_location` | Azure regions of the primary and secondary caches.         |
| `resource_group_name`             | Name of the resource group containing both AMR caches.            |
| `geo_replication_group_nickname`  | Confirms the configured group name.                               |
| `uami_client_id`                  | Client ID of the UAMI — annotate the K8s ServiceAccount with it.  |
| `uami_principal_id`               | Principal (object) ID of the UAMI's service principal.            |
| `spring_env`                      | Ready-to-`eval` `export` block.                                   |

## Destroying

```bash
terraform destroy
```

Deleting a clustered database automatically removes it from the geo-rep
group ([docs](https://learn.microsoft.com/azure/redis/how-to-active-geo-replication#remove-from-geo-replication-group)).
No `prevent_destroy` is set — this is a demo.

If a region becomes unreachable mid-destroy, you may need to manually
force-unlink via `az redisenterprise database force-unlink-redis ...`.

## State backend

This module uses Terraform's default **local** backend (good enough for a
demo). For team / production use, add a backend block in `terraform.tf`,
e.g. `azurerm` backend pointing at a state storage account.
