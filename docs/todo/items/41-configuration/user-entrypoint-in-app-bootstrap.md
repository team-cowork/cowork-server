# cowork-user 기동 부트스트랩의 애플리케이션 내부 이관

- **서비스**: cowork-user
- **우선순위**: 🟡 낮음
- **현재 상태**: Config Server 조회와 스키마 마이그레이션을 `docker-entrypoint.sh`가 컨테이너 기동 전에 셸로 처리한다

## 문제

`cowork-user/docker-entrypoint.sh`는 저장소에 남아 있는 유일한 entrypoint 스크립트다. 나머지 11개
서비스는 `Dockerfile.prod`에서 실행 바이너리를 직접 `ENTRYPOINT`로 지정하고, 설정 조회와 마이그레이션을
애플리케이션 안에서 끝낸다. JVM 7종은 `spring.config.import: "optional:configserver:..."`와
`spring.flyway` 자동설정에 맡기고, Go 3종은 `internal/config/config.go`가 `APP_CONFIG_URL`을 직접
조회하고 `cowork-authorization/cmd/main.go:75`처럼 `Migrate(...)`를 코드에서 호출한다. cowork-user만
이 두 가지를 컨테이너 바깥의 셸에 두고 있다.

스크립트가 하는 일은 세 가지다. `docker-entrypoint.sh:29-46`이 `curl`과 `jq`로
`{APP_CONFIG_URL}/cowork-user/{profile}`을 조회해 `PORT`와 `DB_*`를 환경변수로 export하고,
`:44-49`가 DB 필수값을 `:?`로 검증하며, `:51-55`가 `FLYWAY_*`를 채운 뒤 `/flyway/flyway migrate`를
실행한다. 마지막으로 `:58`이 `exec /app/bin/cowork_user start`로 앱에 PID 1을 넘긴다.

문제는 이 중 첫 번째가 애플리케이션 코드와 **중복**이라는 점이다.
`lib/cowork_user/app_config.ex:201`의 `fetch_from_config_server/0`는 이미 `Req`로 동일한
`{APP_CONFIG_URL}/cowork-user/{profile}` 엔드포인트를 호출하고, `:225`의 `merge_property_sources/1`가
`propertySources`를 병합하며, `:249`의 `lookup/3`이 `["REDIS_HOST", "redis_host"]`처럼 셸의
`set_from_config`과 같은 다중 키 후보 방식을 쓴다. 즉 같은 계약을 셸과 Elixir가 각각 구현해두었고,
Config Server의 프로퍼티 키가 바뀌면 두 곳을 함께 고쳐야 한다.

두 번째 비용은 런타임 이미지다. `Dockerfile.prod:3`이 `flyway/flyway:12.8.1`을 스테이지로 받아 `:34`에서
`/flyway`를 통째로 복사하고, 이를 실행하려고 `:27`에서 `default-jre-headless`를 설치한다. Elixir 릴리스
이미지가 Flyway CLI 때문에 JRE를 함께 지고 있는 셈이다. `curl`과 `jq`도 entrypoint 전용이다
(healthcheck는 `deploy/compose/stack.yaml`의 `cowork-user`에서 `wget`을 쓴다).

## 이관 가능성

두 절반은 난이도가 다르다.

| 대상                | 필요한 것                      | 기존 자산                                                       |
|---------------------|--------------------------------|-----------------------------------------------------------------|
| Config Server 조회  | Repo 설정 시점 문제 해결       | `AppConfig.fetch_from_config_server/0` 이미 존재                |
| 스키마 마이그레이션 | Flyway 형식 SQL 러너 직접 구현 | `ecto_sql ~> 3.14`, `myxql ~> 0.9` 의존성 존재 / Go 구현이 선례 |

### Config Server 조회의 순서 문제

단순 재사용이 막히는 이유는 시점이다. `config/runtime.exs:25-40`이 `DB_HOST`·`DB_PORT`·`DB_NAME`·
`DB_USERNAME`·`DB_PASSWORD`를 읽어 `CoworkUser.Repo` 설정을 만드는데, 이 파일은
`CoworkUser.Application.start/1`보다 먼저 평가된다. 반면 `AppConfig.load/0`는
`application.ex:6`에서, 즉 그 뒤에 호출된다. 그래서 셸이 미리 환경변수를 채워주고 있다.

두 가지 해법이 있다.

| 방안                      | 내용                                                                                                                              | 평가                                                                                                                 |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| A. `runtime.exs`에서 조회 | `:httpc`(`:inets`)와 `Jason`으로 조회해 환경변수 대신 직접 Repo 설정을 구성한다                                                   | `Req`는 별도 애플리케이션 기동이 필요해 `runtime.exs`에서 쓸 수 있는지 확인이 필요하다. 조회가 부팅당 2회로 늘어난다 |
| B. Repo를 동적으로 기동   | `runtime.exs`에서 Repo 설정을 걷어내고, `application.ex:10`의 `{CoworkUser.Repo, []}`에 `AppConfig.load/0` 결과를 옵션으로 넘긴다 | 조회가 1회로 유지되고 기존 `AppConfig`를 그대로 쓴다. `Ecto.Repo` 자식 옵션이 app env를 덮어쓰는지 확인이 필요하다   |

B가 중복 제거라는 목적에 더 맞는다. 다만 `mix.exs`의 `extra_applications`에 이미 `:ssl`과 `:inets`가
있어 A도 배제되지는 않는다.

### 마이그레이션 러너

