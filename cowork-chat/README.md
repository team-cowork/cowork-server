# cowork-chat

## 역할

실시간 채팅과 첨부파일 공유·메시지 검색을 제공합니다.

- 채널 메시지·답글·고정·이모지 반응과 타이핑 알림
- 첨부파일 업로드, DM 목록·숨기기, 사용자 차단과 미읽 카운트
- Elasticsearch 전문 검색과 GraphQL 통합 검색
- GitHub 이슈 생성 슬래시 커맨드와 사용자 알림 발행

## 스택

- TypeScript / Node.js / NestJS
- npm / TypeScript Compiler
- Socket.IO / GraphQL (Apollo)
- MongoDB (Mongoose) / Elasticsearch / Redis / KafkaJS
- SeaweedFS (S3 호환) / AWS SDK

## 포트

| 용도             | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------|---------------|--------------------------|
| HTTP / WebSocket | `8087`        | `8087`                   |

운영 WebSocket 연결은 Gateway의 `/ws/chat`을 사용합니다. 서비스 직접 연결은 운영에 노출하지 않습니다.

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                                | 기본값                      | 설명                                                                   |
|-------------------------------------|-----------------------------|------------------------------------------------------------------------|
| `APP_CONFIG_URL`                    | `http://cowork-config:8761` | 필수 Config Server 연결                                                |
| `APP_PROFILE`                       | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용              |
| `CHAT_PROJECTION_SOURCE_GENERATION` | `1` (앱 기본값)             | Kafka source 교체를 구분하는 운영 세대 값. 필요한 경우 명시적으로 주입 |
| `CHAT_MESSAGE_QUARANTINE_RETENTION_DAYS` | `30` | `chat.message` poison record 원문 보존 기간 (MongoDB TTL) |

- Config Server: 포트, MongoDB 옵션, Elasticsearch, Kafka, Redis, Eureka, S3 endpoint·정책, rate limit.
- Vault: `MONGODB_URI`, `JWT_SECRET`, Discord webhook, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

## `chat.message` quarantine 운영

JSON·이벤트 계약·채널/부모 범위 오류는 `chat_message_quarantine_records`에 저장된 뒤 Kafka offset을 진행합니다. 원문에는 메시지 본문과 첨부 URL이 있을 수 있으므로 MongoDB 접근 권한이 있는 운영자만 취급하고, 애플리케이션 로그와 Discord 경고에는 기록하지 않습니다. 원문은 최대 64KiB이며, 절단된 원문은 재처리할 수 없습니다. 모든 레코드는 최초 격리 시점부터 기본 30일 후 TTL로 삭제됩니다.

운영 셸에서 `MONGODB_URI`를 설정한 뒤 다음 명령을 사용합니다.

```bash
npm run ops:chat-message-quarantine -- list
npm run ops:chat-message-quarantine -- show <recordId>      # 원문 출력: 복사·티켓 첨부 금지
npm run ops:chat-message-quarantine -- reprocess <recordId>
npm run ops:chat-message-quarantine -- discard <recordId>   # 원문과 Kafka key를 즉시 제거
```

`reprocess`는 실행 중인 chat 서비스의 worker가 한 번만 claim해 현재 계약·채널 범위를 다시 검증하고 정상 메시지 처리 경로를 실행합니다. 성공한 레코드는 `REPROCESSED`가 되고 원문과 key가 제거됩니다. 실패하면 `QUARANTINED`로 되돌아가며 원인을 확인한 뒤 다시 요청할 수 있습니다. worker가 비정상 종료되어 `PROCESSING`에 남은 레코드는 2분 후 자동 회수됩니다.
