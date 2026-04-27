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

## 코드 탐색 가이드

- **Gateway 필터/인증**: `cowork-gateway/src/main/kotlin/**/filter/`
- **Kafka 토픽**: 각 서비스 `src/main/resources/application.yml` → `kafka.topics`
- **DB 스키마**: Spring 서비스 `src/main/resources/db/migration/*.sql`, MongoDB 서비스 `schema/*.md`
- **서비스 간 호출**: 각 서비스 `**/client/` 또는 `**/infrastructure/` 디렉터리

