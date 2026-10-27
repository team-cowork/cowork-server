variable "image" {
  type = string
  validation {
    condition     = var.image != "" && regex_replace(var.image, "^.+@sha256:[0-9a-f]{64}$", "") == ""
    error_message = "Use an immutable application image digest."
  }
}

variable "runtime_env" {
  type = map(string)
}

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

job "cowork-roadmap" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"

  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "roadmap"
  }

  group "app" {
    count = 1

    network {
      mode = "cni/cowork"
      port "health" {
        to           = -1
        host_network = "private"
      }
      port "metrics" {
        to           = -1
        host_network = "private"
      }
    }

    update {
      max_parallel      = 1
      canary            = 0
      min_healthy_time  = "15s"
      healthy_deadline  = "10m"
      progress_deadline = "15m"
      auto_revert       = false
    }

    restart {
      attempts = 3
      interval = "30m"
      delay    = "15s"
      mode     = "fail"
    }

    volume "cowork-ca" {
      type      = "host"
      source    = "cowork-ca"
      read_only = true
    }

    service {
      name         = "cowork-roadmap"
      provider     = "consul"
      port         = "8088"
      address_mode = "alloc"
      tags         = ["cowork", "application"]

      meta {
        health_address  = "${attr.unique.network.ip-address}"
        health_port     = "${NOMAD_HOST_PORT_health}"
        health_path     = "/actuator/health/readiness"
        metrics_address = "${attr.unique.network.ip-address}"
        metrics_port    = "${NOMAD_HOST_PORT_metrics}"
        metrics_path    = "/actuator/prometheus"
      }

      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }

      check {
        name     = "readiness"
        type     = "http"
        path     = "/actuator/health/readiness"
        port     = "health"
        interval = "10s"
        timeout  = "3s"
      }

      connect {
        sidecar_service {
          proxy {
            upstreams {
              destination_name   = "cowork-roadmap-db"
              local_bind_port    = 13306
              local_bind_address = "127.0.0.1"
            }
            expose {
              path {
                path            = "/actuator/health/readiness"
                protocol        = "http"
                local_path_port = 8088
                listener_port   = "health"
              }
              path {
                path            = "/actuator/prometheus"
                protocol        = "http"
                local_path_port = 8088
                listener_port   = "metrics"
              }
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

    task "app" {
      driver       = "docker"
      kill_timeout = "30s"
      env          = var.runtime_env

      config {
        image = var.image
      }

      resources {
        cpu        = 500
        memory     = 512
        memory_max = 2048
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }

      vault {
        role         = "cowork-roadmap"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/apps/roadmap?version=${var.secret_version}" }}
{{ range $key, $value := .Data.data }}
{{ $key }}={{ $value | toJSON }}
{{ end }}
{{ end }}
ENV
        destination          = "secrets/runtime.env"
        env                  = true
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }

      volume_mount {
        volume      = "cowork-ca"
        destination = "/etc/cowork/ca"
        read_only   = true
      }

      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
  }
}
