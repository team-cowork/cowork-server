variable "secret_version" {
  type = number
  validation {
    condition     = var.secret_version >= 1 && floor(var.secret_version) == var.secret_version
    error_message = "Pin a positive Vault KV version."
  }
}
variable "envoy_image" {
  type = string
  validation {
    condition     = var.envoy_image != "" && regex_replace(var.envoy_image, "^.+@sha256:[0-9a-f]{64}$", "") == ""
    error_message = "Use an immutable Envoy image digest."
  }
}
variable "vault_mount" {
  type    = string
  default = "secret"
}
variable "image" {
  type = string
  validation {
    condition     = var.image != "" && regex_replace(var.image, "^.+@sha256:[0-9a-f]{64}$", "") == ""
    error_message = "Use an immutable image digest."
  }
}
variable "runtime_env" {
  type = map(string)
  default = {
    DB_HOST     = "127.0.0.1"
    DB_PORT     = "13306"
    DB_NAME     = "cowork_user"
    DB_USERNAME = "cowork_user"
  }
}

job "cowork-user-migrations" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "batch"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "user"
  }
  group "migrations" {
    count = 1
    network {
      mode = "cni/cowork"
    }
    restart {
      attempts = 0
      mode     = "fail"
    }
    reschedule {
      attempts  = 0
      unlimited = false
    }
    service {
      name         = "cowork-user-migrations"
      provider     = "consul"
      address_mode = "alloc"
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      connect {
        sidecar_service {
          proxy {
            upstreams {
              destination_name   = "cowork-user-db"
              local_bind_port    = 13306
              local_bind_address = "127.0.0.1"
            }
          }
        }
        sidecar_task {
          config {
            image = var.envoy_image
          }
          resources {
            cpu    = 100
            memory = 128
          }
        }
      }
    }
    task "migrate" {
      driver = "docker"
      leader = true
      env    = var.runtime_env
      config {
        image = var.image
        args  = ["migrate"]
      }
      resources {
        cpu        = 500
        memory     = 256
        memory_max = 512
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-user-migrations"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/user?version=${var.secret_version}" }}
DB_PASSWORD={{ .Data.data.DB_PASSWORD | toJSON }}
{{ end }}
ENV
        destination          = "secrets/runtime.env"
        env                  = true
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
  }
}