`src/main/resources/db/migration`에는 `V1__init.sql`부터 `V20__add_kafka_action_quarantine.sql`까지
Flyway 형식 SQL 20개가 있다. Ecto 마이그레이션(`.exs`) 형식이 아니므로 `Ecto.Migrator`가 그대로 읽지
못한다. `lib` 아래에 `Ecto.Migrator` 사용처가 없고 `priv/repo`도 없다.

따라서 `cowork-authorization/internal/infra/mysql/migrate.go`와 같은 러너를 Elixir로 직접 써야 한다.
Go 구현이 참고할 골격을 이미 보여준다.

- `migrate.go:19` — `^V(\d+)__(.+)\.sql$` 패턴으로 파일을 수집하고 버전 순으로 정렬한다
- `migrate.go:96-113` — `DB_MIGRATION_DIR`, 컨테이너 경로, 로컬 경로 순으로 디렉터리를 해석한다
- `migrate.go:66-73` — MySQL `GET_LOCK`으로 다중 인스턴스 동시 실행을 막는다
- `migrate.go:181-198` — `flyway_schema_history`를 생성하고 `:171-179`에서 적용 이력을 기록한다

다만 Go 구현을 그대로 옮길 필요는 없다. Go 서비스에는 Flyway 도입 이전 스키마가 있어
`baselineLegacySchema`(`:242-318`)와 버전별 `migrationExpectations`(`:405-492`) 검증기가 필요했고,
검증기가 없는 버전은 `:143-145`에서 기동을 거부한다. cowork-user는 처음부터 실제 Flyway가
`flyway_schema_history`를 만들어 왔으므로 baseline과 버전별 검증기 없이 훨씬 작게 끝날 가능성이 있다.
**이는 아직 검증하지 않았다.**

### 확인이 필요한 위험

- 실제 Flyway가 만든 `flyway_schema_history`에는 `checksum` 컬럼이 있지만 Go의 `ensureHistoryTable`은
  이 컬럼을 만들지 않는다. 자체 러너가 기존 테이블에 `checksum` 없이 행을 추가했을 때 이후 Flyway CLI로
  되돌아갈 수 있는지 확인하지 않았다. 되돌릴 수 없다면 단방향 결정이다.
- 현재 Flyway는 VM 부팅 **전에** 끝나므로 Kafka consumer를 포함한 모든 자식보다 앞선다. 러너를
  `application.ex`의 supervision tree 안에 넣을 경우 `{CoworkUser.Repo, []}` 직후·나머지 자식보다
  앞에서 **동기적으로** 완료되어야 같은 순서가 유지된다.
- 마이그레이션 실패 시 지금은 `set -eu`와 Flyway 종료 코드로 컨테이너가 뜨지 않는다. 이관 후에도
  기동이 실패해야 한다.

## 할 일

### Config Server 조회 이관

- 방안 A와 B 중 어느 쪽이 성립하는지 실제 릴리스 기동으로 확인한 뒤 하나를 택한다
- `AppConfig`가 `PORT`와 `DB_*`까지 담당하도록 확장하고 셸의 `set_from_config` 키 후보 목록을 옮긴다
- 환경변수가 이미 설정된 경우 Config Server 값보다 우선한다는 현재 규칙(`docker-entrypoint.sh:16-18`)을 유지한다

### 마이그레이션 러너 구현

- `flyway_schema_history`의 `checksum` 컬럼 처리 방침을 먼저 정한다
- `V{n}__{설명}.sql`을 버전 순으로 적용하고 이력을 기록하는 러너를 `lib` 아래에 추가한다
- MySQL `GET_LOCK`으로 동시 기동을 직렬화한다
- `application.ex`에서 Repo 기동 직후·나머지 자식보다 먼저 동기 실행되도록 배치한다
- 마이그레이션 SQL 디렉터리를 릴리스에 포함시키고 컨테이너·로컬 경로를 모두 해석한다

### 이미지 정리

- `Dockerfile.prod`에서 flyway 스테이지(`:3`), `/flyway` 복사(`:34-35`), `PATH` 추가(`:38`)를 제거한다
- `:27`의 `default-jre-headless`, `jq`, `curl`을 제거한다 (healthcheck가 쓰는 `wget`은 유지한다)
- `docker-entrypoint.sh`와 `:37`·`:40`·`:43`의 복사·권한·`ENTRYPOINT` 지정을 제거하고
  `ENTRYPOINT ["/app/bin/cowork_user", "start"]`로 바꾼다
- `Dockerfile.local:35`·`:39`·`:41`도 같이 정리한다

## 검증

- Config Server를 띄운 상태와 `APP_CONFIG_URL` 미설정 상태 모두에서 기동을 확인한다
- 빈 스키마에서 V1~V20이 순서대로 적용되고 `flyway_schema_history`에 20행이 기록되는지 확인한다
- 이미 최신인 스키마로 재기동했을 때 아무 SQL도 재실행되지 않는지 확인한다
- 실제 Flyway가 적용해둔 기존 스키마 위에서 러너가 적용 이력을 올바로 읽는지 확인한다
- 두 인스턴스를 동시에 기동해 `GET_LOCK`이 중복 적용을 막는지 확인한다
- 마이그레이션 실패 시 컨테이너가 기동에 실패하는지 확인한다
- 정리 전후 런타임 이미지 크기를 비교한다

## 완료 조건

- `cowork-user/docker-entrypoint.sh`가 저장소에 존재하지 않는다
- `Dockerfile.prod`이 Flyway CLI와 JRE를 포함하지 않는다
- Config Server 조회 로직이 `AppConfig` 한 곳에만 있다
- 스키마 마이그레이션이 애플리케이션 기동 과정에서 자동으로 실행된다
- 다른 11개 서비스와 동일하게 `ENTRYPOINT`가 실행 바이너리를 직접 가리킨다
