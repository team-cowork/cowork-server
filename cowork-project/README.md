# cowork-project

## 역할
프로젝트 관리 서비스.
- 팀 내 프로젝트 생성/수정/삭제
- 멤버 역할 관리 (OWNER / EDITOR / VIEWER)

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Data JPA + MySQL + Flyway
- Spring Cloud (Eureka, Config)
- Spring Kafka

## 포트
`8084`

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
