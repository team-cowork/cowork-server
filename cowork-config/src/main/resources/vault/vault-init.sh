#!/bin/sh
# Vault 개발 서버 초기화 스크립트
# 사용: docker compose up vault 후 실행
# VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-root-token sh vault-init.sh

export VAULT_ADDR=${VAULT_ADDR:-http://localhost:8200}
export VAULT_TOKEN=${VAULT_TOKEN:-dev-root-token}

echo "=== Vault KV v2 활성화 ==="
vault secrets enable -version=2 -path=secret kv 2>/dev/null || echo "이미 활성화됨"

echo ""
echo "=== 공통 시크릿 (application) ==="
vault kv put secret/application \
  jwt.secret="local-dev-jwt-secret-must-be-at-least-256bits-for-hs256-algorithm"

echo ""
echo "=== cowork-gateway 시크릿 ==="
# gateway는 공통 application 경로에서 jwt.secret을 가져오므로 별도 불필요
# 추가 필요 시:
# vault kv put secret/cowork-gateway \
#   some.other.secret="value"

echo ""
echo "=== cowork-user 시크릿 ==="
vault kv put secret/cowork-user \
  spring.datasource.password="user-db-password" \
  spring.datasource.url="jdbc:postgresql://localhost:5432/cowork_user"

echo ""
echo "=== cowork-authorization 시크릿 ==="
vault kv put secret/cowork-authorization \
  spring.datasource.password="auth-db-password" \
  spring.datasource.url="jdbc:postgresql://localhost:5432/cowork_auth"

echo ""
echo "=== cowork-notification 시크릿 ==="
vault kv put secret/cowork-notification \
  db.dsn="cowork:notification-db-password@tcp(cowork-mysql:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local"

echo ""
echo "=== 완료. 확인: ==="
vault kv list secret/
