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
variable "image" {
  type = string
  validation {
    condition     = var.image != "" && regex_replace(var.image, "^.+@sha256:[0-9a-f]{64}$", "") == ""
    error_message = "Use an immutable image digest."
  }
}

job "cowork-chat-search" {
  datacenters = ["gsm"]
  namespace   = "default"
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "chat"
  }
  group "search" {
    count          = 1
    shutdown_delay = "5s"
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version, scripts = var.config_files }))
    }
    network {
      mode = "cni/cowork"
    }
    update {
      max_parallel      = 1
      canary            = 0
      min_healthy_time  = "10s"
      healthy_deadline  = "10m"
      progress_deadline = "15m"
      auto_revert       = false
    }
    volume "data" {
      type      = "host"
      source    = "chat-search"
      read_only = false
    }
    service {
      name         = "cowork-chat-search"
      provider     = "consul"
      port         = "9200"
      address_mode = "alloc"
      tags         = ["cowork", "search"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "authenticated-search"
        type     = "script"
        task     = "search"
        command  = "/bin/bash"
        args     = ["-c", "test \"$(curl -s -o /dev/null -w '%%{http_code}' http://127.0.0.1:9200/)\" = 401"]
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
    task "search" {
      driver       = "docker"
      kill_timeout = "60s"
      config {
        image = var.image
        args  = ["-Enetwork.host=127.0.0.1", "-Ediscovery.type=single-node", "-Ecluster.name=cowork-chat-search", "-Expack.security.enabled=true", "-Expack.security.autoconfiguration.enabled=false", "-Expack.security.http.ssl.enabled=false", "-Expack.security.transport.ssl.enabled=false"]
        ulimit {
          nofile = "65535:65535"
        }
      }
      env {
        ES_JAVA_OPTS = "-Xms512m -Xmx512m"
      }
      resources {
        cpu    = 500
        memory = 1024
      }
      volume_mount {
        volume      = "data"
        destination = "/usr/share/elasticsearch/data"
        read_only   = false
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-chat-search"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/chat/search?version=${var.secret_version}" }}
ELASTIC_PASSWORD={{ .Data.data.ELASTICSEARCH_ADMIN_PASSWORD | toJSON }}
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
      user   = "1000:0"
      lifecycle {
        hook    = "poststart"
        sidecar = false
      }
      config {
        image   = var.image
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
        role         = "cowork-chat-search"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data        = var.config_files["init.sh"]
        destination = "local/initialize.sh"
        perms       = "0400"
        uid         = 1000
        gid         = 0
        change_mode = "restart"
      }
      template {
        data        = var.config_files["role.json"]
        destination = "local/role.json"
        perms       = "0400"
        uid         = 1000
        gid         = 0
        change_mode = "restart"
      }
      template {
        data                 = <<-CONFIG
{{ with secret "${var.vault_mount}/data/nomad/data/chat/search?version=${var.secret_version}" }}
header = "Authorization: Basic {{ printf "elastic:%s" .Data.data.ELASTICSEARCH_ADMIN_PASSWORD | base64Encode }}"
header = "Content-Type: application/json"
{{ end }}
CONFIG
        destination          = "secrets/admin.curl"
        perms                = "0400"
        uid                  = 1000
        gid                  = 0
        change_mode          = "restart"
        error_on_missing_key = true
      }
      template {
        data                 = <<-CONFIG
{{ with secret "${var.vault_mount}/data/nomad/data/chat/search?version=${var.secret_version}" }}
header = "Authorization: Basic {{ printf "cowork_chat:%s" .Data.data.ELASTICSEARCH_PASSWORD | base64Encode }}"
header = "Content-Type: application/json"
{{ end }}
CONFIG
        destination          = "secrets/application.curl"
        perms                = "0400"
        uid                  = 1000
        gid                  = 0
        change_mode          = "restart"
        error_on_missing_key = true
      }
      template {
        data                 = <<-JSON
{{ with secret "${var.vault_mount}/data/nomad/data/chat/search?version=${var.secret_version}" }}
{"password": {{ .Data.data.ELASTICSEARCH_PASSWORD | toJSON }}, "roles": ["cowork_chat_search"], "enabled": true}
{{ end }}
JSON
        destination          = "secrets/user.json"
        perms                = "0400"
        uid                  = 1000
        gid                  = 0
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
