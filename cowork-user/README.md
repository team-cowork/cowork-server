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
| --- | --- | --- |
| HTTP | `8082` | `8082` |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `APP_PROFILE` | `local` | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, DB host·port·name와 Flyway URL, Kafka, Redis, Eureka, S3 endpoint·정책.
- Vault: `DB_USERNAME`, `DB_PASSWORD`, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

컨테이너는 Config Server의 DB 설정을 읽고 Flyway migration을 적용한 뒤 Elixir release를 시작합니다. 필수 DB 설정이 없으면 기동하지 않습니다.
