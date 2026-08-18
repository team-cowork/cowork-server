# cowork-team

## 역할

팀, 초대, 멤버 권한과 팀 이미지를 관리합니다.

- 팀 CRUD와 내 팀 목록 조회
- 초대 생성·목록·취소·참여
- 팀 멤버 조회·존재 확인·기본 역할 변경·제거
- `cowork-preference`에 저장되는 팀 사용자 정의 역할 생성·수정·삭제·할당
- SeaweedFS(S3 호환) presigned URL 기반 팀 아이콘 업로드·확정·교체·삭제
- 팀 삭제·멤버 제거 이벤트와 사용자 알림 발행

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Data JPA + MySQL + Flyway
- Spring Cloud Eureka·Config·OpenFeign
- Spring Kafka, Spring Cloud AWS S3(SeaweedFS)

## 포트와 API

- 포트: `8085`
- 주요 경로: `/teams/**`, `/team-members/**`
- OpenAPI / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`
- Health / Prometheus: `/actuator/health`, `/actuator/prometheus`

## 이벤트와 의존성

- Kafka produce: `notification.trigger`, `team.lifecycle`
- HTTP: `cowork-preference`(팀 사용자 정의 역할)
- MySQL, SeaweedFS, Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, MySQL URL, Kafka, Eureka, S3(SeaweedFS) endpoint/region |
| Vault | MySQL 계정, S3(SeaweedFS) access/secret key |

Compose에서는 Config Server 조회가 필수입니다. 환경변수 override는 직접 실행이나 긴급 운영 override에만 사용합니다.
