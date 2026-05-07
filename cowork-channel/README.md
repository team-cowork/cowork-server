# cowork-channel

## 역할
팀 내 채널 관리 서비스.
- 채널 생성/수정/삭제 (TEXT/VOICE, view_type 분기)
- 채널 멤버 관리, 웹훅/스레드 관리
- 팀/유저 라이프사이클 이벤트 수신 후 채널·멤버십 정리

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Data JPA + MySQL + Flyway
- Spring Cloud (Eureka, Config)
- Spring Kafka

## 포트
`8083`

## 의존성
- Eureka, Config Server
- Kafka consume: `team.lifecycle` (TEAM_DELETED, MEMBER_REMOVED), `user.lifecycle` (USER_DELETED)

## 환경변수
| 변수 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL |
| `MYSQL_USER` | MySQL 계정 |
| `MYSQL_PASSWORD` | MySQL 비밀번호 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 브로커 주소 |
