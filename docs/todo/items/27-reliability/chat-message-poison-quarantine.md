# chat.message poison record 격리

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: `chat.message`의 유효한 JSON이 이벤트 계약을 위반하면 동일 offset에서 consumer 프로세스 재시작이 반복될 수 있음

## 문제

`cowork-chat/src/chat/kafka/chat-message.consumer.ts`의 `ChatMessageConsumer.onModuleInit`은 `chat.message` 값을 `JSON.parse`한 뒤 타입 단언만 하고 `ChatMessageConsumer.handleMessageEvent`에 전달한다. 런타임에서 `eventType`, Kafka key, ID 범위, 본문 타입·길이, 첨부 구조, 발생 시각을 검증하는 계약 검사가 없다.

JSON 문법 오류는 `SyntaxError`로 구분해 로그를 남기고 건너뛴다. 그러나 문법상 유효한 JSON에서 `content`가 문자열이 아니거나 필수 ID가 누락되는 등 구조가 잘못되면 `matchAll` 호출이나 Mongoose 저장에서 다른 예외가 발생하고, 이 예외는 consumer 실행 Promise까지 전파된다.

consumer 실행 실패 경로는 `process.exit(1)`로 프로세스를 종료한다. 실패한 offset은 커밋되지 않으므로 재시작한 consumer가 같은 record를 다시 읽고 다시 종료할 수 있다. projection consumer에는 `applyProjectionMessage`와 `projection_quarantine_records`를 통한 durable quarantine이 있지만 일반 `chat.message` 경로에는 동일한 격리·관측 수단이 없다.

## 오류 분류와 처리 정책

| 오류 종류 | 예시 | offset 처리 | 보존·복구 정책 |
|-----------|------|-------------|----------------|
| JSON 문법 오류 | 잘린 JSON, 잘못된 인코딩 | durable quarantine 성공 후 전진함 | 원문과 위치, 사유를 보존함 |
| 이벤트 계약 오류 | 필수 필드 누락, key 불일치, 잘못된 ID·첨부·시각 | durable quarantine 성공 후 전진함 | producer 수정과 선택적 재처리가 가능하게 함 |
| 메시지 범위 오류 | 채널·프로젝트·부모 불일치 | durable quarantine 성공 후 전진함 | 보안 이벤트로 별도 경고함 |
| 일시적 런타임 오류 | MongoDB 연결 실패, 저장 timeout | offset을 유지함 | 같은 record를 재시도함 |
| quarantine 저장 실패 | MongoDB 쓰기 실패 | offset을 유지함 | 원문 유실 없이 격리 저장부터 재시도함 |

격리 레코드는 최소한 consumer group, topic, partition, offset, Kafka key, 제한된 크기의 원문, 계약 버전, 실패 사유, 최초 관측 시각을 가진다. payload에 메시지 본문과 첨부 URL이 포함되므로 접근 권한, 보존 기간, 로그 마스킹을 함께 정의한다.

## 할 일

### 계약 검증

- `ChatMessageEvent`를 `unknown`에서 검증된 이벤트로 변환하는 런타임 validator를 추가한다.
- `eventType`, Kafka key와 `channelId` 일치, safe integer ID, `teamId`·`projectId` nullable 규칙, 본문 타입·길이, 메시지 타입, 첨부 배열, `parentMessageId`, `clientMessageId`, `occurredAt`을 검증한다.
- producer와 consumer가 같은 이벤트 계약과 계약 버전을 참조하도록 계약 정의를 공통화한다.
- 채널·프로젝트·부모 범위 검증 실패를 일시적 저장소 오류와 구분 가능한 계약 오류로 변환한다.

### durable quarantine

- projection 전용 격리 저장소를 일반 Kafka record에도 확장할지 `chat.message` 전용 collection을 둘지 결정한다.
- `(groupId, topic, partition, offset)` 유니크 키로 격리 저장을 멱등하게 만든다.
- 격리 저장이 성공한 뒤에만 처리 함수를 정상 반환해 Kafka offset이 전진하게 한다.
- 격리 건수, 마지막 발생 위치, 사유별 건수를 노출하고 운영 경고를 과도한 반복 없이 발송한다.
- 원문 조회·재처리·폐기 절차와 보존 기간을 문서화한다.

## 검증

- 이벤트 validator가 필수 필드, ID, 본문, 첨부, key 일치 조건을 검사하는지 계약 정의와 호출 경로를 정적으로 점검한다.
- 채널·프로젝트·부모 범위를 벗어난 메시지를 거부하는 핵심 비즈니스 규칙은 범위 validator 단위 테스트로 검증한다.
- `(groupId, topic, partition, offset)` unique 제약과 offset 전진 조건을 schema·consumer 흐름에서 확인한다.
- poison record, quarantine 저장 실패, MongoDB 일시 오류, 후속 정상 record 결과는 metric과 운영 rehearsal로 확인한다.
- Kafka, MongoDB, quarantine 멱등성, offset 재시도, Socket.IO 전달을 고정하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- `chat.message`의 JSON 및 계약 오류는 원문이 durable하게 보존된 뒤 건너뛴다.
- 하나의 poison record 때문에 `cowork-chat` 프로세스가 같은 offset에서 재시작을 반복하지 않는다.
- 일시적 저장소 오류는 격리되지 않고 offset을 유지한 채 재시도된다.
- 격리 레코드의 위치·사유·원문을 권한 있는 운영자가 추적하고 재처리할 수 있다.
- 정상 record의 저장과 실시간 전달은 앞선 poison record 이후에도 계속된다.
