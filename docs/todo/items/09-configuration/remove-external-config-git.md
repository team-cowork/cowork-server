# 외부 Config Git 제거 및 prod native 전환

- **서비스**: cowork-config, Config Client 11개, 배포 인프라
- **우선순위**: 🟠 중간
- **결론**: Git backend 제거와 `cowork-*-prod.yml` 11개 추가 완료. 외부 Git 원본과의 key 대조 및 staging 검증이 남아 있음

## 진행 상태 (2026-08-20)

| 단계 | 상태 |
|---|---|
| 1. 외부 Git·운영 env property inventory | ❌ 미수행 (외부 저장소 접근 불가) |
| 2. key 분류 (native / Vault / topology / client env) | ✅ 완료 |
| 3. `cowork-*-prod.yml` 11개 추가 | ✅ 완료 |
| 4. 런타임별 placeholder·우선순위 보완 | ✅ 완료 |
| 5. prod composite Git → native 교체 | ✅ 완료 |
| 6. `CONFIG_GIT_*` 제거 | ✅ 완료 |
| 7. 문서 갱신 | ✅ 완료 |
| 8. staging 검증 후 단계적 전환 | ❌ 미수행 |
| 9. 외부 Git 저장소·자격 증명 폐기 | ❌ 미수행 |

1번을 수행하지 못했으므로 **기존 외부 Git이 공급하던 key 중 누락된 것이 있을 수 있다.** `*-prod.yml`은 각 서비스의 `-dev.yml`을 기준으로 작성했다. 외부 저장소 접근이 가능해지면 key set을 대조해야 한다.

### placeholder 처리 규칙 (검증 결과)

Config Server는 native 설정의 `${...}`를 **해석하지 않고 그대로 클라이언트에 전달한다.** 따라서 런타임별로 사용 방식이 다르다.

| 구분 | 서비스 | 규칙 |
|---|---|---|
| Spring | gateway, channel, project, roadmap, team, preference | 클라이언트가 자체 Environment로 해석하므로 `${VAR:default}` 사용 가능. 기본값이 없는 이름은 Config Server `overrides`에 등록해 응답에 포함시켜야 한다. |
| 비Spring | authorization, notification, voice (Go), chat (NestJS), user (Elixir) | 원격 문자열의 placeholder를 해석하지 않는다. **리터럴 값만 사용한다.** 배포 환경에서 바꿔야 하는 값은 `overrides`에 같은 키 이름으로 등록하거나 해당 컨테이너의 환경변수로 덮어쓴다. |

`overrides`에 등록된 flat 키는 Spring 클라이언트의 placeholder 해석 소스가 되는 동시에, 비Spring 클라이언트가 키 이름으로 직접 읽는 값이 된다. 현재 등록 대상은 `S3_INTERNAL_ENDPOINT`, `S3_PUBLIC_ENDPOINT`, `S3_PUBLIC_BASE_URL`, `PUBLIC_WEB_ORIGIN`, `PUBLIC_API_BASE_URL`, `GITHUB_APP_SERVICE_URL`, `LIVEKIT_URL`, `LIVEKIT_WS_URL`이다.

### 검증 완료 항목

Config Server를 `native` 프로파일로 기동하고 prod와 동일한 `overrides`를 주입해 `GET /cowork-{service}/prod` 11개 응답을 확인했다.

- 11개 모두 `cowork-{service}-prod.yml`이 propertySources에 로드됨
- 비Spring 5개 서비스 응답에 미해결 placeholder 없음
- Spring 6개 서비스의 남은 placeholder는 전부 기본값 보유·`overrides` 제공·Vault 제공 중 하나로 해석 가능
- 공통 `application.yml`의 `spring.kafka.bootstrap-servers`는 비Spring 서비스가 읽지 않는 키이므로 무해 (각각 `KAFKA_BOOTSTRAP_SERVERS`, `kafka.brokers`, `KAFKA_BROKERS`, `kafka_bootstrap_servers`를 사용)

Vault 연결이 필요한 시크릿 해석과 각 서비스 실제 기동 테스트는 8번에서 수행한다.

## 현재 구조와 제거 조건

`cowork-config`의 `local`·`dev` 프로파일은 `Vault + classpath native` 조합이고, `prod`는 `Vault + 외부 Git` 조합이다. `CONFIG_GIT_URI`는 Spring Cloud Config가 prod 일반 설정을 읽을 Git 저장소의 위치를 찾기 위해 필요한 부트스트랩 값이며, Git backend를 제거한 뒤에는 필요하지 않다.

현재 classpath에는 공통 `application.yml`과 11개 Config application의 `local`·`dev` 파일만 있고 `*-prod.yml`은 하나도 없다. 저장소 밖의 실제 Config Git 내용과 배포 인스턴스 환경 파일도 이 코드베이스만으로는 확인할 수 없다. 따라서 외부 저장소의 prod 설정을 먼저 목록화하지 않은 상태에서 Git backend를 삭제하면 누락된 설정을 식별하거나 되돌리기 어렵다.

