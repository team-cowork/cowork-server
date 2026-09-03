# cowork-team

## 역할

팀·초대·멤버 권한과 팀 이미지를 관리합니다.

- 팀 생성·조회·수정·삭제와 멤버 초대·참여·제거
- 기본 멤버십 역할 관리와 사용자 정의 역할 조회·변경 요청
- 팀 아이콘 업로드와 GitHub App 설치 연결
- 팀·멤버 상태 변경과 사용자 알림 발행

## 스택

- Kotlin / Java 25 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client
- Spring Cloud AWS S3 / SeaweedFS (S3 호환)

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8085`        | `8085`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                     | 기본값                                   | 설명                                |
|--------------------------|------------------------------------------|-------------------------------------|
| `SPRING_PROFILES_ACTIVE` | `local`                                  | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT`   | `configserver:http://cowork-config:8761` | 필수 Config Server 연결             |

- Config Server: 포트, MySQL URL, Kafka, Eureka, S3 endpoint·region, GitHub App 연결 설정.
- Vault: MySQL 계정, S3 access·secret key, GitHub 연동 state secret.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
