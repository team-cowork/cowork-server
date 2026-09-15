# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- Elixir / Plug·Cowboy
- Mix
- Ecto / MyXQL / MySQL
- 애플리케이션 내부 SQL 마이그레이션 (`flyway_schema_history` 유지)
- brod (Kafka) / Redix (Redis) / ExAws S3 / SeaweedFS
- Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8082`        | `8082`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수             | 기본값                      | 설명                                                      |
|------------------|-----------------------------|-----------------------------------------------------------|
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결                                   |
| `APP_PROFILE`    | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, DB host·port·name, Kafka, Redis, Eureka, S3 endpoint·정책.
- Vault: `DB_USERNAME`, `DB_PASSWORD`, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

컨테이너는 `/app/bin/cowork_user start`로 Elixir release를 직접 실행합니다. 애플리케이션은 Config Server를 한 번 조회하고, 임시 MyXQL 연결에서 DB 마이그레이션을 완료한 뒤 Repo·Kafka·HTTP 프로세스를 시작합니다. 필수 DB 설정이 없거나 설정 조회·마이그레이션에 실패하면 기동하지 않습니다.

비어 있지 않은 환경변수가 원격 설정보다 우선합니다. DB는 `DATABASE_URL` 또는 `DB_URL`로 지정할 수 있으며, URL을 사용하지 않으면 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`가 필요합니다. Repo와 migration은 같은 접속 옵션을 사용합니다. `LOG_PATH`를 지정하면 해당 로그 디렉터리를 release 기동 시 생성하며 앱 사용자에게 쓰기 권한이 있어야 합니다.

기본 로그 경로는 release에서 `/var/log/cowork/user/application.log`, 로컬 Mix 실행에서 `_build/log/application.log`입니다.

## DB 마이그레이션

SQL 파일은 `priv/db/migration/V{버전}__{설명}.sql`에 둡니다. Mix release에 포함되므로 별도 SQL 마운트나 Flyway CLI·JRE가 필요하지 않습니다. 기존 파일의 버전·이름·내용을 유지하고, 스키마 변경은 새 정수 버전 파일로 추가합니다.

- 하나의 DB 연결에서 Flyway 12.8.1과 같은 MySQL named lock을 획득하고, 기존 `flyway_schema_history`와 체크섬을 검증한 뒤 미적용 파일을 순서대로 실행합니다. 잠금을 60초 안에 획득하지 못하면 기동을 중단합니다.
- 한 파일의 여러 일반 SQL 문장은 지원합니다. `DELIMITER`, `SOURCE`, `${...}` placeholder, 실행형 주석, 저장 프로시저, 트랜잭션·세션 제어문과 repeatable migration·callback은 사용하지 않습니다.
- 실행을 시작한 migration은 실패 상태로 기록하고 모든 SQL이 성공하면 성공 상태로 변경합니다. 실패 이력이 있으면 이후 기동도 중단합니다.

MySQL DDL은 부분 적용 후 자동으로 롤백되지 않을 수 있습니다. 실패 시 앱의 재시작을 중지하고 해당 SQL·실제 스키마·`flyway_schema_history`를 대조합니다. 먼저 부분 적용된 객체를 수동으로 정리한 다음 실패 이력을 복구하고 다시 기동합니다. 실패 행만 삭제하거나 성공으로 바꾸어 스키마 불일치를 숨기지 않습니다. 기존에 적용된 파일의 체크섬 불일치는 원래 파일로 복원합니다.

단일 MySQL 서버의 named lock을 기준으로 동시 기동을 직렬화합니다. Galera·Percona cluster의 Flyway 대체 잠금 방식은 지원하지 않습니다. 앱 이미지 롤백은 DB 변경을 되돌리지 않습니다.

## 사용자 검색

이름·닉네임·통합 검색어(`q`/`query`)는 MySQL `LIKE`로 부분 일치합니다(`CoworkUser.Accounts.like_pattern/1`).
이 검색의 대소문자 무시·와일드카드 무해화 계약은 아래 두 DB 설정에 의존하므로, 마이그레이션이나
서버 설정 변경으로 조용히 깨지지 않도록 유지해야 합니다.

- 대상 컬럼(`accounts.name`, `profiles.nickname`)의 collation이 `utf8mb4_unicode_ci`(대소문자 무시)여야 합니다. 그렇지 않으면 검색이 대소문자를 구분합니다.
- MySQL `sql_mode`에 `NO_BACKSLASH_ESCAPES`가 설정되어 있으면 안 됩니다. 설정되어 있으면 `like_pattern/1`이 `\`로 escape한 `%`/`_`/`\`가 리터럴이 아니라 다시 LIKE 연산자로 해석되어 와일드카드 주입이 가능해집니다.