목표 구조는 다음과 같다.

| 설정 종류 | 목표 공급원 | 예시 |
|---|---|---|
| 시크릿 | Vault | DB 자격 증명, JWT 키, OAuth client secret, LiveKit·SeaweedFS 자격 증명 |
| 버전 관리할 일반 설정 | `cowork-config` classpath native | 포트, topic 이름, timeout, 기능 제한값, 고정된 provider endpoint |
| 배포 토폴로지 공유값 | 배포 환경 또는 배포별 native 값 | Kafka·Redis·DB·SeaweedFS·Elasticsearch 주소, 서비스 간 endpoint |
| 인스턴스별 값 | 각 Config Client의 환경변수 | Eureka instance host/port, 인스턴스마다 다른 공개 URL |

## 사전 확인이 필요한 외부 상태

다음 항목을 확인하기 전에는 Git backend 제거를 진행하지 않는다.

- 현재 `CONFIG_GIT_URI`가 가리키는 저장소와 실제 운영 label/branch를 확인한다.
- 외부 저장소의 `application*.yml`, `cowork-*-prod.yml` property key 목록을 시크릿 값 없이 snapshot으로 남긴다.
- 각 배포 인스턴스의 env 파일 또는 secret store에서 `CONFIG_GIT_*`, Config Server 주소, 서비스별 topology override를 확인한다.
- 외부 Git에 시크릿이 들어 있다면 값을 문서나 native 파일로 복사하지 않고 Vault로 이동한 뒤 기존 Git 이력의 노출 대응 범위를 결정한다.
- 현재 Config Git 응답과 전환할 native 응답의 key set을 비교할 기준 목록을 확정한다.

## 모듈별 prod 설정 범위

아래 11개 application에 `cowork-{service}-prod.yml`이 필요하다. `cowork-promotion`은 Config Client가 아니므로 대상에서 제외한다.

| application | prod에서 확인할 일반·토폴로지 설정 |
|---|---|
| `cowork-authorization` | DataGSM endpoint, Kafka, JWT 만료 시간, Eureka instance, user service URL |
| `cowork-channel` | MySQL, Kafka, Eureka, AccountShare callback/redirect와 provider endpoint |
| `cowork-chat` | Elasticsearch, MongoDB, Kafka, Redis, SeaweedFS, 서비스 URL, Eureka, 업로드 제한 |
| `cowork-gateway` | 전체 route, CORS, Redis, Kafka, Eureka, Swagger, circuit breaker |
| `cowork-notification` | Kafka, Firebase 파일 경로, preference/team/user URL, Eureka instance |
| `cowork-preference` | PostgreSQL, Redis, Kafka, Eureka instance |
| `cowork-project` | MySQL, Kafka, Eureka, GitHub App service URL |
| `cowork-roadmap` | R2DBC·Flyway MySQL, Eureka, team service URL |
| `cowork-team` | MySQL, Kafka, SeaweedFS 내부·공개 endpoint, Eureka |
| `cowork-user` | MySQL, Kafka, Redis, SeaweedFS, team service URL, Eureka instance |
| `cowork-voice` | MongoDB, Redis, LiveKit API·WebSocket, Kafka, channel service URL, Eureka instance |

공통 `configs/application.yml`에 있는 Kafka와 Eureka localhost 기본값도 prod에서 실수로 사용되지 않도록 함께 점검한다.

## 분산 배포 주소 규칙

모든 컨테이너가 같은 Docker network나 인스턴스에 있지 않으므로 `mysql`, `kafka`, `redis`, `cowork-config`, `cowork-*`, `seaweedfs`, `elasticsearch`, `host.docker.internal` 같은 Compose 전용 hostname을 prod 기본값으로 고정하지 않는다.

- 모든 인스턴스가 공유하는 endpoint는 배포 환경의 명시적인 값으로 공급한다.
- Eureka instance host/port처럼 인스턴스마다 달라지는 값은 Config Server 공통 응답이 아니라 해당 Config Client 환경변수에서 override한다.
- Config Server process의 환경변수는 모든 client에 같은 값으로 해석될 수 있으므로 인스턴스별 값 공급원으로 사용하지 않는다.
- 공개 URL과 내부 service URL을 분리하고, 실제 외부 네트워크 경로로 도달 가능한지 확인한다.
- 필수 prod endpoint에는 localhost나 Compose hostname fallback을 두지 않거나, 기동 시 잘못된 fallback 사용을 검출한다.

## 런타임별 주의 사항

Spring Config Client와 자체 구현 Config Client의 placeholder 및 우선순위 동작이 같지 않다.

- Spring Boot 모듈은 client process의 환경변수와 Config property source 우선순위를 사용한다.
- Go 모듈은 원격 property를 읽은 뒤 client 환경변수로 구조체 값을 덮어쓴다.
- `cowork-chat`은 이미 존재하는 client 환경변수를 보존하지만 원격 문자열 내부의 `${ENV}`를 별도로 해석하지 않는다.
- `cowork-user`도 client 환경변수를 원격값보다 우선하지만 원격 문자열 placeholder를 공통으로 해석하지 않는다.
- `cowork-preference`는 원격 문자열의 `${ENV:default}`를 client 환경변수로 해석한 뒤 명시적 환경변수 mapping을 다시 적용한다.

