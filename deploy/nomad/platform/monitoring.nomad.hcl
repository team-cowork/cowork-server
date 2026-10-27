variable "envoy_image" {
  type = string
}
variable "vault_mount" {
  type    = string
  default = "secret"
}
variable "secret_version" {
  type = number
}
variable "runtime_env" {
  type = map(string)
}
variable "config_files" {
  type = map(string)
}
job "cowork-monitoring" {
  datacenters = ["gsm"]
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "monitoring"
  }
  group "monitoring" {
    count = 1
    network {
      mode = "cni/cowork"
      port "grafana" {
        static       = 8080
        to           = 8080
        host_network = "private"
      }
      port "loki" {
        static       = 3100
        to           = 3100
        host_network = "private"
      }
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
    volume "monitoring-ingress-tls" {
      type      = "host"
      source    = "monitoring-ingress-tls"
      read_only = true
    }
    volume "cowork-ca" {
      type      = "host"
      source    = "cowork-ca"
      read_only = true
    }
    volume "loki" {
      type      = "host"
      source    = "monitoring-loki"
      read_only = false
    }
    volume "prometheus" {
      type      = "host"
      source    = "monitoring-prometheus"
      read_only = false
    }
    volume "grafana" {
      type      = "host"
      source    = "monitoring-grafana"
      read_only = false
    }
    volume "alertmanager" {
      type      = "host"
      source    = "monitoring-alertmanager"
      read_only = false
    }

    service {
      name         = "cowork-monitoring"
      provider     = "consul"
      port         = "8080"
      address_mode = "alloc"
      tags         = ["cowork", "platform"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "grafana"
        type     = "script"
        task     = "grafana-proxy"
        command  = "wget"
        args     = ["-q", "--spider", "http://127.0.0.1:8080/api/health"]
        interval = "10s"
        timeout  = "5s"
      }
      check {
        name     = "loki"
        type     = "script"
        task     = "grafana-proxy"
        command  = "wget"
        args     = ["-q", "--spider", "http://127.0.0.1:3101/ready"]
        interval = "10s"
        timeout  = "5s"
      }
      check {
        name     = "prometheus"
        type     = "script"
        task     = "prometheus"
        command  = "wget"
        args     = ["-q", "--spider", "http://127.0.0.1:9090/-/ready"]
        interval = "10s"
        timeout  = "5s"
      }
      connect {
        sidecar_service {
          proxy {
            upstreams {
              destination_name   = "cowork-redis"
              local_bind_address = "127.0.0.1"
              local_bind_port    = 16379
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
    task "loki" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image    = "grafana/loki:3.7.7@sha256:d70e4659623f3e109af669cae76fe2a5dd5be54e2298fe8aed380d982fbc2500"
        args     = ["-config.file=/local/config/loki/loki-config.yml"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "loki"
        destination = "/loki"
      }

      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if regex_replace(path, "^loki/", "") != path }
        content {
          data = replace(replace(replace(replace(template.value,
            "COWORK_PUBLIC_API_ORIGIN", trimsuffix(var.runtime_env["PUBLIC_API_BASE_URL"], "/")),
            "COWORK_WEB_ORIGIN", trimspace(split(",", var.runtime_env["PUBLIC_WEB_ORIGINS"])[0])),
            "COWORK_CONSUL_SERVER", trimprefix(lookup(var.runtime_env, "CONSUL_HTTP_ADDR", "https://10.0.0.137:8501"), "https://")),
          "COWORK_VAULT_ADDR", trimsuffix(lookup(var.runtime_env, "VAULT_ADDR", "https://10.0.0.91:8210"), "/"))
          destination     = "local/config/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }
      resources {
        cpu        = 300
        memory     = 512
        memory_max = 1024
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "prometheus" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image    = "prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0"
        args     = ["--config.file=/local/config/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=15d", "--web.listen-address=127.0.0.1:9090"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "prometheus"
        destination = "/prometheus"
      }
      volume_mount {
        volume      = "cowork-ca"
        destination = "/etc/cowork/ca"
        read_only   = true
      }

      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if regex_replace(path, "^prometheus/", "") != path }
        content {
          data = replace(replace(replace(replace(template.value,
            "COWORK_PUBLIC_API_ORIGIN", trimsuffix(var.runtime_env["PUBLIC_API_BASE_URL"], "/")),
            "COWORK_WEB_ORIGIN", trimspace(split(",", var.runtime_env["PUBLIC_WEB_ORIGINS"])[0])),
            "COWORK_CONSUL_SERVER", trimprefix(lookup(var.runtime_env, "CONSUL_HTTP_ADDR", "https://10.0.0.137:8501"), "https://")),
          "COWORK_VAULT_ADDR", trimsuffix(lookup(var.runtime_env, "VAULT_ADDR", "https://10.0.0.91:8210"), "/"))
          destination     = "local/config/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-monitoring"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-SECRET
{{ with secret "${var.vault_mount}/data/nomad/apps/monitoring?version=${var.secret_version}" }}{{ .Data.data.CONSUL_HTTP_TOKEN }}{{ end }}
SECRET
        destination          = "secrets/consul-token"
        perms                = "0400"
        uid                  = 10001
        gid                  = 10001
        env                  = false
        change_mode          = "restart"
        error_on_missing_key = true
      }
      resources {
        cpu        = 300
        memory     = 512
        memory_max = 1024
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "alertmanager" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image    = "prom/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d"
        args     = ["--config.file=/local/config/alertmanager/alertmanager.yml", "--storage.path=/alertmanager", "--web.listen-address=127.0.0.1:9093", "--cluster.listen-address="]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "alertmanager"
        destination = "/alertmanager"
      }

      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if regex_replace(path, "^alertmanager/", "") != path }
        content {
          data = replace(replace(replace(replace(template.value,
            "COWORK_PUBLIC_API_ORIGIN", trimsuffix(var.runtime_env["PUBLIC_API_BASE_URL"], "/")),
            "COWORK_WEB_ORIGIN", trimspace(split(",", var.runtime_env["PUBLIC_WEB_ORIGINS"])[0])),
            "COWORK_CONSUL_SERVER", trimprefix(lookup(var.runtime_env, "CONSUL_HTTP_ADDR", "https://10.0.0.137:8501"), "https://")),
          "COWORK_VAULT_ADDR", trimsuffix(lookup(var.runtime_env, "VAULT_ADDR", "https://10.0.0.91:8210"), "/"))
          destination     = "local/config/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-monitoring"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-SECRET
{{ with secret "${var.vault_mount}/data/nomad/apps/monitoring?version=${var.secret_version}" }}{{ .Data.data.DISCORD_MONITORING_ALERT_WEBHOOK }}{{ end }}
SECRET
        destination          = "secrets/discord-webhook-url"
        perms                = "0400"
        uid                  = 10001
        gid                  = 10001
        env                  = false
        change_mode          = "restart"
        error_on_missing_key = true
      }
      resources {
        cpu        = 100
        memory     = 128
        memory_max = 256
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "grafana" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      env = {
        GF_SECURITY_ADMIN_PASSWORD__FILE = "/secrets/admin-password"
        GF_SERVER_ROOT_URL               = var.runtime_env["GRAFANA_ROOT_URL"]
        GF_SERVER_HTTP_ADDR              = "127.0.0.1"
        GF_SERVER_HTTP_PORT              = "3000"
        GF_SECURITY_COOKIE_SECURE        = "true"
        GF_AUTH_ANONYMOUS_ENABLED        = "false"
        GF_ANALYTICS_REPORTING_ENABLED   = "false"
        GF_USERS_ALLOW_SIGN_UP           = "false"
        GF_PATHS_PROVISIONING            = "/local/config/grafana/provisioning"
      }
      config {
        image    = "grafana/grafana:13.2.2@sha256:ac461fb352abc50da10a51c7d02462e9c05488f11f53f14b3ad79a8145f638a0"
        args     = []
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "grafana"
        destination = "/var/lib/grafana"
      }

      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if regex_replace(path, "^grafana/", "") != path }
        content {
          data = replace(replace(replace(replace(template.value,
            "COWORK_PUBLIC_API_ORIGIN", trimsuffix(var.runtime_env["PUBLIC_API_BASE_URL"], "/")),
            "COWORK_WEB_ORIGIN", trimspace(split(",", var.runtime_env["PUBLIC_WEB_ORIGINS"])[0])),
            "COWORK_CONSUL_SERVER", trimprefix(lookup(var.runtime_env, "CONSUL_HTTP_ADDR", "https://10.0.0.137:8501"), "https://")),
          "COWORK_VAULT_ADDR", trimsuffix(lookup(var.runtime_env, "VAULT_ADDR", "https://10.0.0.91:8210"), "/"))
          destination     = "local/config/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-monitoring"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-SECRET
{{ with secret "${var.vault_mount}/data/nomad/apps/monitoring?version=${var.secret_version}" }}{{ .Data.data.GRAFANA_ADMIN_PASSWORD }}{{ end }}
SECRET
        destination          = "secrets/admin-password"
        perms                = "0400"
        uid                  = 10001
        gid                  = 10001
        env                  = false
        change_mode          = "restart"
        error_on_missing_key = true
      }
      resources {
        cpu        = 200
        memory     = 256
        memory_max = 512
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "grafana-proxy" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image      = "nginxinc/nginx-unprivileged:1.30.5-alpine@sha256:56b8272e43a0525f67aed68d1e0794a712d10e3e45a563e118e9a77c817e6f86"
        args       = ["-c", "/local/config/grafana/proxy.conf", "-g", "daemon off;"]
        cap_drop   = ["ALL"]
        ports      = ["grafana", "loki"]
        entrypoint = ["nginx"]
      }

      template {
        data            = var.config_files["grafana/proxy.conf"]
        destination     = "local/config/grafana/proxy.conf"
        perms           = "0444"
        left_delimiter  = "[[["
        right_delimiter = "]]]"
        change_mode     = "restart"
      }
      volume_mount {
        volume      = "monitoring-ingress-tls"
        destination = "/etc/cowork/ingress-tls"
        read_only   = true
      }
      volume_mount {
        volume      = "cowork-ca"
        destination = "/etc/cowork/ca"
        read_only   = true
      }
      resources {
        cpu        = 100
        memory     = 64
        memory_max = 128
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "blackbox" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image    = "prom/blackbox-exporter:v0.28.0@sha256:e753ff9f3fc458d02cca5eddab5a77e1c175eee484a8925ac7d524f04366c2fc"
        args     = ["--config.file=/local/config/blackbox/blackbox.yml", "--web.listen-address=127.0.0.1:9115"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "cowork-ca"
        destination = "/etc/cowork/ca"
        read_only   = true
      }

      dynamic "template" {
        for_each = { for path, body in var.config_files : path => body if regex_replace(path, "^blackbox/", "") != path }
        content {
          data = replace(replace(replace(replace(template.value,
            "COWORK_PUBLIC_API_ORIGIN", trimsuffix(var.runtime_env["PUBLIC_API_BASE_URL"], "/")),
            "COWORK_WEB_ORIGIN", trimspace(split(",", var.runtime_env["PUBLIC_WEB_ORIGINS"])[0])),
            "COWORK_CONSUL_SERVER", trimprefix(lookup(var.runtime_env, "CONSUL_HTTP_ADDR", "https://10.0.0.137:8501"), "https://")),
          "COWORK_VAULT_ADDR", trimsuffix(lookup(var.runtime_env, "VAULT_ADDR", "https://10.0.0.91:8210"), "/"))
          destination     = "local/config/${template.key}"
          perms           = "0444"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          change_mode     = "restart"
        }
      }
      resources {
        cpu        = 100
        memory     = 64
        memory_max = 128
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "redis-exporter" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      env          = { REDIS_ADDR = "redis://127.0.0.1:16379" }
      config {
        image    = "oliver006/redis_exporter:v1.91.1@sha256:c67a432dba6b4ae30f471e3c77cf14a289133bbeeb89abb0bb03e6092efb2836"
        args     = ["--web.listen-address=127.0.0.1:9121"]
        cap_drop = ["ALL"]
      }
      resources {
        cpu        = 100
        memory     = 64
        memory_max = 128
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "kafka-exporter" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "30s"
      config {
        image    = "danielqsj/kafka-exporter:v1.10.0@sha256:346a53c2489a3530790d01f7b96632496d0d58926662f4c35bb65f569db2ab42"
        args     = ["--kafka.server=${lookup(var.runtime_env, "KAFKA_BOOTSTRAP_SERVERS", "10.0.0.167:9094")}", "--tls.enabled", "--tls.ca-file=/etc/cowork/ca/ca.crt", "--sasl.enabled", "--sasl.mechanism=scram-sha512", "--sasl.username=cowork-monitoring", "--web.listen-address=127.0.0.1:9308"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "cowork-ca"
        destination = "/etc/cowork/ca"
        read_only   = true
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-monitoring"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-SECRET
{{ with secret "${var.vault_mount}/data/nomad/apps/monitoring?version=${var.secret_version}" }}SASL_USER_PASSWORD={{ .Data.data.KAFKA_SASL_PASSWORD | toJSON }}{{ end }}
SECRET
        destination          = "secrets/kafka.env"
        perms                = "0400"
        uid                  = 10001
        gid                  = 10001
        env                  = true
        change_mode          = "restart"
        error_on_missing_key = true
      }
      resources {
        cpu        = 100
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
