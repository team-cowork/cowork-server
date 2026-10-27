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
    DB_NAME     = "cowork_roadmap"
    DB_USERNAME = "cowork_roadmap"
  }
}

job "cowork-roadmap-db" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "roadmap"
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
        to           = 9104
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
      source    = "roadmap-db"
      read_only = false
    }
    service {
      name         = "cowork-roadmap-db"
      provider     = "consul"
      port         = "3306"
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
        command  = "/bin/sh"
        args     = ["-c", "mysqladmin --protocol=tcp -h 127.0.0.1 ping --silent >/dev/null 2>&1"]
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
      name         = "cowork-roadmap-db-metrics"
      provider     = "consul"
      port         = "metrics"
      address_mode = "host"
      tags         = ["cowork", "metrics", "database"]
      meta {
        metrics_address = "${attr.unique.network.ip-address}"
        metrics_port    = "${NOMAD_HOST_PORT_metrics}"
        metrics_path    = "/metrics"
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
        image = "mysql:9.7.2@sha256:29abb0a179982e4a8928138bfc7f918af9eda64e7eeb1b1d084c1720a20159e6"
        args  = ["--bind-address=127.0.0.1", "--mysqlx=0", "--innodb-buffer-pool-size=134217728", "--max-connections=100", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"]
      }
      env {
        MYSQL_DATABASE  = var.runtime_env["DB_NAME"]
        MYSQL_ROOT_HOST = "localhost"
      }
      resources {
        cpu    = 500
        memory = 768
      }
      volume_mount {
        volume      = "data"
        destination = "/var/lib/mysql"
        read_only   = false
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-roadmap-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/roadmap?version=${var.secret_version}" }}
MYSQL_ROOT_PASSWORD={{ .Data.data.DB_ADMIN_PASSWORD | toJSON }}
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
        image   = "mysql:9.7.2@sha256:29abb0a179982e4a8928138bfc7f918af9eda64e7eeb1b1d084c1720a20159e6"
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
        role         = "cowork-roadmap-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/roadmap?version=${var.secret_version}" }}
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
        data        = var.config_files["mysql-init.sh"]
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

      })
      config {
        image = "prom/mysqld-exporter:v0.20.0@sha256:abed8dac117b4ae5b70757f988e44795935b9a72c3b58d720c67ba688f8cb79e"
        args  = ["--mysqld.address=127.0.0.1:3306", "--mysqld.username=cowork_monitoring"]
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
        role         = "cowork-roadmap-db"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/roadmap?version=${var.secret_version}" }}
MYSQLD_EXPORTER_PASSWORD={{ .Data.data.DB_MONITORING_PASSWORD | toJSON }}
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
