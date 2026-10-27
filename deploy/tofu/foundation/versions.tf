terraform {
  required_version = "= 1.13.2"
  required_providers {
    consul = { source = "hashicorp/consul", version = "= 2.23.0" }
    vault  = { source = "hashicorp/vault", version = "= 5.13.0" }
  }
  backend "consul" {
    address = "10.0.0.111:8501"
    scheme  = "https"
    path    = "cowork/tofu/foundation"
    gzip    = true
    lock    = true
  }
  encryption {
    key_provider "pbkdf2" "platform" {
      passphrase = var.state_passphrase
    }
    method "aes_gcm" "platform" {
      keys = key_provider.pbkdf2.platform
    }
    state {
      method   = method.aes_gcm.platform
      enforced = true
    }
    plan {
      method   = method.aes_gcm.platform
      enforced = true
    }
  }
}
provider "consul" {
  address = var.consul_address
  scheme  = "https"
  ca_file = var.platform_ca_file
}
provider "vault" {
  address          = var.vault_address
  ca_cert_file     = var.platform_ca_file
  skip_child_token = true
}
