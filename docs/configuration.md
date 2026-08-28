# 설정 및 시크릿 관리

이 문서는 `cowork-server` 서비스가 설정을 받는 단일 기준을 정의한다.

## 공급 원칙

| 종류                     | 공급원                                 | 예시                                                    |
|--------------------------|----------------------------------------|---------------------------------------------------------|
| 부트스트랩               | Docker Compose 또는 실행 환경          | 활성 프로파일, Config Server/Vault 주소, host 공개 포트 |
| 인프라 부트스트랩 시크릿 | 배포 secret store → Compose            | DB/Vault/Grafana 관리자 계정, LiveKit server key        |
| 일반 설정                | Config Server native (`configs/*.yml`) | 서비스 포트, 내부 URL, Kafka topic, timeout, 기능 정책  |
| 애플리케이션 시크릿      | Vault                                  | DB 계정, JWT/세션 서명 키, OAuth secret, API key        |
| 파일형 시크릿            | Docker secret 또는 배포 secret volume  | Firebase 서비스 계정 JSON                               |

애플리케이션 설정 우선순위는 다음과 같다.

```text
직접 환경변수 > Config Server overrides > Vault 서비스 경로 > Vault 공통 경로 > native `configs/` > 애플리케이션 기본값
```

직접 환경변수 override는 로컬 단독 실행과 긴급 운영 override 용도다. Compose 애플리케이션 서비스에는 Config Server 접속값, 프로파일, replica 식별처럼 런타임에서만 알 수 있는 값만 기본 주입한다.

## 프로파일

| 프로파일 | Config Server backend                       |
|----------|---------------------------------------------|
| `local`  | Vault + classpath `configs/*-local.yml`     |
| `prod`   | 외부 Vault + classpath `configs/*-prod.yml` |

프로파일은 `local`과 `prod` 둘뿐이다. Gateway와 모든 backend business service는 두 프로파일 파일을 모두 가져야 하며, Config Server는 정의되지 않은 프로파일에 대해 아무 설정도 내려주지 않는다. 시크릿은 배포 전 Vault에 동일한 application 이름으로 등록한다.

## Vault 경로

| 경로                          | 주요 값                                               |
|-------------------------------|-------------------------------------------------------|
| `secret/application`          | 공통 DB 계정, JWT, SeaweedFS credential               |
| `secret/cowork-gateway`       | `jwt.secret`                                          |
| `secret/cowork-authorization` | DB DSN, DataGSM ID/webhook key, JWT                   |
| `secret/cowork-channel`       | credential 암호화 키, OAuth state/provider credential |
| `secret/cowork-chat`          | MongoDB URI, Discord webhook URL                      |
| `secret/cowork-notification`  | DB DSN                                                |
| `secret/cowork-preference`    | PostgreSQL username/password                          |
| `secret/cowork-project`       | GitHub App internal key                               |
| `secret/cowork-team`          | GitHub App callback state 서명 키·app slug            |
| `secret/cowork-user`          | MySQL username/password                               |
| `secret/cowork-voice`         | MongoDB URI, LiveKit key/secret                       |

로컬에서는 `vault-init`이 `.env`의 인프라 계정·애플리케이션 시크릿을 위 경로에 기록한다. `.env`는 로컬 Vault와 Config Server를 준비하는 bootstrap 입력이며, 애플리케이션 컨테이너는 이 파일을 직접 설정 소스로 사용하지 않는다. 운영에서는 `vault-init`을 실행하지 않고 외부 Vault를 사전에 준비한다.

Config Server나 Vault client가 아닌 MySQL, PostgreSQL, MongoDB, LiveKit, Grafana, Alertmanager 같은 인프라·서드파티 컨테이너는 배포 환경의 secret을 Compose로 직접 받는다. 같은 값이 애플리케이션에도 필요하면 로컬 `vault-init` 또는 운영 배포 절차가 Vault에 따로 기록한다.

운영에서는 다음 이중 입력이 정확히 같은 credential을 가리켜야 한다. Compose와 Vault를 따로
갱신해 값이 어긋나면 컨테이너 health가 열려도 실제 애플리케이션 요청은 인증에 실패한다.

