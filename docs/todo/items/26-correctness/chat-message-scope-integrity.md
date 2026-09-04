# 채팅 메시지 채널·프로젝트·부모 범위 무결성 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 메시지의 프로젝트와 부모 범위를 클라이언트 값에 의존하며 채널 projection과의 일치 여부를 검증하지 않음

## 문제

`cowork-chat/src/chat/chat.service.ts`의 `ChatService.sendMessage`는 `POST /api/chat/chat/channels/:channelId/messages`에서 요청자의 채널 멤버십을 확인한다. DM이면 `teamId`와 `projectId`를 `null`로 덮어쓰고, 그 외 채널은 `teamId`만 멤버십 projection 값으로 교체한다. 비 DM 채널의 `projectId`는 `SendMessageDto`로 받은 클라이언트 값을 그대로 `chat.message` 이벤트에 넣는다.

`ChatMessageConsumer.handleMessageEvent`도 이벤트의 `teamId`, `projectId`, `channelId` 관계를 채널 projection과 대조하지 않고 MongoDB 메시지에 저장한다. 따라서 프로젝트 채널 메시지의 `projectId`를 누락하거나 다른 프로젝트 ID로 지정할 수 있고, 이후 Elasticsearch 색인·검색 범위도 잘못된 값에 의존하게 된다.

답장 대상 `parentMessageId`는 `SendMessageDto`에서 MongoDB ObjectId 형식만 검증한다. 부모 메시지의 존재 여부와 같은 `channelId` 소속 여부를 확인하지 않으며, `MessageRepository.findMessages`의 `$lookup`도 `_id`만으로 부모를 조인한다. 유효한 다른 채널 메시지 ID를 전달하면 현재 채널 메시지 응답의 `mentionedMessage`에 다른 채널의 부모 내용과 작성자 정보가 결합될 수 있다.

## 저장 무결성 계약

| 필드 | 신뢰 원천 | 저장 규칙 | 불일치 처리 |
|------|-----------|-----------|---------------|
| `channelId` | URL과 활성 채널 projection | 접근 권한을 확인한 채널 ID를 사용함 | 채널이 없거나 삭제되었으면 거부함 |
| `teamId` | 채널·멤버십 projection | DM은 `null`, 그 외에는 채널의 팀 ID를 사용함 | 클라이언트 값과 무관하게 서버 값만 사용함 |
| `projectId` | 채널 projection | 프로젝트 채널은 projection의 프로젝트 ID, 그 외에는 `null`을 사용함 | 클라이언트 값 누락·변조가 저장 범위를 바꾸지 못함 |
| `parentMessageId` | MongoDB 부모 메시지 | 부모가 존재하고 동일한 `channelId`에 속할 때만 저장함 | 형식 오류·미존재·다른 채널이면 요청을 거부함 |

클라이언트가 보내는 `teamId`와 `projectId`를 API 계약에서 제거할지, 전환 기간 동안 허용하되 서버 값과 다르면 거부할지 결정한다. 어떤 전환 방식을 선택해도 최종 저장값과 Kafka 이벤트는 서버가 확인한 채널 범위만 사용한다.

## 할 일

### 쓰기 경로

- `ChatService.sendMessage`가 `ChannelProjectionRepository.findById`로 채널의 팀·프로젝트·타입을 조회하고 권한 검사 결과와 함께 단일한 메시지 범위를 구성하게 한다.
- `SendMessageDto`, Swagger, `ChatMessageEvent`에서 클라이언트 소유가 아닌 범위 필드의 제거 또는 폐기 일정을 정의한다.
- `parentMessageId`가 있으면 `MessageRepository.findByIdAndChannelId`로 동일 채널 부모의 존재를 Kafka 발행 전에 검증한다.
- 중첩 답장을 허용할지 최상위 메시지만 부모가 될 수 있는지 정책을 확정하고 저장 전에 적용한다.
- `ChatMessageConsumer`에도 이벤트 구조와 채널 범위를 검증하는 방어선을 두고 잘못된 내부 이벤트를 저장하지 않는다.

### 읽기와 기존 데이터

- `MessageRepository.findMessages`의 부모 조인이 현재 메시지의 `channelId`와 같은 부모만 반환하도록 제한한다.
- `NotificationOutboxPoller`의 부모 작성자 조회도 메시지 채널 범위를 함께 검증하게 한다.
- 기존 메시지에서 채널 projection과 `teamId`·`projectId`가 다른 문서 및 다른 채널 부모를 가리키는 문서를 점검한다.
- 발견된 기존 불일치 문서의 정정·부모 해제·격리 정책을 정하고 Elasticsearch 재색인 범위에 반영한다.

## 검증

- 프로젝트·팀·DM 채널의 `teamId`·`projectId` 결정 규칙을 `ChatService`와 범위 validator 단위 테스트로 검증한다.
- 다른 채널의 `parentMessageId`, 존재하지 않는 부모, 잘못된 ID 형식을 거부하는 핵심 규칙을 단위 테스트로 검증한다.
- 부모 조인과 알림 조회가 `channelId` 조건을 포함하는지는 repository query를 정적으로 점검하고 기존 데이터 audit로 확인한다.
- 채널 삭제·멤버십 회수와 event 전달 경합은 운영 지표와 데이터 점검 대상으로 두며 Kafka 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 메시지의 `teamId`와 `projectId`는 클라이언트 입력이 아니라 활성 채널 projection에서 결정되어 있다.
- 서로 다른 채널의 메시지 사이에는 부모·답장 관계가 생성되지 않는다.
- 다른 채널의 부모 메시지 내용이나 작성자 정보가 메시지 응답과 알림 처리에 노출되지 않는다.
- 기존 범위 불일치 데이터를 탐지하고 정리하는 절차가 마련되어 있다.
- 채널·프로젝트·부모 범위의 핵심 비즈니스 불변식이 서비스와 validator 단위 테스트로 보호되어 있다.
