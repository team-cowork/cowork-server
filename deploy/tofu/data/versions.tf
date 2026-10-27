terraform {
  required_version = "= 1.13.2"
  required_providers {
    nomad = { source = "hashicorp/nomad", version = "= 2.6.1" }
  }
  backend "consul" {
    address = "10.0.0.111:8501"
    scheme  = "https"
    path    = "cowork/tofu/data"
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
provider "nomad" {
  address = var.nomad_address
  ca_file = var.platform_ca_file
}
