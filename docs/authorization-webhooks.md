# DataGSM 웹훅 접수와 복구

`POST /api/authorization/events/datagsm`은 서명을 검증한 `student.updated` 이벤트를 받는다. `cowork-authorization`은 배치 전체를 `tb_webhook_inbox`와 `tb_kafka_outbox`에 하나의 MySQL transaction으로 저장한다. HTTP 요청에서는 Kafka를 호출하지 않는다.

## 응답 계약

| HTTP | 응답 | 의미 |
|---|---|---|
| `200` | `{"status":"accepted"}` | 배치 전체의 영속 접수를 완료했다. Kafka 발행과 학생 정보 반영은 비동기다. |
| `200` | `{"status":"duplicate"}` | 접수 기한 안의 동일 ID·동일 내용이다. 발행 대기 데이터를 추가하지 않는다. |
| `200` | `{"status":"ignored"}` | 서명과 envelope가 유효하지만 지원하지 않는 이벤트 종류다. 저장하지 않는다. |
| `400` | `invalid_payload` | 전체 배치를 거부했다. 일부 항목만 접수하지 않는다. |
| `400` | `event_expired` | 지원 이벤트의 발생 시각이 DB 현재 시각보다 30일 이상 오래되었다. |
| `400` | `invalid_event_timestamp` | 지원 이벤트의 발생 시각이 DB 현재 시각보다 5분 초과 미래다. |
| `401` | `invalid_signature` | 원본 body의 HMAC-SHA256 서명이 일치하지 않는다. |
| `409` | `event_id_conflict` | 보관 중인 ID의 정규화한 내용과 다르다. 최초 기록은 유지한다. |
| `413` | `payload_too_large` | 본문이 1 MiB를 초과한다. 잘린 본문을 처리하지 않는다. |
| `503` | `temporarily_unavailable` | DB transaction의 성공을 확인하지 못했다. 같은 ID·내용·발생 시각으로 재시도한다. |
| `503` | `webhook_not_configured` | 서명 검증 secret이 설정되지 않았다. |

오류 응답은 `{"error":"오류 코드"}` 형식이다. commit 후 응답 연결이 끊긴 경우에도 재전달은 동일 inbox로 판정한다. 같은 이벤트에 새 timestamp를 붙이거나 ID를 재사용하지 않는다.

## 입력과 중복 판단

- `id`, `event`, RFC3339 `timestamp`와 비어 있지 않은 `data.new[]`가 필요하다. ID는 앞뒤 공백 없이 최대 255바이트이고 바이트 단위로 구분한다.
- 각 항목의 `index`는 명시적인 0 이상의 signed 64-bit 정수다. 배치 안에서 index와 실제 변경 대상 `student_id`는 각각 중복될 수 없다.
- 필수 학생 필드와 선택 필드의 타입·길이를 `cowork-user`의 계약과 DB 컬럼에 맞춰 검사한다. `student_id`는 양의 signed 64-bit 정수다.
- `{}`인 `object`는 변경 없는 항목이다. `null`이나 누락은 거부한다. 모든 항목이 `{}`이면 메시지 없이 inbox만 접수한다.
- 중복 JSON key, 잘못된 UTF-8, 64단계를 초과하는 중첩, 필드 타입 오류와 잘못된 항목 하나는 배치 전체를 거부한다.
- 해시는 이벤트 종류·원래 정밀도의 UTC 발생 시각·index로 정렬한 학생 변경을 대상으로 한다. JSON 공백·객체 키 순서·배열 나열 순서·동일 시각의 timezone 표기는 해시를 바꾸지 않는다. 실제로 반영하지 않는 `data.old`와 부가 필드는 제외한다.
- Kafka key는 `student_id`, 항목 식별자는 `(event_id, event_index)`다. 저장된 key와 payload를 relay가 그대로 재사용한다.

## 보관과 실패 경계

접수 transaction의 DB UTC 시각을 `accepted_at`으로 사용한다. 접수 가능한 시각은 `현재 시각 - 30일 < timestamp <= 현재 시각 + 5분`이다. `expires_at`은 `max(accepted_at, timestamp) + 30일`을 microsecond로 올림한 값이다. 중복 요청은 만료를 연장하지 않는다.