따라서 `*-prod.yml`에 `${ENV}` placeholder를 일괄 추가하지 않는다. 각 값은 native의 구체적인 일반 설정, Vault 시크릿, client-side environment override 중 하나로 분류하고 모든 런타임에서 같은 우선순위가 되는지 테스트한다.

## 구현 순서

1. 외부 Config Git과 운영 env의 현재 property key를 inventory하고 rollback용 snapshot을 만든다.
2. 각 key를 native 일반 설정, Vault 시크릿, 공유 topology 값, 인스턴스별 client 환경변수로 분류한다.
3. 11개 `cowork-*-prod.yml`과 필요한 prod 공통 설정을 추가한다.
4. 각 런타임의 환경변수 우선순위와 placeholder 처리 차이를 보완하고 테스트한다.
5. Config Server `prod` composite의 Git backend를 native backend로 교체한다.
6. `docker-compose.prod.yml`과 배포 환경에서 `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_PASSWORD`를 제거한다.
7. 관련 문서를 native prod 기준으로 갱신한다.
8. staging 또는 별도 검증 환경에서 비교·기동 테스트 후 prod를 단계적으로 전환한다.
9. 안정화 기간이 끝난 뒤 외부 Config Git 저장소와 관련 자격 증명을 폐기한다.

## 수정 대상

현재 저장소에서 `CONFIG_GIT_*` 또는 prod Git backend를 직접 설명하거나 사용하는 파일은 다음과 같다.

- `cowork-config/src/main/resources/application.yml`
- `docker-compose.prod.yml`
- `cowork-config/README.md`
- `cowork-project/README.md`
- `docs/configuration.md`
- `docs/gateway-config.md`
- `docs/local-run-guide.md`
- `docs/development-guide.md`

GitHub Actions workflow와 `.env.example`에는 현재 `CONFIG_GIT_*` 직접 참조가 없지만, 실제 배포 인스턴스의 저장소 외부 env 파일은 별도로 확인해야 한다.

## 운영 영향과 rollback

Git backend는 Config Server image를 다시 빌드하지 않고도 설정 commit과 label로 일반 설정을 변경·되돌릴 수 있다. classpath native로 전환하면 일반 설정 변경과 rollback이 `cowork-config` image tag 및 애플리케이션 commit에 묶인다.

- 설정 변경 시 Config Server image build·배포가 필요하다는 운영 절차를 문서화한다.
- classpath 내용이 바뀌지 않은 상태에서 `/actuator/busrefresh`만 호출해도 새 설정이 생기지 않는다는 점을 명시한다.
- 전환 직전 Config Git commit과 기존 Config Server image tag를 기록한다.
- 전환 안정화 전까지는 기존 Git 자격 증명과 rollback 가능한 배포 구성을 보존한다.

## 검증

- `CONFIG_GIT_*` 없이 `prod` Config Server가 `Vault + native`로 기동하는지 확인한다.
- 11개 `GET /cowork-{service}/prod` 응답이 모두 존재하고, 의도하지 않은 미해결 `${...}`와 localhost·Compose 전용 hostname이 없는지 검사한다.
- 기존 Git 응답과 native 응답의 property key set을 비교하고, 의도적으로 제거·이동한 key는 migration 목록으로 남긴다.
- 시크릿 key가 native 파일이나 Git tracked 파일에 값으로 기록되지 않았는지 검사한다.
- `docker compose -f docker-compose.yml -f docker-compose.prod.yml config`가 `CONFIG_GIT_*` 없이 성공하는지 확인한다.
- Spring Boot, Go, NestJS, Vert.x, Elixir에서 각각 대표 Config Client 기동 테스트를 수행한다.
- 중앙 Config Server와 다른 인스턴스에 배치한 서비스가 외부 네트워크를 통해 설정 조회, Eureka 등록, 의존 서비스 호출을 정상 수행하는지 확인한다.
- 설정 변경, Config Server image rollback, Vault 장애 시 동작을 staging에서 검증한다.

## 완료 조건

- Config Server의 prod backend가 `Vault + classpath native`로 동작한다.
- 11개 prod application 설정이 외부 Config Git 없이 제공된다.
- 운영 설정 key가 native, Vault, 공유 배포 환경, client별 환경변수 중 한 곳에 명시적으로 소유된다.
- 시크릿은 Vault에만 저장되고 native 파일과 배포 문서에 평문으로 남지 않는다.
- 분산 인스턴스가 localhost나 같은 Docker network를 전제로 하지 않고 기동한다.
- `CONFIG_GIT_*` 참조와 외부 Config Git 운영 의존성이 제거된다.
- 설정 변경·배포·rollback 절차가 문서화되고 런타임별 기동 검증이 자동화되어 있다.
