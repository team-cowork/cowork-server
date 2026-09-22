# Authorization 웹훅 멱등 처리와 outbox 원자화

- **서비스**: cowork-authorization
- **우선순위**: 🔴 높음
- **현재 상태**: `POST /events/datagsm`가 처리 여부 조회, 학생별 Kafka 직접 발행, 처리 완료 기록을 서로 분리해 실행함

## 문제

`cowork-authorization/internal/service/event_service.go`의 `ProcessEvent`는 webhook `event_id`로 `ProcessedEventStore.Exists`를 먼저 조회한다. 미처리 event이면 `data.new[]`를 여러 `user.data.sync` 메시지로 만든 뒤 `EventPublisher.Publish`를 순차 호출하고, 모든 호출이 끝난 다음 `MarkProcessed`를 실행한다. 조회와 완료 기록 사이에 원자적인 claim이 없어 동일 event의 동시 요청 두 개가 모두 미처리 상태를 관측하고 같은 batch를 발행할 수 있다.

여러 학생을 포함한 batch의 중간 publish가 실패하면 앞에서 성공한 메시지만 Kafka에 남고 처리 완료 row는 생성되지 않는다. webhook 재시도는 앞 항목부터 다시 발행한다. 모든 publish가 성공한 뒤 `MarkProcessed`가 실패해도 오류를 로그로만 남기고 `nil`을 반환하므로 `POST /events/datagsm`는 `200`을 응답한다. 이후 같은 event가 다시 전달되면 처리 기록이 없어 batch 전체가 중복 발행된다.

`V3__add_processed_events.sql`의 `tb_processed_events.event_id` primary key와 `V4__add_kafka_outbox.sql`·`V5__add_kafka_outbox_partition.sql`의 `tb_kafka_outbox`는 이미 존재한다. 그러나 `cowork-authorization/cmd/main.go`는 webhook service에 outbox writer가 아니라 Kafka `Producer`를 직접 주입하며, `V8`까지의 후속 migration에도 webhook event와 item을 outbox row에 연결하는 unique key는 없다. 현재 단위 테스트는 웹훅 서명, 입력 검증, 학생 데이터 매핑 같은 핵심 규칙만 다루며 transaction, 동시성, relay 복구 메커니즘은 테스트 범위에서 제외한다.

추가로 `cmd/main.go`는 처리 기록을 7일 뒤 정리한다. `EventService`에는 이벤트 발생 시각의 접수 기한 검증이 없어 기록 삭제 후 같은 이벤트를 다시 받아들일 수 있다. `cowork-user/lib/cowork_user/kafka/user_sync_contract.ex`는 필수 문자열의 공백·길이와 음이 아닌 `event_index`를 검증하지만 현재 수신 측은 이 조건을 모두 검사하지 않는다. 접수 후 소비자가 형식 오류로 격리할 입력을 접수 전에 차단한다.

## 처리 계약

`서명·전체 입력 검증 → 정규화 → 단일 DB transaction으로 inbox와 전체 outbox 저장 → 200 → 기존 relay 발행 → cowork-user 반영` 순서로 처리한다.

`200 accepted`는 배치 전체의 영속 접수를 의미한다. Kafka 발행이나 학생 정보 반영 완료를 의미하지 않는다. 여러 학생의 변경을 하나의 서비스 간 transaction으로 묶지 않으며, 소비자는 학생별로 반영한다.

| 상황 | 결과 |
|---|---|
| 유효한 신규 이벤트 | inbox와 모든 outbox row를 함께 commit하고 `200 accepted`를 반환한다. |
| 접수 기한 안의 동일 ID·동일 내용 | 추가 outbox 없이 `200 duplicate`를 반환한다. |
| 접수 기한 안의 동일 ID·다른 내용 | 기존 기록을 바꾸지 않고 `409 event_id_conflict`를 반환한다. |
| 배치 중 한 항목이라도 잘못된 입력 | 영속 변경 없이 배치 전체를 `400 invalid_payload`로 거부한다. |
| 발생 후 30일 이상 경과 | 중복 기록 존재 여부와 무관하게 `400 event_expired`를 반환한다. |
| 발생 시각이 기준 시각보다 5분 초과 미래 | `400 invalid_event_timestamp`를 반환한다. |
| 잘못된 서명 / 1 MiB 초과 본문 | 각각 `401 invalid_signature` / `413 payload_too_large`를 반환한다. |
| DB 연결·잠금·transaction timeout 등 일시 실패 | 성공으로 응답하지 않고 `503 temporarily_unavailable`을 반환한다. |
| 서명과 envelope가 유효한 미지원 이벤트 종류 | 신규 inbox·outbox 없이 `200 ignored`를 반환한다. |

