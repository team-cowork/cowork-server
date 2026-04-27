# Cowork Server

## Overview

GSM(광주소프트웨어마이스터고) 내부 팀 협업 플랫폼 백엔드. 폴리글랏 MSA 모노레포.  
Tech stack: Spring Cloud Gateway, Eureka, OpenFeign, Kafka, Flyway, MySQL, MongoDB, PostgreSQL, Redis, LiveKit, MinIO

## Services

| 서비스 | 언어/프레임워크 | 포트 | 역할 |
|---|---|---|---|
| `cowork-config` | Kotlin / Spring Boot | 8761 | Eureka + Config Server (단일 프로세스) |
| `cowork-gateway` | Kotlin / Spring WebFlux | 8080 | JWT 검증, 라우팅, Rate Limit, Circuit Breaker |
| `cowork-authorization` | Go / Gin | 8081 | DataGSM OAuth2, JWT 발급, Refresh Token 관리 |
| `cowork-user` | Kotlin / Spring Boot | 8082 | 사용자 프로필, MinIO 이미지 업로드 |
| `cowork-channel` | Kotlin / Spring Boot | 8083 | 채널 관리 (text/voice) |
| `cowork-team` | Kotlin / Spring Boot | 8085 | 팀/팀원 관리, MinIO 아이콘 업로드 |
| `cowork-notification` | Go / chi | 8086 | FCM 푸시 알림 (Kafka 소비) |
| `cowork-preference` | Kotlin / Vert.x | 9001 | 알림/리소스 설정 (PostgreSQL, **Not Spring**) |
| `cowork-chat` | TypeScript / NestJS | 3000 | 실시간 채팅 (Socket.io, MongoDB) |
| `cowork-voice` | Go / chi | 9000 | 음성 채널 (LiveKit, MongoDB) |

## Architecture Decisions

- **Gateway 중심 인증**: JWT는 Gateway에서만 검증. 하위 서비스는 `X-User-Id`(Long), `X-User-Role`(String) 헤더만 신뢰.
- **cowork-config 단일 장애점**: Eureka + Config Server가 동일 프로세스(8761). 반드시 가장 먼저 기동.
- **Authorization → User 동기 호출**: 로그인 시 auth가 user 서비스를 HTTP로 직접 호출(`PUT /users/{userId}`). `user.data.sync` Kafka 토픽은 미래 예정.
- **DB 분리**: auth(MySQL), user/channel/team(MySQL), preference(PostgreSQL), chat/voice(MongoDB). 서비스 간 FK 없음.
- **Vert.x 예외**: `cowork-preference`는 Spring Boot가 아님. Config Server를 HTTP로 직접 파싱해 사용.

## Kafka Topics

| 토픽 | 프로듀서 | 컨슈머 |
|---|---|---|
| `user.data.sync` | authorization (예정) | cowork-user |
| `notification.trigger` | cowork-team, cowork-chat | cowork-notification |
| `chat.message` | cowork-chat | cowork-chat (self, WebSocket 팬아웃) |
| `channel.member.event` | (미정) | cowork-chat |
| `preference.status.changed` | cowork-preference | (미정) |

