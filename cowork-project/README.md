# cowork-project

## 역할

팀 내 프로젝트, 멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 CRUD, 내 프로젝트 목록과 팀별 프로젝트 순서 변경
- 프로젝트 멤버 추가·조회·역할 변경·제거(`OWNER`, `EDITOR`, `VIEWER`)
- GitHub repository 연결·해제
- GitHub pull request board, PR 상세·변경 파일 조회
- PR squash merge와 승인
- 팀 lifecycle 이벤트에 따른 프로젝트·멤버십 정리

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Data JPA + MySQL + Flyway
- Spring Cloud Eureka·Config·OpenFeign
- Spring Kafka

## 포트와 API

- 서비스 포트: `8084`
- Docker Compose 기본 매핑: `8084:8084` (`COWORK_PROJECT_HOST_PORT`로 host 포트 변경 가능)
- 주요 경로: `/projects/**`, `/teams/{teamId}/projects/reorder`
- GitHub PR: `/projects/{projectId}/github/pulls/**`
- OpenAPI / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`
- Health / Prometheus: `/actuator/health`, `/actuator/prometheus`

## 이벤트와 의존성

- Kafka consume: `team.lifecycle`, `team.member.event`, `channel.event`, `user.profile.event`, `preference.github-repo.setting.state`, `preference.github-repo.setting.result`
- Kafka produce: `project.event`, `project.member.event`, `project.github-repo.event`, `preference.github-repo.setting.command`
- HTTP: 외부 GitHub App adapter의 실시간 원본 조회와 아직 외부 계약이 없는 댓글·라벨 API
- MySQL, Eureka, Config Server

### Projection 준비 상태

`team.member.event`, `team.lifecycle`, `channel.event`, `user.profile.event`, `preference.github-repo.setting.state` consumer는
projection 변경과 DB checkpoint를 같은 transaction으로 커밋합니다. snapshot-backed state stream인 `team.member.event`, `team.lifecycle`,
`channel.event`, `user.profile.event`, `preference.github-repo.setting.state`의 공유 DB 전체 partition barrier와 source
snapshot completion marker가 충족되기 전에는
projection 기반 권한·채널·사용자 조회가 503으로 fail-closed 되고, readiness/Eureka도 OUT_OF_SERVICE/STARTING 상태를
유지합니다. Kafka retention으로 checkpoint/marker가 현재 offset 범위를 벗어나거나 저장된 Kafka topic ID와 현재
topic ID가 다르면 자동 복구 완료로 간주하지 않으므로 projection 데이터와 checkpoint/barrier를 함께 재구성해야 합니다.

팀 GitHub installation read model도 별도 action topic이 아니라 authoritative `team.lifecycle` full state에서 갱신합니다.
disconnect/팀 삭제는 팀별 source-version tombstone을 남기고, installation별 ownership fence가 파티션 간 순서 역전에서도 최신
owner만 허용합니다. projection 변경과 tombstone/fence/checkpoint는 같은 DB transaction으로 커밋되며 startup/주기 snapshot
replay로 빈 project DB를 복구할 수 있습니다.

GitHub 저장소 연결과 webhook 채널은 `cowork-project`가 소유하고 `project.github-repo.event`로 발행합니다.
`label_auto_apply`는 `cowork-preference`의 authoritative `GITHUB_REPO` 설정입니다. `PUT
/projects/{projectId}/github-repos/{repoId}/label-policy`는 `Idempotency-Key`를 필수로 받고 project의 작업 row와
`preference.github-repo.setting.command` outbox를 같은 transaction에 기록한 뒤 `202 {operationId,status:PENDING}`를
반환합니다. 완료를 기다리지 않으며 `GET .../label-policy/operations/{operationId}`로 상태를 조회합니다.
`GET .../label-policy`는 `preference.github-repo.setting.state` local projection을 읽고, row가 없거나 DELETE면
기본값 `true`를 사용합니다. 저장소 연결을 단일 또는 cascade 삭제할 때는 같은 local transaction에서 안정적인
repo별 identity의 `commandType=DELETE`도 outbox에 넣습니다. 이 command는 공개 operation과 result가 없으며 preference의
설정 row가 snapshot UPSERT로 되살아나는 것을 막습니다. label UPDATE는 저장소 연결 row를 먼저 잠가 같은 key의 UPDATE
outbox가 DELETE outbox보다 뒤집히지 않게 합니다.

### GitHub 저장소 설정 Kafka 계약 (schemaVersion 1)

| 토픽 | key | 계약 |
|---|---|---|
| `preference.github-repo.setting.command` | `repoId` | 공통 `operationId`, `idempotencyKey`, `commandType`, `repoId`, `occurredAt`; UPDATE는 `settings.label_auto_apply`와 `requestedBy`, DELETE는 `settings=null`과 nullable `requestedBy` |
| `preference.github-repo.setting.result` | `operationId` | `operationId`, `idempotencyKey`, `repoId`, `status=SUCCEEDED\|FAILED`, 배타적 `settings`/`error`, SUCCEEDED의 `stateOccurredAt`, `occurredAt` |
| `preference.github-repo.setting.state` | `repoId` | `eventType=UPSERT\|DELETE`, `repoId`, UPSERT의 `settings.label_auto_apply`, `occurredAt`, `snapshot` |

Canonical JSON payload는 다음과 같다. 모든 시각은 RFC 3339 UTC 문자열이며 `snapshot`은 생략 시 `false`다.

Command:

```json
{"schemaVersion":1,"operationId":"<uuid>","idempotencyKey":"<1..128 chars>","commandType":"UPDATE","repoId":5,"settings":{"label_auto_apply":false},"requestedBy":7,"occurredAt":"2026-08-27T00:00:00Z"}
{"schemaVersion":1,"operationId":"<stable uuid>","idempotencyKey":"github-repo-setting-delete:5","commandType":"DELETE","repoId":5,"settings":null,"requestedBy":null,"occurredAt":"2026-08-27T00:00:02Z"}
```

SUCCEEDED/FAILED result:

```json
{"schemaVersion":1,"operationId":"<uuid>","idempotencyKey":"<same key>","repoId":5,"status":"SUCCEEDED","settings":{"label_auto_apply":false},"error":null,"stateOccurredAt":"2026-08-27T00:00:01Z","occurredAt":"2026-08-27T00:00:01Z"}
{"schemaVersion":1,"operationId":"<uuid>","idempotencyKey":"<same key>","repoId":5,"status":"FAILED","settings":null,"error":{"code":"<1..100 chars>","message":"<1..500 chars>"},"stateOccurredAt":null,"occurredAt":"2026-08-27T00:00:01Z"}
```

State UPSERT/DELETE:

```json
{"schemaVersion":1,"eventType":"UPSERT","repoId":5,"settings":{"label_auto_apply":false},"occurredAt":"2026-08-27T00:00:01Z","snapshot":false}
{"schemaVersion":1,"eventType":"DELETE","repoId":5,"settings":null,"occurredAt":"2026-08-27T00:00:02Z","snapshot":false}
```

State topic은 repo ID 기준 compacted topic이며 preference는 활성 key, durable DELETE tombstone, startup/주기 snapshot과
partition completion marker를 outbox로 발행해야 합니다. Command consumer는 `operationId`/`idempotencyKey`를 영속
멱등 처리하고 authoritative 설정 변경과 state/result outbox를 하나의 PostgreSQL transaction으로 커밋해야
합니다. SUCCEEDED result의 `settings`와 FAILED result의 `error {code,message}`는 서로 배타적입니다.
Project는 SUCCEEDED result를 받아도 즉시 완료로 노출하지 않고 `PROCESSING`으로 저장합니다. 같은
`repoId`, `settings.label_auto_apply`, `stateOccurredAt`을 가진 state event가 local projection에 반영된 뒤에만
operation을 `SUCCEEDED`로 완료합니다. 따라서 SUCCEEDED result의 `stateOccurredAt`은 함께 커밋한 UPSERT state의
`occurredAt`과 정확히 같아야 합니다. FAILED는 즉시 최종 상태입니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE`, host 공개 포트 `COWORK_PROJECT_HOST_PORT` |
| Config Server | 앱 포트, MySQL URL, Kafka, Eureka, GitHub App 서비스 URL |
| Vault | MySQL 계정, `github-app.internal-api-key` |

`local`/`dev` 설정은 Config 저장소에 포함되어 있으며 운영 시크릿은 Vault에 등록해야 합니다.
