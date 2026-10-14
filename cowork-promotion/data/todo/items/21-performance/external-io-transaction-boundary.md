# 외부 I/O와 DB transaction 경계 분리

- **서비스**: cowork-project, cowork-channel, GitHub App, OAuth provider, Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: project의 외부 대기 분리와 channel OAuth 저장 시점 재인가가 남아 있다.

## 문제

project의 GitHub 조회·일부 command 발행 대기가 DB transaction 안에 있어 외부 지연이 connection 점유를 늘릴 수 있다.
channel OAuth는 외부 호출을 분리했지만 최초 권한 확인 이후 저장·중복 복구에서 권한 회수를 반영하지 않는다.

## 경계 정책

| 단계 | 처리 |
|---|---|
| 권한·repo 정보 조회 | 짧은 read transaction에서 불변 값으로 반환한다. |
| 외부 HTTP | transaction 밖에서 실제 timeout을 적용한다. |
| 상태·command 기록 | 짧은 write transaction과 outbox·재처리 정책으로 관리한다. |

## 할 일

- project의 외부 대기와 transaction을 분리하고 Feign timeout의 실제 바인딩을 확인한다.
- 이슈 생성·PR merge·approve의 outbox 전환 범위를 정한다. 이미 outbox를 사용하는 댓글·라벨 command는 유지한다.
- channel OAuth의 최종 저장·중복 복구 시점에 필요한 재인가를 적용한다.
- 권한 회수 경합과 외부 성공 뒤 로컬 실패의 보상·재처리 정책을 정한다.
- 외부 지연·DB pool 점유를 근거로 필요한 동시 호출 한도를 정한다.

## 검증

- transaction 선언·호출 그래프에서 HTTP·Kafka 대기가 경계 밖인지 정적으로 확인한다.
- 권한 없는 외부 작업 차단·재인가 정책은 client mock 기반 서비스 단위 테스트로 확인한다.
- 지연·부분 실패 처리와 DB pool 점유는 수동 운영 점검한다.

## 완료 조건

- 외부 대기가 로컬 DB transaction을 장시간 유지하지 않는다.
- 권한 조회·외부 작업·후속 기록의 실패·회수 경합 정책이 적용되어 있다.
