output "geo_replication_group_nickname" {
  description = "Nickname of the active geo-replication group that both caches joined."
  value       = var.geo_group_nickname
}

output "primary_cache_id" {
  description = "Azure resource ID of the primary Redis Enterprise cluster."
  value       = azapi_resource.cluster["primary"].id
}

output "primary_hostname" {
  description = "Public hostname of the primary AMR cache. Use this as AMR_PRIMARY_HOST for the Spring Boot demo."
  value       = azapi_resource.cluster["primary"].output.properties.hostName
}

output "primary_location" {
  description = "Azure region of the primary AMR cache."
  value       = azapi_resource.cluster["primary"].location
}

output "resource_group_name" {
  description = "Name of the resource group containing both AMR caches."
  value       = azurerm_resource_group.main.name
}

output "secondary_cache_id" {
  description = "Azure resource ID of the secondary Redis Enterprise cluster."
  value       = azapi_resource.cluster["secondary"].id
}

output "secondary_hostname" {
  description = "Public hostname of the secondary AMR cache. Use this as AMR_SECONDARY_HOST for the Spring Boot demo."
  value       = azapi_resource.cluster["secondary"].output.properties.hostName
}

output "secondary_location" {
  description = "Azure region of the secondary AMR cache."
  value       = azapi_resource.cluster["secondary"].location
}

output "spring_env" {
  description = "Ready-to-source environment block for `mvn spring-boot:run`."
  value       = <<-EOT
    export AMR_PRIMARY_HOST=${azapi_resource.cluster["primary"].output.properties.hostName}
    export AMR_SECONDARY_HOST=${azapi_resource.cluster["secondary"].output.properties.hostName}
  EOT
}

output "uami_client_id" {
  description = "Client ID of the User-Assigned Managed Identity for AKS Workload Identity."
  value       = azurerm_user_assigned_identity.app.client_id
}

output "uami_principal_id" {
  description = "Principal (object) ID of the UAMI's service principal."
  value       = azurerm_user_assigned_identity.app.principal_id
}
