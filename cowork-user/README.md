# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- Elixir / Plug·Cowboy
- Mix
- Ecto / MySQL / Flyway (컨테이너 entrypoint)
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

- Config Server: 포트, DB host·port·name와 Flyway URL, Kafka, Redis, Eureka, S3 endpoint·정책.
- Vault: `DB_USERNAME`, `DB_PASSWORD`, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

컨테이너는 Config Server의 DB 설정을 읽고 Flyway migration을 적용한 뒤 Elixir release를 시작합니다. 필수 DB 설정이 없으면 기동하지 않습니다.

## 사용자 검색

이름·닉네임·통합 검색어(`q`/`query`)는 MySQL `LIKE`로 부분 일치합니다(`CoworkUser.Accounts.like_pattern/1`).
이 검색의 대소문자 무시·와일드카드 무해화 계약은 아래 두 DB 설정에 의존하므로, 마이그레이션이나
서버 설정 변경으로 조용히 깨지지 않도록 유지해야 합니다.

- 대상 컬럼(`accounts.name`, `profiles.nickname`)의 collation이 `utf8mb4_unicode_ci`(대소문자 무시)여야 합니다. 그렇지 않으면 검색이 대소문자를 구분합니다.
- MySQL `sql_mode`에 `NO_BACKSLASH_ESCAPES`가 설정되어 있으면 안 됩니다. 설정되어 있으면 `like_pattern/1`이 `\`로 escape한 `%`/`_`/`\`가 리터럴이 아니라 다시 LIKE 연산자로 해석되어 와일드카드 주입이 가능해집니다.
