# cowork-preference

## 역할

사용자·팀·채널의 설정과 사용자 정의 역할 정책을 전담 관리합니다.

- 사용자 상태·표시 설정, 팀·채널 설정과 채널별 알림 설정
- 프로젝트 역할과 팀 사용자 정의 역할·권한·멤버 할당
- 채널별 역할의 메시지 읽기 정책과 GitHub 저장소 라벨 정책
- 설정 캐시·상태 만료 처리와 변경 이벤트 발행

## 스택

- Kotlin / Java 25 / Vert.x Coroutines
- Amper (`module.yaml`)
- Vert.x PostgreSQL Client / PostgreSQL / Flyway
- Vert.x Redis Client / Kafka Client / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| HTTP | `9001` | `9001` |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 (`local` 또는 `prod`) |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |

- Config Server: 포트, PostgreSQL host·DB·schema·pool, Redis, Kafka, Eureka.
- Vault: `preference.db.username`, `preference.db.password`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

Config Server를 3회 조회하지 못하면 종료합니다. Preference command·state·result 토픽 이름은 서비스 간 계약이므로 환경별 override를 허용하지 않습니다.