commit 응답을 받기 전에 연결이 끊기면 호출자는 실패 응답이나 timeout을 받을 수 있다. 같은 이벤트로 재요청하면 DB에 남은 inbox가 결과를 결정한다. commit 여부를 확인하지 못한 요청을 성공으로 추정하지 않는다.

## 입력과 동일 내용의 기준

- 기존 `student.updated`만 처리한다. 실제 발행 대상의 `student_id`를 Kafka key로 사용하고 현재 `user.data.sync` payload 계약을 유지한다.
- 서명은 잘리지 않은 원본 body로 검증한다. body를 제한 길이보다 한 바이트 더 읽는 방식 등으로 초과 여부를 구분하고 잘린 JSON을 처리하지 않는다.
- `id`, `event`, `timestamp`, `data.new`와 각 항목의 `index`, `object`를 명시적으로 검증한다. 누락된 `index`를 `0`으로 해석하지 않고 음수·중복 index를 거부한다.
- `data.new`는 비어 있지 않은 배열이어야 한다. 현재 명시적으로 건너뛰는 빈 객체 `{}`는 변경 없는 항목으로 유지하되 `null`, 필드 누락, 배열·문자열은 잘못된 입력으로 거부한다. 모든 항목이 `{}`이면 inbox만 기록하는 정상 no-op으로 접수한다.
- 하나의 배치에 같은 `student_id`의 변경이 여러 번 있으면 거부한다. 한 이벤트의 동일 발생 시각을 가진 여러 변경 중 소비자가 첫 번째만 적용하는 모호함을 제거한다.
- 필수 문자열의 공백·길이, 선택 필드의 타입·길이는 `UserSyncContract`와 계정 컬럼에 맞춘다. `student_id`와 `event_index`는 양쪽 런타임 및 저장 타입에서 손실 없이 표현할 수 있는 범위로 제한한다.
- 이벤트 ID는 앞뒤 공백 없는 최대 255바이트로 제한하고 DB에서도 바이트 단위로 구분한다. 대소문자·악센트·공백을 DB collation이 합쳐서는 안 된다.
- 동일 내용은 정규화한 업무 입력으로 판단한다. 이벤트 종류, UTC 발생 시각, index 순서로 정렬한 `data.new`의 index와 실제 반영 필드를 canonical JSON으로 만들고 SHA-256을 계산한다. 빈 객체 항목의 index도 포함한다.
- JSON 공백·객체 키 순서·배열 내 항목의 나열 순서·동일 시각의 timezone 표기 차이는 같은 내용이다. 실제 반영 필드, index, 발생 시각의 변경은 충돌이다. 현재 반영하지 않는 `data.old`와 부가 필드는 해시에서 제외한다.
- 해시의 발생 시각은 UTC로 바꾸되 원래 정밀도를 보존한다. 소비자용 `occurred_at`만 현재 계약대로 microsecond로 정규화한다. 선택 필드의 누락과 `null`은 현재 발행 계약과 동일하게 정규화한다.
- 중복 JSON key와 숫자 타입 손실로 검증·해시·발행이 서로 다른 값을 보지 않도록 하나의 파싱 결과에서 정규화 배치와 최종 payload를 만든다. 중복 JSON key는 거부한다.

## 저장 구조와 원자 처리

| 저장소 | 역할과 제약 |
|---|---|
| `tb_webhook_inbox` | `event_id`를 primary key로 사용한다. `event_type`, `payload_hash`, `message_count`, `occurred_at`, `accepted_at`, `expires_at`을 저장한다. 원본 body와 학생 개인정보는 복사하지 않는다. |
| `tb_kafka_outbox` | 기존 topic·key·payload와 함께 nullable `source_event_id`, `source_event_index`를 추가한다. 두 컬럼의 조합은 unique이며 웹훅 row는 둘 다 존재하고 다른 용도의 row는 둘 다 `NULL`이다. |
| inbox 보존 제약 | outbox의 `source_event_id`는 같은 서비스 DB의 inbox를 참조한다. 미발행 row가 참조하는 inbox 삭제를 막고 cascade 삭제를 사용하지 않는다. |
| 만료 조회 | inbox의 `expires_at` 인덱스와 outbox의 source identity 인덱스로 만료·미발행 여부를 조회한다. |

inbox는 접수의 증거다. 발행 완료 상태를 관리하는 별도 상태 머신이나 HTTP 작업 조회 API는 추가하지 않는다. outbox가 발행 후 삭제되어도 inbox는 보관 기한까지 남는다.

repository의 `SubmitBatch` 하나가 다음 순서를 소유한다.

