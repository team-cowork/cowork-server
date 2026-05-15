# cowork-config

## 역할
중앙화된 설정 서버 + 서비스 디스커버리.
- Spring Cloud Config Server: 각 서비스 설정 파일 외부화
- Eureka Server: 서비스 등록/조회

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Cloud Config Server + Eureka Server
- Spring Cloud Bus (Kafka)
- Spring Vault

## 포트
`8761`

## 의존성
없음 — 가장 먼저 기동해야 함
