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

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| HTTP / WebSocket | `8087` | `8087` |

운영 WebSocket 연결은 Gateway의 `/ws/chat`을 사용합니다. 서비스 직접 연결은 운영에 노출하지 않습니다.

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `APP_PROFILE` | `local` | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |
| `CHAT_PROJECTION_SOURCE_GENERATION` | `1` (앱 기본값) | Kafka source 교체를 구분하는 운영 세대 값. 필요한 경우 명시적으로 주입 |

- Config Server: 포트, MongoDB 옵션, Elasticsearch, Kafka, Redis, Eureka, S3 endpoint·정책, rate limit.
- Vault: `MONGODB_URI`, `JWT_SECRET`, Discord webhook, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
