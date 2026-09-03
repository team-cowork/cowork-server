# cowork-project

## 역할

팀 내 프로젝트·멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 생성·조회·수정·삭제와 순서 변경
- 프로젝트 멤버와 역할 관리
- GitHub 저장소 연결, PR 조회·승인·병합과 라벨 정책 변경 요청

## 스택

- Kotlin / Java 25 / Spring Boot
- Maven (`pom.xml`; Gradle은 Maven 실행 위임)
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client / OpenFeign

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| HTTP | `8084` | `8084` |

호스트 포트는 `COWORK_PROJECT_HOST_PORT`로 변경할 수 있습니다.

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT` | `configserver:http://cowork-config:8761` | 필수 Config Server 연결 |
| `COWORK_PROJECT_HOST_PORT` | `8084` | Compose 호스트 공개 포트 |

- Config Server: 포트, MySQL URL, Kafka, Eureka, GitHub App 서비스 URL.
- Vault: MySQL 계정, `github-app.internal-api-key`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
