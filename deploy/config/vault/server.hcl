storage "file" {
  path = "/vault/data"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

# TLS terminates at the external HTTPS reverse proxy. Restrict the listener's
# host binding/firewall to that proxy. VAULT_API_ADDR is set by the Vault stack.
# File storage is persistent but single-node; backups and unseal custody are required.
disable_mlock = true
ui            = true