| Compose bootstrap 입력                       | 외부 Vault 대상                                                                                                                                                        | 일치 계약                                                                              |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `MYSQL_USER`, `MYSQL_PASSWORD`               | `secret/application`의 동명 key, `secret/cowork-authorization`의 `DB_DSN`, `secret/cowork-notification`의 `db.dsn`, `secret/cowork-user`의 `DB_USERNAME`·`DB_PASSWORD` | 같은 MySQL login을 사용하고 DSN은 각 서비스 DB 이름과 `mysql:3306`을 가리킨다.         |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`         | `secret/application`의 동명 key, `secret/cowork-preference`의 `preference.db.username`·`preference.db.password`                                                        | 같은 PostgreSQL login을 사용한다.                                                      |
| `MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD` | `secret/cowork-chat`·`secret/cowork-voice`의 `MONGODB_URI`                                                                                                             | 같은 root login을 URI에 넣고 서비스별 DB 이름과 `authSource=admin`을 사용한다.         |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`             | `secret/application`의 동명 key                                                                                                                                        | SeaweedFS server·bucket init·chat/team/user가 같은 key pair를 사용한다.                |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`      | `secret/cowork-voice`의 동명 key                                                                                                                                       | LiveKit server와 voice token 발급기가 같은 key pair를 사용한다.                        |
| JWT signing secret                           | `secret/application`의 `JWT_SECRET`, `secret/cowork-gateway`의 `jwt.secret`, `secret/cowork-authorization`의 `JWT_SECRET`                                              | authorization 서명, Gateway HTTP 검증, chat WebSocket 검증에 동일한 secret을 사용한다. |

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
2. 시크릿은 코드에 값을 넣지 않고 Vault key 이름만 정의한다.
3. 로컬 시크릿이면 `.env.example`, `vault-init` 환경 전달, `vault-init.sh` 저장 경로를 함께 갱신한다.
4. 파일형 credential은 read-only Docker secret 또는 배포 secret volume을 사용한다.
5. 모듈 README의 설정 공급 표를 함께 갱신한다.
6. `docker compose config --quiet`와 해당 모듈 테스트를 실행한다.

## 운영 체크

- `VAULT_HOST`, `VAULT_TOKEN`은 Config Server 부트스트랩 값으로 배포 환경에서 주입한다.
- native 설정 파일에는 시크릿 값을 커밋하지 않는다.
- 운영 Compose의 `S3_ACCESS_KEY`, `S3_SECRET_KEY`는 필수이며 외부 Vault의
  `secret/application`에 저장한 동명 값과 정확히 같아야 한다. SeaweedFS, bucket init job,
  chat·team·user가 이 한 자격 증명 계약을 공유한다.
- `S3_PUBLIC_ENDPOINT`, `S3_PUBLIC_BASE_URL`은 클라이언트가 도달 가능한 주소로 배포 환경에서
  주입한다. 공개/인증 조회 정책, bucket 분리, public ingress의 SigV4 보존, signer 정합성, CORS와
  기존 URL 이관은 아직 확정하지 않았으며 [오브젝트 스토리지 공개 접근 계약 TODO](./todo/items/13-storage/object-storage-public-access-contract.md)에서 관리한다.
- 필수 시크릿이 없을 때 기본 개발 키로 대체하지 않는다.
- Config Server/Eureka의 `8761`은 Compose 내부망 또는 배포 플랫폼의 private control-plane network에서만 접근시킨다. 운영 Compose는 Gateway 이외의 application/infra/ops host port를 제거한다.
- 다중 replica의 Eureka instance ID는 명시적 `EUREKA_INSTANCE_ID`가 있으면 이를 사용하고, 없으면 runtime hostname·application·port 조합으로 만든다. non-Spring 서비스는 `EUREKA_USE_RUNTIME_HOSTNAME=true`일 때 non-loopback 내부 IP를 광고하며 consumer group ID에는 replica suffix를 붙이지 않는다.
- Config Server와 Vault를 우회하는 서비스 직접 포트는 운영 외부망에 공개하지 않는다.
