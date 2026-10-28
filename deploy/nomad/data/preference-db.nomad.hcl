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
variable "config_files" {
  type = map(string)
}
variable "runtime_env" {
  type = map(string)
  default = {
    DB_NAME     = "cowork_preference"
    DB_USERNAME = "cowork_preference"
  }
}

job "cowork-preference-db" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "preference"
  }
  group "database" {
    count          = 1
    shutdown_delay = "5s"
    # A changed credential or initializer needs a fresh allocation to rerun poststart.
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version, scripts = var.config_files, identity = var.runtime_env }))
    }
    network {
      mode = "cni/cowork"
      port "metrics" {
        to           = 9187
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
      source    = "preference-db"
      read_only = false
    }
    service {
      name         = "cowork-preference-db"
      provider     = "consul"
      port         = "5432"
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
        command  = "pg_isready"
        args     = ["-h", "127.0.0.1", "-U", "postgres"]
        interval = "10s"
        timeout  = "5s"
      }
      connect {
        sidecar_service {}
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
      name         = "cowork-preference-db-metrics"
      provider     = "consul"
      port         = "metrics"
      address_mode = "host"
      tags         = ["cowork", "metrics", "database"]
      meta {
        metrics_address = "${attr.unique.network.ip-address}"
        metrics_port    = "${NOMAD_HOST_PORT_metrics}"
        metrics_path    = "/metrics"
        metrics_service = "postgres"
        module          = "preference"
      }
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "exporter"
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
        image = "postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"
        args  = ["postgres", "-c", "listen_addresses=127.0.0.1", "-c", "shared_buffers=128MB", "-c", "max_connections=100"]
      }
      env {
        POSTGRES_DB          = var.runtime_env["DB_NAME"]
        POSTGRES_INITDB_ARGS = "--auth-host=scram-sha-256"
      }
      resources {
        cpu    = 500
        memory = 512
      }
      volume_mount {
        volume      = "data"
        destination = "/var/lib/postgresql"
        read_only   = false
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-preference-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/preference?version=${var.secret_version}" }}
POSTGRES_PASSWORD={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
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
      env    = var.runtime_env
      lifecycle {
        hook    = "poststart"
        sidecar = false
      }
      config {
        image   = "postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"
        command = "/bin/bash"
        args    = ["${NOMAD_TASK_DIR}/initialize.sh"]
      }
      restart {
        attempts = 0
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
        role         = "cowork-preference-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/preference?version=${var.secret_version}" }}
DB_ADMIN_PASSWORD={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
DB_PASSWORD={{ .Data.data.DB_PASSWORD | toJSON }}
DB_MONITORING_PASSWORD={{ .Data.data.DB_MONITORING_PASSWORD | toJSON }}
{{ end }}
ENV
        destination          = "secrets/runtime.env"
        env                  = true
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }
      template {
        data        = var.config_files["postgres-init.sh"]
        destination = "local/initialize.sh"
        perms       = "0500"
        change_mode = "restart"
      }
      logs {
        max_files     = 2
        max_file_size = 5
      }
    }
    task "exporter" {
      driver = "docker"
      env = merge(var.runtime_env, {
        DATA_SOURCE_URI  = "127.0.0.1:5432/${var.runtime_env["DB_NAME"]}?sslmode=disable"
        DATA_SOURCE_USER = "cowork_monitoring"
      })
      config {
        image = "prometheuscommunity/postgres-exporter:v0.20.1@sha256:ac5ec343104fae0e2d84a27bb8d69b38430a11910c5382cad85d478d2bab713e"
        ports = ["metrics"]
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
        role         = "cowork-preference-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/preference?version=${var.secret_version}" }}
DATA_SOURCE_PASS={{ .Data.data.DB_MONITORING_PASSWORD | toJSON }}
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
