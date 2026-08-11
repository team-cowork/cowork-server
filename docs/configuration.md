# 설정 및 시크릿 관리

이 문서는 `cowork-server` 서비스가 설정을 받는 단일 기준을 정의한다.

## 공급 원칙

| 종류                     | 공급원                                | 예시                                                    |
|--------------------------|---------------------------------------|---------------------------------------------------------|
| 부트스트랩               | Docker Compose 또는 실행 환경         | 활성 프로파일, Config Server/Vault 주소, host 공개 포트 |
| 인프라 부트스트랩 시크릿 | 배포 secret store → Compose           | DB/Vault/Grafana 관리자 계정, LiveKit server key        |
| 일반 설정                | Config Server native/Git              | 서비스 포트, 내부 URL, Kafka topic, timeout, 기능 정책  |
| 애플리케이션 시크릿      | Vault                                 | DB 계정, JWT/세션 서명 키, OAuth secret, API key        |
| 파일형 시크릿            | Docker secret 또는 배포 secret volume | Firebase 서비스 계정 JSON                               |

애플리케이션 설정 우선순위는 다음과 같다.

```text
직접 환경변수 > Vault 서비스 경로 > Vault 공통 경로 > native/Git > 애플리케이션 기본값
```

직접 환경변수 override는 로컬 단독 실행과 긴급 운영 override 용도다. Compose 애플리케이션 서비스에는 Config Server 접속값과 프로파일만 기본 주입한다.

## 프로파일

| 프로파일 | Config Server backend                    |
|----------|------------------------------------------|
| `local`  | Vault + classpath `configs/*-local.yml`  |
| `dev`    | Vault + classpath `configs/*-dev.yml`    |
| `prod`   | 외부 Vault + `CONFIG_GIT_URI` Git 저장소 |

`local`과 `dev`에는 Gateway와 모든 backend business service의 설정 파일이 존재해야 한다. `prod` 설정은 배포 전 외부 Config Git과 Vault에 동일한 application 이름으로 등록한다.

## Vault 경로

| 경로                          | 주요 값                                               |
|-------------------------------|-------------------------------------------------------|
| `secret/application`          | 공통 DB 계정, JWT, SeaweedFS credential                |
| `secret/cowork-gateway`       | `jwt.secret`                                          |
| `secret/cowork-authorization` | DB DSN, DataGSM ID/webhook key, JWT                   |
| `secret/cowork-channel`       | credential 암호화 키, OAuth state/provider credential |
| `secret/cowork-chat`          | MongoDB URI, Discord webhook URL                      |
| `secret/cowork-notification`  | DB DSN                                                |
| `secret/cowork-preference`    | PostgreSQL username/password                          |
| `secret/cowork-project`       | GitHub App internal key                               |
| `secret/cowork-user`          | MySQL username/password, Phoenix `SECRET_KEY_BASE`    |
| `secret/cowork-voice`         | MongoDB URI, LiveKit key/secret                       |

로컬에서는 `vault-init`이 `.env`의 인프라 계정·애플리케이션 시크릿을 위 경로에 기록한다. `.env`는 로컬 Vault와 Config Server를 준비하는 bootstrap 입력이며, 애플리케이션 컨테이너는 이 파일을 직접 설정 소스로 사용하지 않는다. 운영에서는 `vault-init`을 실행하지 않고 외부 Vault를 사전에 준비한다.

Config Server나 Vault client가 아닌 MySQL, PostgreSQL, MongoDB, LiveKit, Grafana, Alertmanager 같은 인프라·서드파티 컨테이너는 배포 환경의 secret을 Compose로 직접 받는다. 같은 값이 애플리케이션에도 필요하면 로컬 `vault-init` 또는 운영 배포 절차가 Vault에 따로 기록한다.

## 서비스별 부트스트랩

| 런타임      | 방식                                                           | Config Server 실패 처리 |
|-------------|----------------------------------------------------------------|-------------------------|
| Spring Boot | `SPRING_CONFIG_IMPORT=configserver:...`                        | 기동 실패               |
| Go          | `APP_CONFIG_URL`, `APP_PROFILE` custom client                  | URL 지정 시 기동 실패   |
| NestJS      | bootstrap 전 Config Server 조회                                | 기동 실패               |
| Vert.x      | 배포 전 Config Server 조회                                     | 3회 실패 후 종료        |
| Elixir      | entrypoint가 DB/Flyway 설정 조회 후 앱 내부에서 일반 설정 조회 | 기동 실패               |

## 변경 절차

1. 일반 설정은 `cowork-config/src/main/resources/configs/cowork-{service}-{profile}.yml`에 추가한다.
2. 시크릿은 코드나 Config Git에 값을 넣지 않고 Vault key 이름만 정의한다.
3. 로컬 시크릿이면 `.env.example`, `vault-init` 환경 전달, `vault-init.sh` 저장 경로를 함께 갱신한다.
4. 파일형 credential은 read-only Docker secret 또는 배포 secret volume을 사용한다.
5. 모듈 README의 설정 공급 표를 함께 갱신한다.
6. `docker compose config --quiet`와 해당 모듈 테스트를 실행한다.

## 운영 체크

- `VAULT_HOST`, `VAULT_TOKEN`, `CONFIG_GIT_URI`는 Config Server 부트스트랩 값으로 배포 환경에서 주입한다.
- 운영 Config Git에는 시크릿 값을 커밋하지 않는다.
- 필수 시크릿이 없을 때 기본 개발 키로 대체하지 않는다.
- 분산 배포 모듈의 Config Server/Eureka 접근을 위해 현재 `8761` 포트를 모든 인터페이스에 공개한다. 인증과 네트워크 제한은 [Config Server 접근 보호 TODO](./todo/items/08-security/config-server-access-control.md)로 관리한다.
- Config Server와 Vault를 우회하는 서비스 직접 포트는 운영 외부망에 공개하지 않는다.
