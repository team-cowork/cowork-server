# cowork-team

## 역할
팀 관리 서비스.
- 팀 생성/수정/삭제
- 팀 멤버 초대/탈퇴
- 팀 프로필 이미지 (MinIO presigned URL)

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Data JPA + MySQL + Flyway
- Spring Cloud (Eureka, Config, OpenFeign)
- Spring Kafka

## 포트
`8085`

## 의존성
- Eureka, Config Server
- Kafka produce: `notification.trigger`
- MinIO (팀 프로필 이미지)

## 환경변수
| 변수 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL |
| `MYSQL_USER` | MySQL 계정 |
| `MYSQL_PASSWORD` | MySQL 비밀번호 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 브로커 주소 |
| `MINIO_ACCESS_KEY` | MinIO 액세스 키 |
| `MINIO_SECRET_KEY` | MinIO 시크릿 키 |
| `MINIO_INTERNAL_ENDPOINT` | MinIO 내부 엔드포인트 |
| `MINIO_BUCKET` | MinIO 버킷명 |
