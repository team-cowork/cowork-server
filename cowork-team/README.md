# cowork-team

## 역할

팀, 초대, 멤버 권한과 팀 이미지를 관리합니다.

- 팀 CRUD와 내 팀 목록 조회
- 초대 생성·목록·취소·참여
- 팀 멤버 조회·기본 역할 변경·제거
- 팀 사용자 정의 역할 조회와 비동기 생성·수정·삭제·할당 API
- SeaweedFS(S3 호환) presigned URL 기반 팀 아이콘 업로드·확정·교체·삭제
- 팀 삭제·멤버 제거 이벤트와 사용자 알림 발행

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Data JPA + MySQL + Flyway
- Spring Cloud Eureka·Config
- Spring Kafka, Spring Cloud AWS S3(SeaweedFS)

## 포트와 API

- 포트: `8085`
- 주요 경로: `/teams/**`, `/team-members/**`
- OpenAPI / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`
- Health / Prometheus: `/actuator/health`, `/actuator/prometheus`

## 이벤트와 의존성

- Kafka produce: `notification.trigger`, `team.lifecycle`, `team.member.event`, `preference.team-role.command`
- Kafka consume: `team.github.connected`, `team.github.disconnected`, `preference.team-role.changed`,
  `preference.team-role.command-result`
- `team.lifecycle`은 팀별 `TEAM_CREATED`/`TEAM_UPDATED`/`TEAM_DELETED`, `team.member.event`는 팀·사용자별
  `UPSERT`/`DELETE` full-state 계약입니다. 영속 원장의 version과 tombstone을 live 이벤트와 snapshot이 함께 사용합니다.
- GitHub App의 연결/해제 action은 team만 수신하며, installation 상태 변경도 team DB와 `team.lifecycle` outbox를 같은
  transaction으로 커밋합니다. downstream은 이 full-state stream을 replay해 빈 DB에서도 projection을 재구축합니다.
- 기본 멤버십 역할 `OWNER`/`ADMIN`/`MEMBER`는 이 서비스가 소유합니다. 사용자 정의 역할의 정의·권한·할당은
  `cowork-preference`가 소유하고, 이 서비스는 읽기/권한 검사에 필요한 local projection만 유지합니다.
- 사용자 정의 역할 write API 5개는 `Idempotency-Key`를 요구합니다. 요청은 MySQL operation과 transactional outbox에
  원자적으로 접수하고 서버 UUID `operationId`와 `PENDING` 상태를 `202 Accepted`로 반환합니다. 처리 결과는
  `/teams/{teamId}/role-operations/{operationId}`에서 조회하며, owner 검증 실패도 `FAILED`와 error 필드로 확인합니다.
- 성공 result를 받아도 대응하는 `preference.team-role.changed` version이 local projection에 보이기 전에는
  `SUCCEEDED`를 노출하지 않습니다. 내부 REST/Feign 호출로 preference mutation을 수행하지 않습니다.
- MySQL, SeaweedFS, Eureka, Config Server

`preference.team-role.changed`는 compacted full-state/tombstone stream이며 시작·주기 snapshot completion까지
checkpoint가 따라잡아야 projection readiness가 열립니다. Spring readiness health와 Eureka health check도 이 상태를
반영하므로 projection이 준비되기 전에는 트래픽 대상으로 승격되지 않습니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, MySQL URL, Kafka, Eureka, S3(SeaweedFS) endpoint/region |
| Vault | MySQL 계정, S3(SeaweedFS) access/secret key |

Compose에서는 Config Server 조회가 필수입니다. 환경변수 override는 직접 실행이나 긴급 운영 override에만 사용합니다.
