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
  type    = map(string)
  default = {}
}

job "cowork-livekit" {
  datacenters = ["gsm"]
  type        = "service"

  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "livekit"
  }

  group "livekit" {
    count = 1

    network {
      mode = "cni/cowork"
      port "http" {
        static       = 7880
        to           = 7880
        host_network = "private"
      }
      port "rtc_tcp" {
        static       = 7881
        to           = 38970
        host_network = "private"
      }
      port "rtc_udp" {
        static       = 7882
        to           = 35268
        host_network = "private"
      }
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
      name         = "cowork-livekit"
      provider     = "consul"
      port         = "7880"
      address_mode = "alloc"
      tags         = ["cowork", "platform"]

      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }

      check {
        name     = "signalling"
        type     = "http"
        path     = "/"
        port     = "http"
        interval = "10s"
        timeout  = "3s"
      }

      connect {
        sidecar_service {
          proxy {
            upstreams {
              destination_name   = "cowork-voice"
              local_bind_address = "127.0.0.1"
              local_bind_port    = 18089
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

    task "livekit" {
      driver       = "docker"
      kill_timeout = "60s"
      env          = var.runtime_env

      config {
        image = "livekit/livekit-server:v1.13.7@sha256:6fd3b7088874c4d119160dd688798dfec852bc014786d392caad15f6f63912a3"
        args  = ["--config", "/secrets/livekit.json"]
        ports = ["http", "rtc_tcp", "rtc_udp"]
      }

      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }

      vault {
        role         = "cowork-livekit"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }

      template {
        data                 = <<-JSON
{{ with secret "${var.vault_mount}/data/nomad/data/voice?version=${var.secret_version}" }}
{
  "port": 7880,
  "rtc": {"node_ip": "158.247.251.109", "use_external_ip": false, "tcp_port": 38970, "udp_port": 35268},
  "turn": {"enabled": false},
  "keys": { {{ .Data.data.LIVEKIT_API_KEY | toJSON }}: {{ .Data.data.LIVEKIT_API_SECRET | toJSON }} },
  "webhook": {"api_key": {{ .Data.data.LIVEKIT_API_KEY | toJSON }}, "urls": ["http://127.0.0.1:18089/voice/webhook"]},
  "logging": {"level": "info"}
}
{{ end }}
JSON
        destination          = "secrets/livekit.json"
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }

      resources {
        cpu        = 500
        memory     = 512
        memory_max = 1024
      }

      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
  }
}
