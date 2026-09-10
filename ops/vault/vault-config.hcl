storage "file" {
  path = "/vault/data"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

# TLS는 이 설정이 아니라 VM 패널의 HTTPS 도메인 기능(와일드카드 인증서 자동 발급 +
# 포트포워딩)이 앞단에서 종단한다. api_addr/cluster_addr는 배포 시점에만 정해지는
# 외부 도메인이 필요해서 여기 하드코딩하지 않고, ops/vault/docker-compose.yml에서
# VAULT_API_ADDR/VAULT_CLUSTER_ADDR 환경변수로 주입한다(Vault가 이 두 값을
# 설정 파일 값보다 우선해서 읽는다).

disable_mlock = true
ui            = true
