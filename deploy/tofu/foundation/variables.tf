variable "manifest" {
  description = "The committed release manifest, containing only image digests and KV versions."
  type = object({
    schema_version  = number
    source_sha      = string
    release_version = string
    applications = map(object({
      image               = string
      image_source_sha    = string
      version             = string
      secret_version      = number
      data_secret_version = number
    }))
    infrastructure_images    = map(string)
    data_secret_versions     = map(number)
    platform_secret_versions = map(number)
  })
  validation {
    condition     = var.manifest.schema_version == 1 && can(regex("^[0-9a-f]{40}$", var.manifest.source_sha))
    error_message = "Use a schema 1 manifest with a full source commit SHA."
  }
  validation {
    condition     = alltrue([for app in values(var.manifest.applications) : can(regex("@sha256:[0-9a-f]{64}$", app.image)) && app.secret_version >= 1 && floor(app.secret_version) == app.secret_version]) && alltrue([for image in values(var.manifest.infrastructure_images) : can(regex("@sha256:[0-9a-f]{64}$", image))])
    error_message = "Images must use immutable digests and secret versions must be positive integers."
  }
}
variable "platform_ca_file" {
  type        = string
  description = "Absolute path to the public platform CA certificate."
}
variable "vault_mount" {
  type    = string
  default = "secret"
  validation {
    condition     = can(regex("^[a-zA-Z0-9_-]+$", var.vault_mount))
    error_message = "Use one KV mount name."
  }
}
variable "state_passphrase" {
  type        = string
  sensitive   = true
  ephemeral   = true
  description = "Persistent random encryption passphrase supplied using TF_VAR_state_passphrase."
  validation {
    condition     = length(var.state_passphrase) >= 32
    error_message = "The state encryption passphrase must contain at least 32 characters."
  }
}

variable "consul_address" {
  type        = string
  default     = "10.0.0.111:8501"
  description = "Consul HTTPS host:port; may be overridden for an operator SSH tunnel."
  validation {
    condition     = can(regex("^[a-zA-Z0-9.-]+:[0-9]+$", var.consul_address))
    error_message = "Use a TLS endpoint without credentials or a path."
  }
}

variable "vault_address" {
  type        = string
  default     = "https://10.0.0.91:8210"
  description = "Vault HTTPS origin; may be overridden for an operator SSH tunnel."
  validation {
    condition     = can(regex("^https://[a-zA-Z0-9.-]+:[0-9]+$", var.vault_address))
    error_message = "Use a TLS endpoint without credentials or a path."
  }
}
