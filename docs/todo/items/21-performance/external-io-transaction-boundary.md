# 외부 I/O와 DB transaction 경계 분리

- **서비스**: cowork-project, cowork-channel, GitHub App·OAuth provider·Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: GitHub HTTP, OAuth HTTP, 동기 Kafka 전송을 DB transaction 안에서 기다리는 경로가 존재함

## 문제

`cowork-project`의 다수 GitHub 조회·댓글·라벨 service가 `@Transactional(readOnly = true)` 안에서 프로젝트, 팀 멤버십, repository link, 사용자 profile projection을 조회한 뒤 `GithubAppClient`를 호출한다. 외부 지연 동안 persistence context와 transaction이 유지되며, connection release 정책에 따라 DB connection도 네트워크 응답을 기다리며 점유될 수 있다. 연결 3초·읽기 10초 선언은 구형 `feign.client.config` prefix에 남아 있어 현재 의존성에 적용된다고 가정할 수 없다. [Feign 설정 조사](../../../../cowork-project/docs/feign-hc5-pooling.md)의 미해결 설정 문제를 함께 확인한다.

`CreateGithubIssueServiceImpl`, `MergePullRequestServiceImpl`, `ApprovePullRequestServiceImpl`은 같은 read-only transaction에서 `GithubActionCommandPublisher`를 호출한다. publisher는 Kafka acknowledgement를 최대 5초 동기 대기하므로 broker 지연도 DB transaction 수명에 포함된다.

`cowork-channel`의 `HandleOAuthCallbackServiceImpl`은 class-level `@Transactional` 아래에서 authorization code 중복 여부를 조회하고 OAuth token·user info HTTP 요청을 수행한 뒤 shared account를 저장한다. provider가 느리면 채널 DB transaction도 함께 길어지며, provider 호출은 성공했지만 로컬 저장이 실패하는 부분 성공을 DB rollback만으로 복구할 수도 없다.

이 구조는 외부 장애를 DB pool 고갈로 전파하고 transaction annotation이 실제 원격 부수 효과까지 원자적으로 보호하는 것처럼 보이게 한다. 로컬 권한·중복 여부 조회, 외부 I/O, 로컬 상태·outbox 기록을 명시적인 단계로 분리한다.

## 경계 설계

| 단계 | transaction 정책 |
|------|--------------------|
| 프로젝트·멤버십·repo link 권한 또는 OAuth 중복 여부 조회 | 짧은 read-only transaction에서 불변 DTO로 반환함 |
| GitHub App·OAuth provider HTTP 호출 | 로컬 DB transaction 밖에서 timeout·circuit breaker를 적용함 |
| Kafka command 발행 | request transaction과 분리하거나 로컬 outbox에서 비동기 발행함 |
| shared account·후속 event·outbox 기록 | 별도의 짧은 write transaction과 idempotency key로 처리함 |

권한 조회와 외부 변경 사이의 권한 회수 경합을 어느 수준까지 허용할지도 명시한다. 강한 일관성이 필요한 mutation은 비동기 command와 재검증 가능한 상태 머신을 검토한다.

## 할 일

### orchestration 분리

- GitHub orchestration service의 광범위한 `@Transactional`을 제거하고 권한과 repo reference를 불변 값으로 반환하는 짧은 조회 component를 만든다.
- GitHub Feign 호출과 `GithubActionCommandPublisher`의 acknowledgement 대기 중 로컬 transaction이 활성화되지 않게 한다.
- `HandleOAuthCallbackServiceImpl`을 중복 확인, provider 호출, idempotent 저장 단계로 나누고 authorization code 또는 provider account 식별자의 재사용 정책을 명시한다.
- 외부 mutation 뒤 로컬 기록이 필요한 경로의 부분 성공, 보상, 재처리 정책을 정의한다.
- request가 broker acknowledgement를 기다릴 필요가 없는 command는 transactional outbox로 전환한다.

### 성능과 장애 격리

- GitHub·OAuth·Kafka 호출 latency, timeout, 오류 상태와 각 서비스의 DB pool active connection을 함께 관측한다.
- 느린 외부 응답이 일반 CRUD DB pool을 고갈시키지 않도록 HTTP client와 command publisher의 bulkhead를 검토한다.
- 목록 API의 downstream pagination과 최대 응답 크기 계약을 함께 확인한다.

## 검증

- GitHub App·OAuth provider·Kafka 호출이 transaction 밖에 있는지 호출 그래프와 transaction 선언을 정적으로 점검한다.
- 외부 지연 중 DB transaction·connection 점유 여부는 runtime metric과 부하 관측으로 확인한다.
- 권한 없는 사용자의 외부 호출을 차단하는 핵심 판단은 client mock을 사용한 서비스 단위 테스트로 검증한다.
- HTTP·Kafka 실패와 외부 작업 성공 뒤 로컬 저장 실패의 보상·재처리 정책은 상태 전이 검토와 운영 rehearsal로 확인한다.
- 외부 시스템, database, broker를 구동하는 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- GitHub App·OAuth provider·Kafka 대기는 로컬 DB transaction 밖에서 실행된다.
- 외부 지연이 `cowork-project`와 `cowork-channel`의 DB connection을 장시간 점유하지 않는다.
- 권한 조회, 외부 호출, 후속 기록의 일관성·실패 정책이 메서드와 운영 문서에 명확히 분리되어 있다.
