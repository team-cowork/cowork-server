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

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 변경 절차](../docs/deployment.md#설정-변경과-재배포)를 참고합니다.

운영 검색 컬럼의 collation은 `utf8mb4_unicode_ci`를 유지하고 세션 `sql_mode`에 `NO_BACKSLASH_ESCAPES`를 추가하지 않습니다. 대소문자 구분 없는 검색과 `%`·`_`의 문자 검색이 이 조건에 의존합니다. 기존 Flyway 이력을 유지하며 migration 실패 복구는 [배포 절차](../docs/deployment.md#실패-복구)를 따릅니다.
