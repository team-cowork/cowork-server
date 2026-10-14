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

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_PROFILE` | `local` | Compose의 `SPRING_PROFILES_ACTIVE`에서 전달 |
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_CHAT_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_CHAT_PASSWORD`에서 전달 |

운영 WebSocket은 Gateway의 `/ws/chat`을 사용합니다.
