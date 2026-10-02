# 외부 I/O와 DB transaction 경계 분리

- **서비스**: cowork-project, cowork-channel, GitHub App, OAuth provider, Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: channel OAuth 호출은 transaction 밖으로 분리되어 있으며 project의 GitHub 호출·동기 Kafka 대기는 transaction 안에 남아 있다.

## 문제

project의 GitHub 조회 서비스는 read-only transaction 안에서 Feign을 호출한다.
`CreateGithubIssueServiceImpl`도 같은 transaction에서 broker acknowledgement를 기다린다.
외부 지연이 DB transaction과 connection 점유를 늘릴 수 있다.

channel의 OAuth는 짧은 권한 조회 → 외부 호출 → 짧은 저장으로 분리되어 있다.
이 경로는 재구현하지 않고 권한 조회 뒤 회수 경합·외부 성공 뒤 저장 실패의 처리 정책을 확인한다.

## 경계 정책

| 단계 | 처리 |
|---|---|
| 권한·repo 정보 조회 | 짧은 read transaction에서 불변 값으로 반환한다. |
| 외부 HTTP | transaction 밖에서 실제 timeout을 적용한다. |
| 상태·command 기록 | 짧은 write transaction과 outbox·재처리 정책으로 관리한다. |

## 할 일

- project의 GitHub orchestration에서 외부 대기까지 감싸는 transaction을 분리한다.
- Feign timeout의 실제 바인딩과 Kafka command의 outbox 전환 범위를 확인한다.
- 권한 회수 경합, 외부 성공 뒤 로컬 실패의 보상·재처리 정책을 정한다.
- 외부 지연과 DB pool 점유를 함께 관측하고 필요한 동시 호출 한도를 정한다.

## 검증

- transaction 선언과 호출 그래프에서 HTTP·Kafka 대기가 경계 밖인지 확인한다.
- 권한 없는 외부 작업 차단과 재검증 정책은 client mock 기반 서비스 단위 테스트로 확인한다.
- 지연·부분 실패의 처리와 DB pool 점유는 수동 운영 점검으로 확인한다.

## 완료 조건

- 외부 대기가 로컬 DB transaction을 장시간 유지하지 않는다.
- 권한 조회·외부 작업·후속 기록의 실패·회수 경합 정책이 명확히 적용되어 있다.
