# 설정 및 시크릿 관리

이 문서는 `cowork-server` 서비스가 설정을 받는 단일 기준을 정의한다.

## 공급 원칙

| 종류                     | 공급원                                                  | 예시                                                    |
|--------------------------|---------------------------------------------------------|---------------------------------------------------------|
| 부트스트랩               | 운영: Vault `deploy/<target>` → Actions; 로컬: Compose  | 활성 프로파일, Config Server/Vault 주소, host 공개 포트 |
| 인프라 부트스트랩 시크릿 | Vault → Actions → 컨테이너                              | DB/Vault/Grafana 관리자 계정, LiveKit server key        |
| 일반 설정                | 코드 기본값: Config Server native; 운영 override: Vault | 내부 URL, timeout, 기능 정책                            |
| 애플리케이션 시크릿      | Vault                                                   | DB 계정, JWT/세션 서명 키, OAuth secret, API key        |

운영값은 Vault에서 관리하고 GitHub Actions는 조회·수정·배포를 수행한다. VM의 환경 파일을 수정하지
않는다. GitHub에는 Vault 접근 토큰·주소, 복구용 bootstrap과 일시적인 변경 입력만 둔다.
설정 교체·재배포는 [배포 가이드](deployment.md)를 따른다. 로컬 Compose의 `.env`와 seed는 유지한다.
기존 데이터·시크릿의 유지·이관 여부는 운영 담당자 재량이며 배포의 필수 조건이 아니다.

Config Server 응답의 속성 우선순위는 다음과 같다.

```text
Config Server overrides > Vault 서비스 경로 > Vault 공통 경로 > native `configs/`
```

클라이언트는 이를 자체 기본값·환경변수와 병합한다. Go·Elixir·Vert.x는 코드에서 매핑한 환경변수만
덮어쓰며, Chat은 기존의 비어 있지 않은 환경변수를 보존한다. Spring은 Config Client의 property source
우선순위를 따른다. 모든 런타임에서 임의의 환경변수가 같은 이름의 원격 설정보다 우선한다고 가정하지 않는다.
운영 배포의 `runtime`은 Config 접속값·프로파일·VM 주소를 공급하며 `application`은 컨테이너에
직접 전달할 명시적 환경변수다. 일반 속성은 Config Server 경로를 사용한다. `runtime_refs`와
`application_refs`로 기존 Vault key를 참조하면 동일 시크릿을 배포 문서마다 복사하지 않는다.

## 프로파일

| 프로파일 | Config Server backend                       |
|----------|---------------------------------------------|
| `local`  | Vault + classpath `configs/*-local.yml`     |
| `prod`   | 외부 Vault + classpath `configs/*-prod.yml` |

지원하는 배포 프로파일은 `local`과 `prod`다. Gateway와 모든 backend business service는 두 프로파일
파일을 모두 가져야 한다. 공통 Vault 값과 Config Server overrides는 서비스별 파일과 별도로 공급되므로,
응답이 비어 있지 않다고 해당 프로파일이 정의되어 있다고 판단하면 안 된다. 시크릿은 배포 전 Vault에
동일한 application 이름으로 등록한다.

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

Config Server나 Vault client가 아닌 MySQL, PostgreSQL, MongoDB, LiveKit, Grafana, Alertmanager 같은 인프라·서드파티 컨테이너는 배포 환경의 secret을 Compose로 직접 받는다. 같은 값이 애플리케이션에도 필요하면 로컬에서는 `vault-init`으로 기록하고, 운영에서는 Vault의 같은 원본을 참조한다.

아래 credential 계약은 인프라와 앱에서 일치해야 한다. 기존 DSN 형태 속성은 사용자·비밀번호가
포함된 전체 값을 요구한다. Vault 참조는 문자열 내부를 조합하지 않으므로 DB credential을 회전할 때
이 DSN도 함께 갱신한다. 운영 bootstrap의 동일 값은 가능하면 `runtime_refs`로 참조한다.

