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

job "cowork-chat-storage" {
  datacenters = ["gsm"]
  type        = "service"
  constraint {
    attribute = "${meta.cowork_vm}"
    value     = "chat"
  }
  group "storage" {
    count = 1
    meta {
      initialization = sha256(jsonencode({ version = var.secret_version, files = var.config_files, settings = var.runtime_env }))
    }
    network {
      mode = "cni/cowork"
    }
    volume "storage" {
      type      = "host"
      source    = "chat-storage"
      read_only = false
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
      name         = "cowork-chat-storage"
      provider     = "consul"
      port         = "9000"
      address_mode = "alloc"
      tags         = ["cowork", "storage"]
      identity {
        aud = ["consul.io"]
        ttl = "1h"
      }
      check {
        name     = "bucket-ready"
        type     = "script"
        task     = "server"
        command  = "/bin/sh"
        args     = ["-ec", "test -f /alloc/data/storage-ready && wget -qO- http://127.0.0.1:9000/readyz >/dev/null"]
        interval = "10s"
        timeout  = "3s"
      }
      check {
        name     = "lifecycle-ready"
        type     = "script"
        task     = "lifecycle"
        command  = "wget"
        args     = ["-qO-", "http://127.0.0.1:9327/ready"]
        interval = "30s"
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
    task "server" {
      driver       = "docker"
      user         = "10001:10001"
      kill_timeout = "60s"
      config {
        image    = "chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882"
        args     = ["server", "-dir=/storage/data", "-ip=127.0.0.1", "-ip.bind=127.0.0.1", "-master.port=9333", "-master.telemetry=false", "-master.volumeSizeLimitMB=1024", "-volume.port=8080", "-volume.max=8", "-volume.minFreeSpace=2GiB", "-filer.port=8888", "-s3", "-s3.port=9000", "-s3.ip.bind=127.0.0.1", "-s3.port.iceberg=0", "-s3.port.lance=0", "-s3.config=/secrets/s3-config.json", "-s3.iam=false", "-s3.autoCreateBucket=false", "-s3.allowedOrigins=${var.runtime_env["PUBLIC_WEB_ORIGINS"]}"]
        cap_drop = ["ALL"]
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-chat-storage"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-JSON
{{ with secret "${var.vault_mount}/data/nomad/data/chat?version=${var.secret_version}" }}
{"identities":[
  {"name":"cowork-admin","credentials":[{"accessKey":{{ .Data.data.S3_ADMIN_ACCESS_KEY | toJSON }},"secretKey":{{ .Data.data.S3_ADMIN_SECRET_KEY | toJSON }}}],"actions":["Admin"]},
  {"name":"cowork-chat","credentials":[{"accessKey":{{ .Data.data.S3_ACCESS_KEY | toJSON }},"secretKey":{{ .Data.data.S3_SECRET_KEY | toJSON }}}],"actions":${jsonencode(concat([for prefix in ["chat-files", "chat-uploads"] : "Read:${var.runtime_env["S3_BUCKET"]}/${prefix}/*"], [for prefix in ["chat-files", "chat-uploads"] : "Write:${var.runtime_env["S3_BUCKET"]}/${prefix}/*"]))}}
]}
{{ end }}
JSON
        destination          = "secrets/s3-config.json"
        perms                = "0400"
        uid                  = 10001
        gid                  = 10001
        change_mode          = "restart"
        error_on_missing_key = true
      }
      volume_mount {
        volume      = "storage"
        destination = "/storage"
      }
      resources {
        cpu        = 500
        memory     = 512
        memory_max = 768
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "bucket-init" {
      driver = "docker"
      user   = "10001:10001"
      lifecycle {
        hook    = "poststart"
        sidecar = false
      }
      env = merge(var.runtime_env, {
        AWS_DEFAULT_REGION        = lookup(var.runtime_env, "S3_REGION", "us-east-1")
        AWS_EC2_METADATA_DISABLED = "true"
        AWS_PAGER                 = ""
        HOME                      = "/tmp"
        APP_CONFIG_PROFILE        = "prod"
        S3_INTERNAL_ENDPOINT      = "http://127.0.0.1:9000"
        S3_BUCKET_POLICY_FILE     = "/local/storage/bucket-policy.json"
        S3_BUCKET_LIFECYCLE_FILE  = "/local/storage/bucket-lifecycle.json"
      })
      config {
        image      = "amazon/aws-cli:2.36.46@sha256:eedfdcb56e9a1b02fabcf656977ca226f40860c6672aca41d435e036bc95a075"
        entrypoint = ["/bin/sh", "-ec"]
        args       = ["i=0; until aws --endpoint-url http://127.0.0.1:9000 s3api list-buckets >/dev/null 2>&1; do i=$((i+1)); [ $i -lt 90 ] || exit 1; sleep 2; done; sh /local/storage/configure-bucket.sh; touch /alloc/data/storage-ready"]
        cap_drop   = ["ALL"]
      }
      identity {
        name = "vault_default"
        aud  = ["vault.io"]
        ttl  = "1h"
      }
      vault {
        role         = "cowork-chat-storage"
        env          = false
        disable_file = true
        change_mode  = "noop"
      }
      template {
        data                 = <<-ENV
{{ with secret "${var.vault_mount}/data/nomad/data/chat?version=${var.secret_version}" }}
AWS_ACCESS_KEY_ID={{ .Data.data.S3_ADMIN_ACCESS_KEY | toJSON }}
AWS_SECRET_ACCESS_KEY={{ .Data.data.S3_ADMIN_SECRET_KEY | toJSON }}
{{ end }}
ENV
        destination          = "secrets/admin.env"
        env                  = true
        perms                = "0600"
        change_mode          = "restart"
        error_on_missing_key = true
      }
      dynamic "template" {
        for_each = var.config_files
        content {
          data            = template.value
          destination     = "local/storage/${template.key}"
          left_delimiter  = "[[["
          right_delimiter = "]]]"
          perms           = "0444"
          change_mode     = "restart"
        }
      }
      template {
        data = jsonencode({ Version = "2012-10-17", Statement = concat([
          { Sid = "DenyServiceBucketAdministration", Effect = "Deny", Principal = { AWS = ["arn:aws:iam::000000000000:user/cowork-chat"] }, Action = ["s3:PutBucketAcl", "s3:PutObjectAcl", "s3:PutBucketPolicy", "s3:DeleteBucketPolicy"], Resource = ["arn:aws:s3:::${var.runtime_env["S3_BUCKET"]}", "arn:aws:s3:::${var.runtime_env["S3_BUCKET"]}/*"] }
        ], []) })
        destination = "local/storage/bucket-policy.json"
        perms       = "0444"
      }
      template {
        data        = jsonencode({ Rules = [{ ID = "expire-temporary-uploads", Status = "Enabled", Filter = { Prefix = "chat-uploads/" }, Expiration = { Days = 1 } }] })
        destination = "local/storage/bucket-lifecycle.json"
        perms       = "0444"
      }
      resources {
        cpu    = 100
        memory = 128
      }
      logs {
        max_files     = 3
        max_file_size = 10
      }
    }
    task "admin" {
      driver = "docker"
      user   = "10001:10001"
      config {
        image    = "chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882"
        args     = ["admin", "-master=127.0.0.1:9333", "-ip=127.0.0.1", "-dataDir=/storage/admin"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "storage"
        destination = "/storage"
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
    task "lifecycle" {
      driver = "docker"
      user   = "10001:10001"
      config {
        image    = "chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882"
        args     = ["worker", "-admin=127.0.0.1:23646", "-jobType=s3_lifecycle", "-workingDir=/storage/lifecycle", "-metricsPort=9327", "-metricsIp=127.0.0.1"]
        cap_drop = ["ALL"]
      }
      volume_mount {
        volume      = "storage"
        destination = "/storage"
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
  }
}
