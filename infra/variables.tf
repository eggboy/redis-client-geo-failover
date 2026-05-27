variable "entra_principal_object_id" {
  type        = string
  description = "Object ID of the Entra ID principal (user, group, or service principal) that the Spring Boot demo will authenticate as. This identity is assigned the 'default' Redis Enterprise data-access policy on both caches."
  sensitive   = true

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.entra_principal_object_id))
    error_message = "entra_principal_object_id must be a GUID."
  }
}

variable "name_prefix" {
  type        = string
  description = "Prefix used to name the resource group and both Redis Enterprise caches (suffixed with '-primary' / '-secondary'). Must be lowercase alphanumeric with optional hyphens, 3-40 chars, no leading/trailing hyphen."

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$", var.name_prefix))
    error_message = "name_prefix must be 3-40 chars, lowercase alphanumeric and hyphens, with no leading or trailing hyphen."
  }
}

variable "geo_group_nickname" {
  type        = string
  default     = "amr-geo-demo"
  description = "Nickname for the AMR active geo-replication group. Both caches join this group; the value must match on every cache in the group."
  nullable    = false
}

variable "primary_location" {
  type        = string
  default     = "eastus"
  description = "Azure region for the primary AMR cache."
  nullable    = false
}

variable "secondary_location" {
  type        = string
  default     = "westus2"
  description = "Azure region for the secondary AMR cache. Must differ from primary_location."
  nullable    = false
}

variable "sku_name" {
  type        = string
  default     = "Balanced_B10"
  description = "SKU for both Redis Enterprise caches. Active geo-replication is only supported on Enterprise/EnterpriseFlash/Memory-Optimized SKUs and on Balanced SKUs of 12 GB or larger (Balanced_B10 and above)."
  nullable    = false

  validation {
    condition = !contains(
      ["Balanced_B0", "Balanced_B1", "Balanced_B3", "Balanced_B5"],
      var.sku_name
    )
    error_message = "Active geo-replication is not supported on Balanced_B0/B1/B3/B5. Use Balanced_B10 or larger, or any Memory-Optimized/Enterprise/EnterpriseFlash SKU."
  }
}

variable "aks_oidc_issuer_url" {
  type        = string
  description = "OIDC issuer URL of the AKS cluster. Used to federate the UAMI for Workload Identity."

  validation {
    condition     = can(regex("^https://", var.aks_oidc_issuer_url))
    error_message = "aks_oidc_issuer_url must be an HTTPS URL."
  }
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to the resource group and both caches."
  nullable    = false
}

variable "k8s_namespace" {
  type        = string
  default     = "amr-demo"
  description = "Kubernetes namespace where the demo workload runs. Used in the UAMI federated identity credential subject. Must match the namespace in deploy/k8s/ manifests."
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", var.k8s_namespace))
    error_message = "k8s_namespace must be a valid Kubernetes namespace name (lowercase alphanumeric and hyphens, 1-63 chars)."
  }
}

variable "k8s_service_account_name" {
  type        = string
  default     = "amr-demo-sa"
  description = "Kubernetes ServiceAccount name for the demo workload. Used in the UAMI federated identity credential subject. Must match the ServiceAccount in deploy/k8s/ manifests."
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", var.k8s_service_account_name))
    error_message = "k8s_service_account_name must be a valid Kubernetes ServiceAccount name (lowercase alphanumeric and hyphens, 1-63 chars)."
  }
}
