# cowork-project

## 역할

팀 내 프로젝트, 멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 CRUD, 내 프로젝트 목록과 팀별 프로젝트 순서 변경
- 프로젝트 멤버 추가·조회·역할 변경·제거(`OWNER`, `EDITOR`, `VIEWER`)
- GitHub repository 연결·해제
- GitHub pull request board, PR 상세·변경 파일 조회
- PR squash merge와 승인
- 팀·사용자 lifecycle 이벤트에 따른 프로젝트·멤버십 정리

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Data JPA + MySQL + Flyway
- Spring Cloud Eureka·Config·OpenFeign
- Spring Kafka, Resilience4j

## 포트와 API

- 서비스 포트: `8084`
- Docker Compose 기본 매핑: `8084:8084` (`COWORK_PROJECT_HOST_PORT`로 host 포트 변경 가능)
- 주요 경로: `/projects/**`, `/teams/{teamId}/projects/reorder`
- GitHub PR: `/projects/{projectId}/github/pulls/**`
- OpenAPI / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`
- Health / Prometheus: `/actuator/health`, `/actuator/prometheus`

## 이벤트와 의존성

- Kafka consume: `team.lifecycle`, `team.member.event`, `user.lifecycle`
- Kafka produce: `project.event`, `project.member.event`
- HTTP: `cowork-user`, GitHub App 중계 서비스
- MySQL, Eureka, Config Server

### Projection 준비 상태

`team.member.event`, `team.lifecycle`, `user.lifecycle` consumer는 projection 변경과 DB checkpoint를 같은 transaction으로
커밋합니다. snapshot-backed state stream인 `team.member.event`, `team.lifecycle`의 공유 DB 전체 partition barrier와 source
snapshot completion marker가 충족되기 전에는 projection 기반 권한 검사가 503으로 fail-closed 되고, readiness/Eureka도
OUT_OF_SERVICE/STARTING 상태를 유지합니다. `user.lifecycle`은 producerless action-only stream이므로 durable checkpoint는
유지하지만 readiness barrier에는 포함하지 않습니다. Kafka retention으로 checkpoint/marker가 현재 offset 범위를 벗어나거나
저장된 Kafka topic ID와 현재 topic ID가 다르면 자동 복구 완료로 간주하지 않으므로 projection 데이터와 checkpoint/barrier를
함께 재구성해야 합니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE`, host 공개 포트 `COWORK_PROJECT_HOST_PORT` |
| Config Server | 앱 포트, MySQL URL, Kafka, Eureka, GitHub App 서비스 URL |
| Vault | MySQL 계정, `github-app.internal-api-key` |

`local`/`dev` 설정은 Config 저장소에 포함되어 있으며 운영 시크릿은 Vault에 등록해야 합니다.
