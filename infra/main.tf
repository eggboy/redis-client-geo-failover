resource "azurerm_resource_group" "main" {
  name     = local.resource_group_name
  location = var.primary_location
  tags     = local.merged_tags
}

# Redis Enterprise clusters (one per region).
#
# Implementation note: the official AVM module
# `Azure/avm-res-cache-redisenterprise/azurerm` v0.2.0 does NOT support
# active geo-replication (tracked in upstream PR
# https://github.com/Azure/terraform-azurerm-avm-res-cache-redisenterprise/pull/28).
# Active geo-rep MUST be configured at database-creation time per
# https://learn.microsoft.com/azure/redis/how-to-active-geo-replication
# and cannot be added later. We therefore drive the resources through
# `azapi_resource` directly, against the same `Microsoft.Cache/redisEnterprise`
# 2025-07-01 API the AVM module itself uses internally.
resource "azapi_resource" "cluster" {
  for_each = local.clusters

  type      = "Microsoft.Cache/redisEnterprise@2025-07-01"
  name      = each.value.name
  parent_id = azurerm_resource_group.main.id
  location  = each.value.location

  body = {
    sku = {
      name = var.sku_name
    }
    properties = {
      minimumTlsVersion   = "1.2"
      publicNetworkAccess = "Enabled"
      highAvailability    = "Enabled"
    }
  }

  response_export_values = ["properties.hostName"]
  tags                   = local.merged_tags

  timeouts {
    create = "60m"
    update = "60m"
    delete = "30m"
  }
}

# Primary database — created as the FIRST member of a single-member geo-rep
# group. Per the BadRequest error returned when the primary lacks geo-rep
# config ("'properties.geoReplication' cannot be changed... You can only
# create new replica databases..."), the secondary's join requires the
# primary to already belong to a group with the same `groupNickname`. So we
# create the primary with `linkedDatabases = [primary_self_id]` (a group of
# one), then the secondary joins that group.
resource "azapi_resource" "database_primary" {
  type      = "Microsoft.Cache/redisEnterprise/databases@2025-07-01"
  name      = "default"
  parent_id = azapi_resource.cluster["primary"].id

  body = {
    properties = {
      clientProtocol           = "Encrypted"
      port                     = 10000
      clusteringPolicy         = "EnterpriseCluster"
      evictionPolicy           = "NoEviction"
      accessKeysAuthentication = "Disabled"
      modules = [
        { name = "RediSearch" },
        { name = "RedisJSON" },
      ]
      geoReplication = {
        groupNickname = var.geo_group_nickname
        linkedDatabases = [
          { id = "${azapi_resource.cluster["primary"].id}/databases/default" },
        ]
      }
    }
  }

  lifecycle {
    # After the secondary joins, Azure adds the secondary to this database's
    # linkedDatabases. Ignore that drift so re-apply doesn't try to revert it.
    ignore_changes = [body.properties.geoReplication]
  }

  timeouts {
    create = "60m"
    update = "60m"
    delete = "60m"
  }
}

# Secondary database — joins the existing single-member group by including
# both database IDs in linkedDatabases. This must run AFTER the primary is
# already a member of the group (otherwise Azure returns 400 BadRequest
# "geoReplication cannot be changed for an existing database").
resource "azapi_resource" "database_secondary" {
  type      = "Microsoft.Cache/redisEnterprise/databases@2025-07-01"
  name      = "default"
  parent_id = azapi_resource.cluster["secondary"].id

  body = {
    properties = {
      clientProtocol           = "Encrypted"
      port                     = 10000
      clusteringPolicy         = "EnterpriseCluster"
      evictionPolicy           = "NoEviction"
      accessKeysAuthentication = "Disabled"
      modules = [
        { name = "RediSearch" },
        { name = "RedisJSON" },
      ]
      geoReplication = {
        groupNickname = var.geo_group_nickname
        linkedDatabases = [
          { id = "${azapi_resource.cluster["primary"].id}/databases/default" },
          { id = "${azapi_resource.cluster["secondary"].id}/databases/default" },
        ]
      }
    }
  }

  depends_on = [azapi_resource.database_primary]

  lifecycle {
    ignore_changes = [body.properties.geoReplication]
  }

  timeouts {
    create = "90m"
    update = "90m"
    delete = "60m"
  }
}

# Entra ID data-plane access. Assigns the built-in `default` access policy
# (full data-plane access) to the user-supplied principal on both databases.
# Name must be alphanumeric (1-60 chars).
resource "azapi_resource" "access_policy_assignment" {
  for_each = {
    primary   = azapi_resource.database_primary.id
    secondary = azapi_resource.database_secondary.id
  }

  type      = "Microsoft.Cache/redisEnterprise/databases/accessPolicyAssignments@2025-07-01"
  name      = "demoapp${each.key}"
  parent_id = each.value

  body = {
    properties = {
      accessPolicyName = "default"
      user = {
        objectId = var.entra_principal_object_id
      }
    }
  }
}

# ---------------------------------------------------------------------------
# User-Assigned Managed Identity for AKS Workload Identity.
# The app pod authenticates as this UAMI via DefaultAzureCredential,
# which picks up the AZURE_* env vars injected by the AKS workload-identity
# webhook.
# ---------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "app" {
  name                = "${var.name_prefix}-app-mi"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tags                = local.merged_tags
}

resource "azurerm_federated_identity_credential" "aks" {
  name                = "${var.name_prefix}-aks-fed"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.app.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = var.aks_oidc_issuer_url
  subject             = "system:serviceaccount:${var.k8s_namespace}:${var.k8s_service_account_name}"
}

# Allow Entra propagation before assigning AMR access policies to the UAMI.
resource "time_sleep" "uami_propagation" {
  depends_on      = [azurerm_user_assigned_identity.app]
  create_duration = "30s"
}

resource "azapi_resource" "uami_access_policy_assignment" {
  for_each = {
    primary   = azapi_resource.database_primary.id
    secondary = azapi_resource.database_secondary.id
  }

  type      = "Microsoft.Cache/redisEnterprise/databases/accessPolicyAssignments@2025-07-01"
  name      = "aksmi${each.key}"
  parent_id = each.value

  body = {
    properties = {
      accessPolicyName = "default"
      user = {
        objectId = azurerm_user_assigned_identity.app.principal_id
      }
    }
  }

  depends_on = [time_sleep.uami_propagation]
}
