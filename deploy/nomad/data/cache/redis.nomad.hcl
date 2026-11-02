variable "envoy_image" {
  type = string
}
variable "vault_mount" {
  type    = string
  default = "secret"
}
variable "secret_version" {
  type    = number
  default = 1
}
variable "runtime_env" {
  type    = map(string)
  default = {}
}
job "cowork-redis" {
  datacenters = ["gsm"]
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "redis"
  }
  group "redis" {
    count = 1
    network {
      mode = "cni/cowork"
    }
    volume "data" {
      type      = "host"
      source    = "redis-data"
      read_only = false
    }
    update {
      max_parallel      = 1
      canary            = 0
      healthy_deadline  = "5m"
      progress_deadline = "10m"
      auto_revert       = false
    }
    restart {
      attempts = 3
      interval = "30m"
      delay    = "15s"
      mode     = "fail"
    }
    service {
      name         = "cowork-redis"
      provider     = "consul"
      port         = "6379"
      address_mode = "alloc"
      tags         = ["cowork", "data"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "redis-ping"
        type     = "script"
        task     = "redis"
        command  = "redis-cli"
        args     = ["-h", "127.0.0.1", "ping"]
        interval = "10s"
        timeout  = "3s"
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
    task "redis" {
      driver       = "docker"
      user         = "999:999"
      kill_timeout = "60s"
      config {
        image    = "redis:8.10.1-alpine@sha256:becdda6c7f4b3fb42e42fd7f120bbf5c54c4caaaf16f26da24e4563d2c1f0576"
        args     = ["redis-server", "--bind", "127.0.0.1", "--protected-mode", "yes", "--dir", "/data", "--appendonly", "yes", "--appendfsync", "everysec", "--maxmemory", "256mb", "--maxmemory-policy", "noeviction"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "data"
        destination = "/data"
      }
      resources {
        cpu        = 250
        memory     = 256
        memory_max = 512
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
  }
}
