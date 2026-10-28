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

job "cowork-voice-db" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "voice"
  }
  group "database" {
    count          = 1
    shutdown_delay = "5s"
    # A changed credential or initializer needs a fresh allocation to rerun poststart.
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version, scripts = var.config_files }))
    }
    network {
      mode = "cni/cowork"
      port "metrics" {
        to           = 9216
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
      source    = "voice-db"
      read_only = false
    }
    service {
      name         = "cowork-voice-db"
      provider     = "consul"
      port         = "27017"
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
        command  = "mongosh"
        args     = ["--host", "127.0.0.1", "--quiet", "--norc", "--eval", "if(db.runCommand({ping:1}).ok!==1)quit(1)"]
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
      name         = "cowork-voice-db-metrics"
      provider     = "consul"
      port         = "metrics"
      address_mode = "host"
      tags         = ["cowork", "metrics", "database"]
      meta {
        metrics_address = "${attr.unique.network.ip-address}"
        metrics_port    = "${NOMAD_HOST_PORT_metrics}"
        metrics_path    = "/metrics"
        metrics_service = "mongodb"
        module          = "voice"
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
        image = "mongo:8.3.11@sha256:5d7043a4ffe02b9ed1b6e0bab057546981af5ca0a79107e9c461e49bc44c0a7b"
        args  = ["mongod", "--auth", "--bind_ip", "127.0.0.1", "--wiredTigerCacheSizeGB", "0.25"]
        mount {
          type   = "tmpfs"
          target = "/data/configdb"
        }
      }
      env {
        MONGO_INITDB_ROOT_USERNAME = "cowork_admin"
      }
      resources {
        cpu    = 500
        memory = 512
      }
      volume_mount {
        volume      = "data"
        destination = "/data/db"
        read_only   = false
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-voice-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/voice?version=${var.secret_version}" }}
MONGO_INITDB_ROOT_PASSWORD={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
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
        image   = "mongo:8.3.11@sha256:5d7043a4ffe02b9ed1b6e0bab057546981af5ca0a79107e9c461e49bc44c0a7b"
        command = "/bin/bash"
        args    = ["${NOMAD_TASK_DIR}/initialize.sh"]
      }
      restart {
        attempts = 0
        mode     = "fail"
      }
      resources {
        cpu    = 100
        memory = 256
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-voice-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/voice?version=${var.secret_version}" }}
DB_NAME={{ .Data.data.DB_NAME | toJSON }}
DB_USERNAME={{ .Data.data.DB_USERNAME | toJSON }}
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
        data        = var.config_files["mongodb-init.sh"]
        destination = "local/initialize.sh"
        perms       = "0500"
        change_mode = "restart"
      }
      template {
        data        = var.config_files["mongodb-init.js"]
        destination = "local/initialize.js"
        perms       = "0400"
        change_mode = "restart"
      }
      logs {
        max_files     = 2
        max_file_size = 5
      }
    }
    task "exporter" {
      driver = "docker"
      env {
        MONGODB_URI  = "mongodb://127.0.0.1:27017/admin?authSource=admin"
        MONGODB_USER = "cowork_monitoring"
      }
      config {
        image = "percona/mongodb_exporter:0.53.0@sha256:0214e482b2cd4b1849f9f4dcef90d328a99ba3727dd1103e7da48b42060bcec1"
        args  = ["--compatible-mode"]
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
        role         = "cowork-voice-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/voice?version=${var.secret_version}" }}
MONGODB_PASSWORD={{ .Data.data.DB_MONITORING_PASSWORD | toJSON }}
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
