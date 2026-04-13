# cowork-project

## 역할
프로젝트 관리 서비스.

- 프로젝트 생성/수정/삭제
- 프로젝트 멤버 관리
- Kafka: 프로젝트 이벤트 발행 (`notification.trigger`)

## 스택
TBD (Spring Boot + Java/Kotlin, .NET 등 팀 결정)

## 포트
`8084`

## DB
MySQL — Flyway로 스키마 관리 (`db/migration/V1__init.sql`)