| Compose bootstrap 입력                       | 외부 Vault 대상                                                                                                                                                        | 일치 계약                                                                              |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `MYSQL_USER`, `MYSQL_PASSWORD`               | `secret/application`의 동명 key, `secret/cowork-authorization`의 `DB_DSN`, `secret/cowork-notification`의 `db.dsn`, `secret/cowork-user`의 `DB_USERNAME`·`DB_PASSWORD` | 같은 MySQL login과 각 서비스 DB 이름을 사용한다. 운영 DSN은 실제 사설 주소를 가리킨다. |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`         | `secret/application`의 동명 key, `secret/cowork-preference`의 `preference.db.username`·`preference.db.password`                                                        | 같은 PostgreSQL login을 사용한다.                                                      |
| `MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD` | `secret/cowork-chat`·`secret/cowork-voice`의 `MONGODB_URI`                                                                                                             | 같은 root login을 URI에 넣고 서비스별 DB 이름과 `authSource=admin`을 사용한다.         |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`             | `secret/application`의 동명 key                                                                                                                                        | SeaweedFS server·bucket init·chat/team/user가 같은 key pair를 사용한다.                |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`      | `secret/cowork-voice`의 동명 key                                                                                                                                       | LiveKit server와 voice token 발급기가 같은 key pair를 사용한다.                        |
| JWT signing secret                           | `secret/application`의 `JWT_SECRET`, `secret/cowork-gateway`의 `jwt.secret`, `secret/cowork-authorization`의 `JWT_SECRET`                                              | authorization 서명, Gateway HTTP 검증, chat WebSocket 검증에 동일한 secret을 사용한다. |

## 서비스별 부트스트랩

| 런타임      | 방식                                                           | Config Server 실패 처리                                                      |
|-------------|----------------------------------------------------------------|------------------------------------------------------------------------------|
| Spring Boot | Compose의 `SPRING_CONFIG_IMPORT=configserver:...`              | 기동 실패; 모듈 기본값의 `optional:configserver:...`만 쓰는 직접 실행은 다름 |
| Go          | `APP_CONFIG_URL`, `APP_PROFILE` custom client                  | URL 지정 시 기동 실패                                                        |
| NestJS      | bootstrap 전 Config Server 조회                                | 기동 실패                                                                    |
| Vert.x      | 배포 전 Config Server 조회                                     | 3회 실패 후 종료                                                             |
| Elixir      | entrypoint가 DB/Flyway 설정 조회 후 앱 내부에서 일반 설정 조회 | 기동 실패                                                                    |

## Firebase 자격 증명 교체

`secret/cowork-notification/local` 또는 `secret/cowork-notification/prod`의
`fcm.credentials-json`에 서비스 계정 JSON 전체를 **문자열**로 저장한다. 중첩 객체나 파일 경로가
아니며 `private_key`의 줄바꿈은 JSON 직렬화로 보존한다. 공통 경로에 이 키를 두지 않는다.
알림 서비스는 `APP_PROFILE`로 선택한 Config Server 값을 메모리에서 Firebase SDK에 전달한다.
`service_account` 형식만 허용하며 파일 마운트나 `FCM_CREDENTIALS_FILE` 환경변수는 사용하지 않는다.

기존 프로파일의 다른 속성을 보존한 전체 문서를 준비하고 기존 Prod CD의 `update-config`에서
`target=notification`, `scope=application`, `profile=local|prod`와 현재 Vault 버전을 지정한다.
성공 후 `notification`을 재배포하고 임시 `VAULT_UPDATE_JSON`을 삭제한다.
실제 JSON·개인키는 저장소 프로파일 YAML에 기록하지 않는다.

## 변경 절차

1. 일반 설정은 `cowork-config/src/main/resources/configs/cowork-{service}-{profile}.yml`에 추가한다.
2. 운영 설정·시크릿은 Vault key로 관리하며 기존 Prod CD의 `operation=update-config` 또는 Vault UI/API로 변경한다.
3. 로컬 시크릿이면 `.env.example`, `vault-init` 환경 전달, `deploy/config/vault/seed-secrets.sh` 저장 경로를 함께 갱신한다.
4. Firebase credential은 서비스 프로파일 Vault의 `fcm.credentials-json` 문자열로 공급한다. 배포 문서의 `files`는 지원하지 않는다.
5. 코드만으로 알 수 없는 설정 제약과 운영 절차만 `docs/`에 갱신하고, 후속 구현은 `docs/todo/`로 분리한다.
6. 설정 변경은 배포 workflow의 `check_only`로 확인하고, 비즈니스 로직 변경 시에만 해당 핵심 로직의 단위 테스트를 실행한다.

## 운영 체크

- `VAULT_HOST`, `VAULT_TOKEN`은 Config Server 부트스트랩 값으로 배포 환경에서 주입한다.
- native 설정 파일에는 시크릿 값을 커밋하지 않는다.
- 운영 S3 서버의 `S3_ACCESS_KEY`, `S3_SECRET_KEY`는 외부 Vault의
  `secret/application`에 저장한 동명 값과 정확히 같아야 한다. SeaweedFS, bucket init job,
  chat·team·user가 이 한 자격 증명 계약을 공유한다.
- `S3_PUBLIC_ENDPOINT`, `S3_PUBLIC_BASE_URL`은 클라이언트가 도달 가능한 주소로 배포 환경에서
  주입한다. 공개/인증 조회 정책, bucket 분리, public ingress의 SigV4 보존, signer 정합성, CORS와
  기존 URL 이관은 아직 확정하지 않았으며 [오브젝트 스토리지 공개 접근 계약 TODO](./todo/items/13-storage/object-storage-public-access-contract.md)에서 관리한다.
- 필수 시크릿이 없을 때 기본 개발 키로 대체하지 않는다.
- Config Server/Eureka의 `8761`은 운영 private control-plane network에서만 접근시킨다. 하위 앱 포트는 VM 사설 주소에 바인딩하고 Gateway와 필요한 운영 peer만 접근하도록 제한한다.
- 다중 replica의 Eureka instance ID는 명시적 `EUREKA_INSTANCE_ID`가 있으면 이를 사용하고, 없으면 runtime hostname·application·port 조합으로 만든다. non-Spring 서비스는 `EUREKA_USE_RUNTIME_HOSTNAME=true`일 때 non-loopback 내부 IP를 광고하며 consumer group ID에는 replica suffix를 붙이지 않는다.
- Config Server와 Vault를 우회하는 서비스 직접 포트는 운영 외부망에 공개하지 않는다.

배포 경로, VM별 endpoint와 local→prod 전환은 [배포 가이드](deployment.md)를 참고한다. 운영 Vault 재배포에서는 local 시크릿 seed를 실행하지 않는다.
