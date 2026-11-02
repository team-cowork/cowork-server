variable "envoy_image" {
  type = string
}
variable "init_image" {
  type = string
  validation {
    condition     = var.init_image != "" && regex_replace(var.init_image, "^.+@sha256:[0-9a-f]{64}$", "") == ""
    error_message = "Pin the Kafka initialization image digest."
  }
}
variable "vault_mount" {
  type    = string
  default = "secret"
}
variable "secret_version" {
  type = number
}
variable "runtime_env" {
  type    = map(string)
  default = {}
}
variable "config_files" {
  type = map(string)
}
job "cowork-kafka" {
  datacenters = ["gsm"]
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "kafka"
  }
  group "kafka" {
    count          = 1
    shutdown_delay = "5s"
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version, files = var.config_files, image = var.init_image }))
    }
    network {
      mode = "cni/cowork"
      port "broker" {
        static       = 9094
        to           = 9094
        host_network = "private"
      }
    }
    volume "data" {
      type      = "host"
      source    = "kafka-data"
      read_only = false
    }
    volume "tls" {
      type      = "host"
      source    = "kafka-tls"
      read_only = true
    }
    update {
      max_parallel      = 1
      canary            = 0
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
    service {
      name     = "cowork-kafka"
      provider = "consul"
      port     = "broker"
      tags     = ["cowork", "data", "sasl-ssl"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "initialized-broker"
        type     = "script"
        task     = "broker"
        command  = "/bin/bash"
        args     = ["-ec", "test -f /alloc/data/bootstrap-ready && /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server 127.0.0.1:9092 >/dev/null"]
        interval = "30s"
        timeout  = "15s"
      }
    }
    task "broker" {
      driver       = "docker"
      user         = "1000:1000"
      kill_timeout = "60s"
      env = merge(var.runtime_env, {
        KAFKA_HEAP_OPTS = "-Xms512m -Xmx1024m"
        LOG_DIR         = "/alloc/data/kafka-logs"
      })
      config {
        image      = "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"
        entrypoint = ["/bin/bash", "/local/kafka/start-broker.sh"]
        ports      = ["broker"]
        cap_drop   = ["ALL"]
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-kafka"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/kafka?version=${var.secret_version}" }}
KAFKA_CLUSTER_ID={{ .Data.data.KAFKA_CLUSTER_ID | toJSON }}
{{ end }}
ENV
        destination          = "secrets/broker.env"
        env                  = true
        perms                = "0400"
        uid                  = 1000
        gid                  = 1000
        change_mode          = "restart"
        error_on_missing_key = true
      }
      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if contains(["server.properties", "start-broker.sh"], path) }
        content {
          data            = template.value
          destination     = "local/kafka/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }
      volume_mount {
        volume      = "data"
        destination = "/var/lib/kafka/data"
      }
      volume_mount {
        volume      = "tls"
        destination = "/etc/cowork/kafka-tls"
        read_only   = true
      }
      resources {
        cpu        = 1000
        memory     = 1536
        memory_max = 2048
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "initialize" {
      driver = "docker"
      user   = "1000:1000"
      lifecycle {
        hook    = "poststart"
        sidecar = false
      }
      config {
        image    = var.init_image
        cap_drop = ["ALL"]
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-kafka"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-JSON
{{ with secret "${var.vault_mount}/data/nomad/data/kafka?version=${var.secret_version}" }}{{ .Data.data.KAFKA_CREDENTIALS_JSON }}{{ end }}
JSON
        destination          = "secrets/credentials.json"
        perms                = "0400"
        uid                  = 1000
        gid                  = 1000
        change_mode          = "restart"
        error_on_missing_key = true
      }
      template {
        data            = var.config_files["policy.json"]
        destination     = "local/kafka/policy.json"
        perms           = "0444"
        left_delimiter  = "[[["
        right_delimiter = "]]]"
        change_mode     = "restart"
      }
      resources {
        cpu        = 250
        memory     = 128
        memory_max = 256
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
  }
}