정리는 시작 시와 매시간 수행하며 회당 최대 20개 batch, batch당 500개 inbox를 삭제한다. 만료된 기록 중 미발행 outbox가 없는 기록만 삭제하고 DB 외래 키도 참조 중인 inbox 삭제를 차단한다. 정리 실패는 다음 주기에 재시도한다. 이미 접수한 outbox는 30일이 지났어도 계속 발행한다.

보관 종료 뒤 원래 timestamp로 재전달된 이벤트는 오래된 요청으로 거부한다. 삭제한 ID를 새로운 timestamp로 재사용하는 행위까지 유한한 inbox로 감지하지는 않는다.

relay는 at-least-once로 발행한다. publish 후 outbox 삭제가 commit되기 전에 종료되면 같은 메시지가 다시 전달될 수 있다. `cowork-user`는 계정을 잠그고 `datagsm_updated_at`보다 새로운 변경만 반영하며 계정 갱신과 프로필 outbox를 함께 commit한다. 미가입 학생은 기존 정책대로 skip하고 업무 제약 충돌은 소비자의 영속 격리에 남긴다. `200 accepted`가 모든 학생의 적용 성공을 의미하지 않는다.

## 관측과 운영 점검

지표 이름의 공통 접두사는 `cowork_authorization_webhook_`이다.

| 지표 | 확인할 상태 |
|---|---|
| `requests_total{result}` | `accepted`, `duplicate`, `ignored`, `invalid`, `expired`, `conflict`, `unavailable`별 요청 수 |
| `outbox_pending`, `outbox_oldest_seconds` | 발행 적체와 가장 오래 기다린 시간 |
| `outbox_completed_total`, `outbox_publish_failures_total` | DB에서 발행 완료로 확정한 수와 전송 실패 횟수 |
| `inbox_expired`, `inbox_retained` | 정리를 기다리는 만료 기록과 미발행 outbox 때문에 보존한 만료 기록 |
| `inbox_deleted_total`, `maintenance_failures_total{operation}` | 정리 결과와 `cleanup`·`observe` 실패 |
| `observation_success`, `observation_timestamp_seconds` | backlog 지표 조회의 성공 여부와 마지막 성공 시각 |

공유 DB를 조회하는 gauge는 replica 값을 합산하지 않고 최신의 성공한 관측값을 선택하거나 `max`로 집계한다. 관측이 실패하면 마지막 수치를 정상 최신값으로 해석하지 않는다. 프로세스별 counter는 정상적인 `rate`·`sum`으로 집계한다. 이벤트 ID·서명·학생 개인정보·payload는 로그와 metric label에 넣지 않는다.

- `unavailable` 증가 시 DB 연결·잠금·기동 시 migration 결과를 확인한다. 접수와 정리의 DB 작업은 각각 10초 timeout을 사용한다.
- 발행 적체 시 Kafka 연결과 기존 relay의 성공 여부를 확인한다. outbox row를 삭제하거나 timestamp를 변경해 접수 제한을 우회하지 않는다.
- `inbox_retained`는 미발행 작업의 보호 상태다. 발행이 완료되면 다음 정리 주기에 삭제 대상으로 돌아온다.
- `409`는 동일 ID의 내용 변경이므로 발신자 계약을 확인한다. 원본 inbox를 덮어쓰지 않는다.
- 소비자에 도착했지만 반영되지 않은 변경은 `cowork-user`의 action quarantine과 업무 제약·미가입 학생 정책을 확인한다. Kafka 자체의 데이터 유실이나 보존 기간 초과를 inbox만으로 재구성할 수는 없다.

## 스키마와 검증 범위

`V9__add_webhook_inbox.sql`은 새 inbox, outbox source identity unique·참조·check 제약을 정의하고 이전 `tb_processed_events`를 제거한다. 기존 처리 기록의 backfill이나 구버전 병행 운영은 지원하지 않는다. migration runner는 새 버전의 컬럼과 제약을 확인하고 중단된 DDL 적용을 재개한다.

핵심 입력·서명·접수 시각 정책의 단위 테스트, `go build`, `go vet`, `golangci-lint`와 Swagger 생성을 수행한다. 실제 MySQL migration, 동시 요청과 commit 경계 종료, Kafka 중단·복구, 만료 데이터 정리는 이 변경에서 실행하지 않았다. 자동화 통합·회귀 테스트는 작성하거나 실행하지 않는다.