1. 요청 context와 유한한 timeout을 적용한 MySQL transaction을 시작한다.
2. DB의 UTC 시각을 기준으로 접수 기한을 확인한다. 애플리케이션 replica별 시계 차이로 접수·정리 기준이 달라지지 않게 한다.
3. `event_id` primary key에 insert해 최초 요청을 결정한다. `Exists` 선조회로 소유권을 결정하지 않는다.
4. 중복 key이면 기존 row를 locking read하고 해시를 비교한다. 실제 중복 key 오류만 중복으로 취급하며 다른 DB 오류를 삼키지 않는다. affected-row 수에 따라 달라지는 upsert 판정이나 `INSERT IGNORE`를 사용하지 않는다.
5. 신규 inbox이면 검증·직렬화가 끝난 모든 메시지를 같은 transaction의 outbox에 적재한다. `source_event_id`, `source_event_index`는 payload의 `event_id`, `event_index`와 동일하다.
6. 하나라도 실패하면 전체 rollback한다. commit이 확인된 뒤에만 신규 접수 성공을 반환한다. 동시 요청의 deadlock·lock timeout은 성공이나 중복으로 처리하지 않고 재시도 가능한 실패로 반환한다.

## 30일 보관과 접수 기한

- 접수 가능한 발생 시각의 범위는 `DB 현재 시각 - 30일 < occurred_at <= DB 현재 시각 + 5분`이다. 정확히 30일이 된 이벤트는 거부한다.
- `expires_at = max(accepted_at, occurred_at) + 30일`로 고정해 접수 후 최소 30일을 보관한다. 허용한 미래 시각만큼 보관 기간이 최대 5분 늘어날 수 있다. 저장 정밀도 변환 시 만료를 앞당기지 않도록 올림한다.
- 중복 요청은 최초 `accepted_at`과 `expires_at`을 연장하지 않는다.
- 매시간 제한된 크기의 batch로 `expires_at <= DB 현재 시각`이면서 미발행 outbox가 없는 inbox만 정리한다. 정리 실패는 다음 주기에 다시 시도하고 정상 접수를 실패시키지 않는다.
- 미발행 outbox는 접수 기한이 지나도 발행을 계속한다. 접수 만료는 신규 HTTP 요청의 기준이며 이미 접수한 작업을 버리는 TTL이 아니다.
- 보관 기한을 넘긴 inbox가 outbox 때문에 남아 있어도 오래된 HTTP 요청은 거부한다. 만료된 inbox 수와 미발행 때문에 정리하지 못한 수를 구분해 관측한다.
- 멱등 보장은 보관 중인 ID와 원래 발생 시각을 유지한 재전달에 적용한다. 보관 기간이 끝나 기록을 삭제한 ID를 발신자가 새 발생 시각으로 재사용하는 경우까지 판별하지는 않는다. 발신자의 이벤트 ID와 발생 시각은 불변이라는 계약을 문서에 명시한다.

## 발행과 소비자 경계

기존 `OutboxRelay`가 저장된 key·payload를 그대로 발행한다. Kafka 전송 성공 뒤 outbox 삭제 전 프로세스가 종료되면 같은 메시지가 다시 발행될 수 있으므로 전달 보장은 at-least-once다. HTTP 중복 요청이 outbox를 다시 만드는 문제와 relay의 재전달 가능성을 구분한다.

`cowork-user`의 `Accounts.apply_student_event`는 학생 계정을 잠그고 `datagsm_updated_at`보다 새로운 이벤트만 반영하며 계정 갱신과 프로필 outbox를 같은 transaction에서 처리한다. 이 경계를 유지해 동일 메시지의 재전달이 변경을 중복 적용하지 않게 한다. 아직 가입하지 않은 학생의 skip, 서로 다른 이벤트의 동일 시각 처리, consumer의 영속 격리 정책은 현재 업무 계약을 따른다.

형식 검증 통과가 계정 email·GitHub ID의 업무 충돌까지 사전에 보장하지는 않는다. 소비자에서 발생하는 업무 충돌은 기존 영속 격리 경로와 운영 복구 절차로 처리하고, 접수 성공을 최종 적용 성공으로 보고하지 않는다. Kafka 보존 기간 안에 소비자가 따라잡지 못한 경우 등 외부 데이터 손실까지 inbox만으로 복구할 수 있다고 주장하지 않는다.

relay의 기존 순서 보장과 retry를 유지한다. 실패 row를 임의로 삭제하거나 다음 row·snapshot marker가 추월하게 하지 않는다. Kafka 장애 중에도 HTTP 요청이 Kafka를 직접 호출하지 않지만 DB 잠금 때문에 접수 transaction이 실패하면 `503`을 반환한다.

## 구현 범위와 순서

### 1. 입력·응답 계약

- `cowork-authorization/internal/service/event_service.go`의 파싱·검증·배치 정규화를 분리하고 Kafka `EventPublisher` 의존성을 제거한다.
- `cowork-authorization/internal/handler/event_handler.go`에 본문 초과 검출과 접수 결과·오류 매핑을 적용한다. `accepted`, `duplicate`, `ignored`를 명시적인 서비스 결과로 전달한다.
- `cowork-user/lib/cowork_user/kafka/user_sync_contract.ex`와 `cowork-user/lib/cowork_user/accounts.ex`를 기준으로 입력·발행 계약을 대조한다.

