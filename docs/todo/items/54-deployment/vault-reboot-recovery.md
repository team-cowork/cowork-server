# VM 재부팅 후 Vault unseal 복구 절차

- **서비스**: Vault, cowork-config, GitHub Actions, 모든 애플리케이션 서비스
- **우선순위**: 🔴 높음
- **현재 상태**: 재부팅마다 Vault가 sealed 상태가 되어 전 서비스가 기동하지 못하며, `vault_recovery`는 unseal에 성공해도 실패로 표시된다
- **관련 작업**: [배포 Vault 인증 자동화](../42-deployment/vault-auth-automation.md)

> **2026-10-08 진척:** `deploy/prod/services/vault.sh`에서 unseal 뒤의 `docker compose up -d --wait`를 제거하고, 컨테이너 health가 `healthy`로 갱신될 때까지 최대 120초 기다린 뒤 `vault status`로 unseal 상태를 확인하도록 바꿨다. 둘 중 하나라도 정상이 아니면 실패로 끝난다. `docs/deployment.md`의 `실패 복구`에 재부팅 후 복구 순서(Vault unseal → Config `200` 확인 → 앱 재기동·Eureka 등록 확인)를 추가했다. 검증은 `bash -n` 구문 검사뿐이며, sealed 상태에서의 `vault_recovery` 실행 결과와 unseal 후 Config `200` 응답은 아직 운영에서 확인하지 않았다. auto-unseal 도입 여부는 결정하지 않았다.

## 문제

운영 Vault `cowork-vault-prod`는 cowork-db에서 file storage, Shamir 임계값 1로 동작한다. VM이 재부팅되면 sealed 상태로 시작하고, Config Server는 Vault 경로를 읽지 못해 모든 `/{application}/{profile}` 요청에 500을 반환한다. Spring·Go·Elixir 서비스가 모두 기동 시 Config를 필수로 읽으므로, 2026-10-02 재부팅 직후 Vault 하나 때문에 전체 서비스가 재시작을 반복했다.

복구는 `cowork-prod-cd.yml`의 `vault_recovery` 입력으로 `deploy/prod/services/vault.sh`를 실행해 이루어졌다(run 36949190585). 이 스크립트는 `vault operator unseal` 후 `docker compose up -d --wait --wait-timeout 120`을 실행한다. 이때 unseal 이전의 `unhealthy` health 상태가 남아 있어 `--wait`가 즉시 실패한다. 그래서 `sys/health`가 200이고 `sealed:false`인데도 워크플로는 실패로 끝났다.

운영 VM의 재부팅은 사전 공지 없이 일어날 수 있다. unseal 절차는 문서화되어 있지 않고, 운영 secret을 쓰는 실행이라 운영자가 직접 해야 한다.

## 할 일

- `vault.sh`에서 unseal 후 컨테이너 health가 갱신될 때까지 `vault status`나 `sys/health`를 기준으로 기다리도록 바꾼다.
- 재부팅 후 복구 순서(Vault unseal → Config 확인 → 서비스 재시작 확인)를 `docs/deployment.md`에 추가한다.
- 재부팅 시 unseal을 자동화할지(auto-unseal 등) 수동 절차로 유지할지 결정한다.

## 검증

- sealed 상태에서 `vault_recovery`를 실행했을 때 워크플로가 성공으로 끝나는지 확인한다.
- unseal 후 `curl http://<cowork-db>:8761/cowork-user/prod`가 200을 반환하는지 확인한다.

## 완료 조건

- `vault_recovery` 실행 결과가 실제 unseal 상태와 일치한다.
- 재부팅 후 복구 절차가 저장소 문서에 있고, 운영자가 그 문서만으로 복구할 수 있다.
