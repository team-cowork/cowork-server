storage "file" {
  path = "/vault/data"
}

# GSM SV keeps terminating external HTTPS in front of this listener.
listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

listener "tcp" {
  address         = "0.0.0.0:8210"
  tls_cert_file   = "/vault/tls/server.crt"
  tls_key_file    = "/vault/tls/server.key"
  tls_min_version = "tls12"
}

disable_mlock = true
ui            = true
