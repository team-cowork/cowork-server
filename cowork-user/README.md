# cowork-user

## 역할
사용자 계정/프로필 관리 서비스.
- 사용자 계정/프로필 조회 및 수정
- MinIO 기반 프로필 이미지 presigned URL 발급
- Kafka `user.data.sync` 이벤트 소비

## 스택
- Elixir
- MySQL + Flyway

## 포트
`8082`

## 의존성
- Eureka, Config Server
- Kafka consume: `user.data.sync`
- MinIO (프로필 이미지)

## 환경변수
| 변수 | 설명 |
|---|---|
| `DATABASE_URL` | MySQL URL |
| `DB_USERNAME` | MySQL 계정 |
| `DB_PASSWORD` | MySQL 비밀번호 |
| `APP_CONFIG_URL` | Config Server URL |
| `APP_PROFILE` | 활성 프로파일 |
