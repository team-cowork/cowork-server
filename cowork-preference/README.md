# cowork-preference

## 역할
사용자 알림 및 상태 설정 관리 서비스.
- 알림 설정 (채널별, 팀별)
- 계정 상태(온라인/자리비움 등) 관리 및 만료 처리
- 팀/프로젝트 역할 설정 조회

## 스택
- Kotlin + Vert.x (Coroutines)
- PostgreSQL + Flyway
- Redis (설정 캐시)
- Spring Kafka
- Eureka Client

## 포트
`9001`

## 의존성
- Eureka, Config Server
- Kafka produce: `preference.status.changed`, `preference.team.setting.changed`
- Redis (캐시)

## 환경변수
| 변수 | 설명 |
|---|---|
| `preference.db.host` | PostgreSQL 호스트 |
| `preference.db.username` | DB 계정 |
| `preference.db.password` | DB 비밀번호 |
| `preference.redis.host` | Redis 호스트 |
| `preference.kafka.bootstrap-servers` | Kafka 브로커 주소 |
