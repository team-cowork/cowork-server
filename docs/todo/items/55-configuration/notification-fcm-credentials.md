# notification FCM 자격 증명 Vault 등록

- **서비스**: cowork-notification, Vault
- **우선순위**: 🔴 높음
- **현재 상태**: Vault `cowork-notification/prod`에 `fcm.credentials-json`이 없어 notification이 기동하지 못하고 중지되어 있다
- **관련 작업**: [운영 서비스의 CD 배포 일원화](../52-deployment/prod-cd-unification.md)

## 문제

현재 notification은 Firebase 서비스 계정 JSON 전체를 Config Server 속성 `fcm.credentials-json` 문자열로 받는다(`cowork-notification/internal/config/config.go`). 값이 비어 있으면 기동하지 않는다. `fcm.NewSender`는 `type`, `project_id`, `client_email`, `private_key`가 모두 있어야 성공한다(`cowork-notification/internal/infra/fcm/sender.go`).

2026-10-02 `http://<cowork-db>:8761/cowork-notification/prod` 응답에는 `cowork-notification-prod.yml`의 기본값 `""`만 있었다. Vault 값은 없었다. notification VM에서 실행 중이던 `cowork-notification:local` 이미지는 과거 코드로 빌드되어 `/run/secrets/firebase-credentials.json` 파일을 요구했다. 이 파일도 VM에 없었다. 따라서 수동 이미지와 현재 코드 이미지 모두 기동할 수 없다.

배포 문서 일괄 교체(`vault-settings.py update`)는 CAS로 문서 전체를 바꾸므로 기존 `db.dsn` 등을 지운다. 키 하나만 추가하려면 병합 방식이 필요하다.

## 할 일

- cowork-db의 `cowork-vault-prod`에서 쓰기 권한 토큰으로 `vault kv patch -mount=secret cowork-notification/prod fcm.credentials-json=-`를 실행해 기존 키를 보존한 채 값을 추가한다.
- 작업에 사용한 키 파일을 VM에서 `shred -u`로 삭제한다.
- notification VM의 notification을 `ghcr.io/team-cowork/cowork-notification:sha-<SHA>` 이미지로 교체한다.
- 등록에 사용한 Firebase 프로젝트가 운영 앱의 프로젝트와 같은지 확인한다.

## 검증

- `http://<cowork-db>:8761/cowork-notification/prod` 응답에 `fcm.credentials-json`이 Vault 출처로 포함된다.
- notification 로그에 FCM 초기화 오류가 없고 `/health/ready`와 Eureka 등록이 확인된다.

## 완료 조건

- Vault `cowork-notification/prod`에 유효한 `fcm.credentials-json`이 저장되어 있다.
- 운영 VM과 저장소 어디에도 Firebase 키 파일이 평문으로 남아 있지 않다.
