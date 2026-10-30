variable "jobs" {
  description = "Native jobspecs and HCL2 inputs assembled by deploy.nomad.render."
  type        = map(object({ jobspec = string, variables = map(string) }))
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

variable "nomad_address" {
  type        = string
  default     = "https://10.0.0.111:4646"
  description = "Nomad HTTPS origin; may be overridden for an operator SSH tunnel."
  validation {
    condition     = can(regex("^https://[a-zA-Z0-9.-]+:[0-9]+$", var.nomad_address))
    error_message = "Use a TLS endpoint without credentials or a path."
  }
}
