# JVM Kafka outbox relay 정체와 장기 transaction 제거

- **서비스**: cowork-channel, cowork-team, cowork-project
- **우선순위**: 🔴 높음
- **현재 상태**: 세 서비스의 relay가 가장 오래된 outbox row를 잠근 transaction 안에서 Kafka 전송을 기다리고 첫 실패 row를 매번 다시 선택함

## 문제

세 모듈의 `KafkaOutboxRelay`는 동일한 구조로 `tb_kafka_outbox`의 오래된 row 최대 100개를 `FOR UPDATE`로 조회한다. 같은 database transaction 안에서 각 row의 Kafka 전송 결과를 최대 10초 기다린 뒤 성공 row를 삭제한다. Kafka가 느리면 connection과 row lock을 한 batch 동안 길게 점유하며, 이론상 대기 시간이 batch 크기에 비례해 늘어난다.

전송이나 JSON 역직렬화가 실패하면 `attempts`와 `last_error`만 갱신하고 loop를 중단한다. 다음 실행도 `ORDER BY id ASC`로 같은 row를 다시 선택하며 재시도 상한, backoff, `next_attempt_at`, 격리 상태가 없다. 영구적으로 잘못된 선두 row 하나가 뒤의 정상 이벤트 전체를 무기한 막을 수 있다.

세 구현은 package와 lock 이름을 제외하면 거의 같아 정책 수정이 모듈별로 어긋날 위험도 있다. 공통 동작을 재사용 가능한 relay 구성요소로 추출하되, 서비스별 topic과 순서 보장 범위는 명시적으로 주입한다.

## 전달 정책

| 항목 | 결정할 내용 |
|------|-------------|
| 순서 보장 | 전체 전역 순서가 필요한지, 동일 aggregate·event key 안에서만 필요한지 결정함 |
| claim | 짧은 transaction에서 owner와 lease 만료 시각을 기록함 |
| publish | database lock 밖에서 Kafka 전송을 수행함 |
| finalize | 성공 삭제·완료 표시와 실패 backoff를 짧은 transaction으로 반영함 |
| 영구 실패 | 최대 시도 뒤 별도 격리 테이블 또는 상태로 이동하고 운영 재처리 경로를 제공함 |
| 중복 | claim 후 crash로 생길 수 있는 재발행을 event ID와 소비자 멱등성으로 흡수함 |

## 할 일

### relay 구조

- claim, Kafka publish, finalize 단계를 분리해 네트워크 대기 중 database transaction을 열어 두지 않는다.
- `attempts`, `next_attempt_at`, claim owner·만료 시각, 격리 상태를 후속 migration으로 추가한다.
- poison row가 같은 key의 순서를 지켜야 하는 범위와 무관한 key의 진행을 막지 않게 선택 쿼리를 설계한다.
- interrupt와 shutdown 시 claim을 안전하게 반환하거나 lease 만료로 복구되게 한다.
- 세 서비스가 공유하는 relay 핵심과 서비스별 설정 경계를 분리한다.

### 운영과 복구

- 대기 row 수, 최고 지연 시간, 재시도 횟수, 격리 건수, publish 지연을 metric으로 노출한다.
- 격리 row의 payload를 안전하게 조회·수정·재처리하는 절차를 문서화한다.
- payload 역직렬화 오류와 Kafka 일시 장애를 서로 다른 실패 유형으로 기록한다.

## 검증

- 영구 실패 row 뒤의 다른 event key가 계속 발행되는지 통합 테스트한다.
- 동일 event key의 순서가 정책대로 유지되는지 검증한다.
- Kafka 전송을 지연시켜도 outbox row lock과 database connection 점유 시간이 claim/finalize transaction 범위로 제한되는지 확인한다.
- relay crash, claim lease 만료, 재시작 뒤 중복·유실 없이 복구되는지 검증한다.
- 세 모듈의 기존 outbox writer와 migration을 사용하는 회귀 테스트를 실행한다.

## 완료 조건

- poison outbox row 하나가 무관한 후속 이벤트 발행을 무기한 차단하지 않는다.
- Kafka 네트워크 대기 동안 database transaction과 row lock이 유지되지 않는다.
- 재시도·backoff·격리·수동 재처리 정책이 세 서비스에서 일관되게 동작한다.
- relay의 지연과 실패 상태를 metric과 테스트로 확인할 수 있다.
