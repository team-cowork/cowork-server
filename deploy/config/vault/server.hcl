storage "file" {
  path = "/vault/data"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

# Private clients verify this listener against tls-ca.crt. The host's
# cowork-vault-tls timer renews the certificate and reloads it with SIGHUP.
listener "tcp" {
  address         = "0.0.0.0:8210"
  tls_cert_file   = "/vault/tls/server.crt"
  tls_key_file    = "/vault/tls/server.key"
  tls_min_version = "tls12"
}

# TLS for the public endpoint terminates at the external HTTPS reverse proxy.
# deploy/prod/vault/firewall.json admits only that proxy, Config and the
# monitoring probe. VAULT_API_ADDR is set by the Vault stack.
# File storage is persistent but single-node; backups and unseal custody are required.
disable_mlock = true
ui            = true
