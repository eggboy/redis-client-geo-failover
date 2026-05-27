locals {
  resource_group_name = "${var.name_prefix}-rg"

  clusters = {
    primary = {
      name     = "${var.name_prefix}-primary"
      location = var.primary_location
    }
    secondary = {
      name     = "${var.name_prefix}-secondary"
      location = var.secondary_location
    }
  }

  merged_tags = merge(
    {
      managed_by  = "terraform"
      application = "amr-geo-failover"
    },
    var.tags,
  )

  # Validation: primary_location must differ from secondary_location.
  _validate_distinct_regions = (
    var.primary_location != var.secondary_location
    ? null
    : tobool("primary_location and secondary_location must be different Azure regions for active geo-replication.")
  )
}
