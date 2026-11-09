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

job "cowork-applet-db" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "applet"
  }
  group "database" {
    count          = 1
    shutdown_delay = "5s"
    # A changed credential needs a fresh allocation to rerun poststart.
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version }))
    }
    network {
      mode = "cni/cowork"
      port "metrics" {
        to           = -1
        host_network = "private"
      }
    }
    update {
      max_parallel      = 1
      canary            = 0
      min_healthy_time  = "10s"
      healthy_deadline  = "10m"
      progress_deadline = "15m"
      auto_revert       = false
    }
    migrate {
      max_parallel     = 1
      health_check     = "checks"
      min_healthy_time = "10s"
      healthy_deadline = "10m"
    }
    volume "data" {
      type      = "host"
      source    = "applet-db"
      read_only = false
    }
    service {
      name         = "cowork-applet-db"
      provider     = "consul"
      port         = "8000"
      address_mode = "alloc"
      tags         = ["cowork", "database"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "database"
        type     = "script"
        task     = "database"
        command  = "/surreal"
        args     = ["is-ready", "--endpoint", "http://127.0.0.1:8000"]
        interval = "10s"
        timeout  = "5s"
      }
      connect {
        sidecar_service {
          proxy {
            # The SurrealDB HTTP port also serves queries, so only this path leaves the allocation.
            expose {
              path {
                path            = "/metrics"
                protocol        = "http"
                local_path_port = 8000
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
    service {
      name         = "cowork-applet-db-metrics"
      provider     = "consul"
      port         = "metrics"
      address_mode = "host"
      tags         = ["cowork", "metrics", "database"]
      meta {
        metrics_address = "${attr.unique.network.ip-address}"
        metrics_port    = "${NOMAD_HOST_PORT_metrics}"
        metrics_path    = "/metrics"
        metrics_service = "surrealdb"
        module          = "applet"
      }
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "metrics"
        type     = "http"
        path     = "/metrics"
        interval = "15s"
        timeout  = "5s"
      }
    }
    task "database" {
      driver       = "docker"
      kill_timeout = "60s"
      config {
        image = "surrealdb/surrealdb:v4.0.0@sha256:c35893410644e65fa90aa1bef446ac9cb0a0c2733b9d405ce3eb56754b85c330"
        # SurrealKV sizes its cache from host memory by default, which exceeds this task's limit on the 4 GB VM.
        args = ["start", "--bind", "127.0.0.1:8000", "--log", "info", "surrealkv:///data/applet?sync=every&block_cache_capacity=134217728&max_memtable_size=67108864"]
      }
      env {
        SURREAL_MEMORY_THRESHOLD = "640mb"
      }
      resources {
        cpu    = 500
        memory = 768
      }
      volume_mount {
        volume      = "data"
        destination = "/data"
        read_only   = false
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-applet-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/applet?version=${var.secret_version}" }}
SURREAL_USER="cowork_admin"
SURREAL_PASS={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
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
    task "initialize" {
      driver = "docker"
      lifecycle {
        hook    = "poststart"
        sidecar = false
      }
      config {
        image = "surrealdb/surrealdb:v4.0.0@sha256:c35893410644e65fa90aa1bef446ac9cb0a0c2733b9d405ce3eb56754b85c330"
        args  = ["import", "--endpoint", "http://127.0.0.1:8000", "${NOMAD_SECRETS_DIR}/initialize.surql"]
      }
      # The image has no shell for a wait loop, so Nomad retries until the database accepts connections.
      restart {
        attempts = 20
        interval = "5m"
        delay    = "5s"
        mode     = "fail"
      }
      resources {
        cpu    = 100
        memory = 128
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-applet-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/applet?version=${var.secret_version}" }}
SURREAL_USER="cowork_admin"
SURREAL_PASS={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
SURREAL_NAMESPACE="cowork"
SURREAL_DATABASE={{ .Data.data.DB_NAME | toJSON }}
{{ end }}
ENV
        destination          = "secrets/runtime.env"
        env                  = true
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }
      template {
        data                 = <<-SURQL
{{ with secret "${var.vault_mount}/data/nomad/data/applet?version=${var.secret_version}" }}
OPTION IMPORT;
DEFINE NAMESPACE IF NOT EXISTS cowork;
USE NS cowork;
DEFINE DATABASE IF NOT EXISTS {{ .Data.data.DB_NAME }};
USE DB {{ .Data.data.DB_NAME }};
DEFINE USER OVERWRITE {{ .Data.data.DB_USERNAME }} ON DATABASE PASSWORD {{ .Data.data.DB_PASSWORD | toJSON }} ROLES EDITOR;
{{ end }}
SURQL
        destination          = "secrets/initialize.surql"
        perms                = "0400"
        uid                  = 65532
        gid                  = 65532
        change_mode          = "restart"
        error_on_missing_key = true
      }
      logs {
        max_files     = 2
        max_file_size = 5
      }
    }
  }
}
