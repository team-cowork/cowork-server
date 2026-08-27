# cowork-team

## 역할

팀, 초대, 멤버 권한과 팀 이미지를 관리합니다.

- 팀 CRUD와 내 팀 목록 조회
- 초대 생성·목록·취소·참여
- 팀 멤버 조회·기본 역할 변경·제거
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

- Kafka produce: `notification.trigger`, `team.lifecycle`, `team.member.event`
- Kafka consume: `preference.team-role.changed`
- 역할 조회는 로컬 projection을 사용하며, 역할 변경 command만 cowork-preference에 동기 전달합니다.
- HTTP: `cowork-preference`(팀 사용자 정의 역할)
- MySQL, SeaweedFS, Eureka, Config Server

### Projection 준비 상태

`preference.team-role.changed`의 역할과 assignment는 한 state stream으로 처리되며 projection 변경과 DB checkpoint가 같은
transaction에 저장됩니다. topic 전체 partition barrier와 source snapshot completion marker가 충족되기 전에는 역할 기반
권한/조회가 503으로 fail-closed 되고, readiness/Eureka도 OUT_OF_SERVICE/STARTING 상태를 유지합니다. Kafka retention으로
checkpoint/marker가 현재 offset 범위를 벗어나거나 저장된 Kafka topic ID와 현재 topic ID가 다르면 자동으로 ready 처리하지
않으므로 projection 데이터와 checkpoint/barrier를 함께 재구성해야 합니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, MySQL URL, Kafka, Eureka, S3(SeaweedFS) endpoint/region |
| Vault | MySQL 계정, S3(SeaweedFS) access/secret key |

Compose에서는 Config Server 조회가 필수입니다. 환경변수 override는 직접 실행이나 긴급 운영 override에만 사용합니다.