### 2. inbox와 outbox 저장

- `ProcessedEventStore`, `ProcessedEventRepository`, `ProcessedEvent`를 inbox 모델과 `SubmitBatch` repository로 교체한다.
- 신규 schema migration에서 inbox와 outbox source 제약을 정의하고 사용하지 않는 `tb_processed_events`를 제거한다. 현재 마지막 migration은 `V8`이며 구현 시 해당 서비스의 최신 번호를 다시 확인한다.
- 커밋된 migration 파일은 수정하지 않는다. 새 DDL은 빈 DB에서 전체 migration을 적용하는 경로를 기준으로 작성하고 구버전 기록의 hash 생성·backfill·병행 코드 경로는 만들지 않는다. 운영 DB 초기화나 데이터 삭제는 이 설계 작업에서 실행하지 않는다.
- `cowork-authorization/internal/infra/mysql/migrate.go`의 새 버전 schema 검증도 갱신한다. 이 runner는 SQL 파일만 추가하면 미등록 schema 검사 때문에 기동을 거부한다.
- `cowork-authorization/cmd/main.go`의 주입과 cleanup을 교체하고 기존 7일 정리 코드를 제거한다. 요청·worker 종료 context를 전달한다.

### 3. 관측과 운영 문서

- 접수 결과를 `accepted`, `duplicate`, `conflict`, `invalid`, `expired`, `unavailable` 같은 제한된 label로 집계한다. 성공 count는 commit 뒤에만 증가시킨다.
- 웹훅 outbox의 대기 수·최고 대기 시간·발행 실패, cleanup 삭제 수·실패·보존 연장 수를 노출한다. 다중 replica의 공유 DB gauge를 합산해 중복 집계하지 않도록 안내한다.
- event ID, 서명, 학생 개인정보, payload 원문을 로그·metric label·HTTP 오류에 넣지 않는다. DB driver 오류 원문도 식별자를 포함할 수 있어 오류 분류만 노출한다.
- `cowork-authorization/README.md`, Swagger 생성물, `docs/development-guide.md`에 성공의 의미, 30일 접수·보관 계약, 충돌 응답과 outbox 적체·consumer 격리 확인 절차를 기록한다.

## 검증

- 서명 검증, 전체 배치의 필수값·길이·index·학생 ID 중복 판단, 빈 객체 no-op, 학생 필드 매핑, 접수 가능한 시각 경계를 핵심 업무·보안 규칙의 단위 테스트로 검증한다.
- `go build`, `go vet`, 저장소의 Go 정적 검사와 Swagger 생성을 수행한다. 테스트는 해당 핵심 규칙에 해당하는 대상만 선택해 실행한다.
- inbox·outbox insert의 동일 transaction, unique·참조 제약, locking read, rollback, 만료 계산과 cleanup query는 코드·schema 검토로 확인한다.
- 동시 재전달, commit 뒤 응답 단절, Kafka 장애·복구, publish 뒤 outbox 삭제 전 종료, 만료와 미발행 보존은 수동 검증 환경의 운영 점검 절차로 작성한다. 실제 실행 여부와 결과를 별도로 기록한다.
- MySQL·Kafka·handler·relay를 구동하는 자동화 통합·회귀 테스트를 작성하거나 실행하지 않는다. 전달 멱등성·직렬화·cleanup 구현을 그대로 고정하는 테스트도 추가하지 않는다.

## 완료 조건

- 신규 웹훅 성공 응답은 inbox와 전체 outbox의 commit 이후에만 반환된다.
- 접수 기한 안의 동일 ID·동일 내용 재요청은 추가 outbox를 만들지 않고 다른 내용은 충돌로 거부된다.
- 요청 실패가 배치 일부만 영속 접수된 상태를 남기지 않는다.
- 수신한 배치의 형식 오류가 일부 항목의 발행 이후에 발견되지 않는다.
- 모든 발행 항목의 source identity와 key·payload가 재시도에도 유지되고 웹훅 요청 경로의 Kafka 직접 발행이 제거되어 있다.
- 처리 기록은 30일 정책으로 정리되며 오래된 이벤트는 기록 삭제 이후에도 원래 timestamp로 재접수되지 않는다. 미발행 작업은 만료 때문에 삭제되지 않는다.
- inbox 충돌·접수 실패·발행 적체·보관 연장이 민감정보 없는 지표로 확인된다.
- schema runner, API 문서, 핵심 단위 테스트와 정적 검사가 새로운 계약에 맞춰 갱신되어 있고 수동 운영 점검의 수행 여부가 기록되어 있다.
