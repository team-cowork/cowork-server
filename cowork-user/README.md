# cowork-user

## 역할
사용자 관리 서비스.

- 사용자 프로필 조회/수정
- 사용자 검색
- Kafka: 사용자 데이터 변경 이벤트 발행 (`user.data.sync`)

## 스택
TBD (Spring Boot + Java/Kotlin, .NET 등 팀 결정)

## 포트
`8082`

## DB
MySQL — Flyway로 스키마 관리 (`db/migration/V1__init.sql`)
