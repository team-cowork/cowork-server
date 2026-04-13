# cowork-config

## 역할
중앙화된 설정 서버 + 서비스 디스커버리.

- **Spring Cloud Config Server**: 각 서비스의 설정 파일 외부화 제공
- **Eureka Server**: 서비스 등록/조회 (Service Discovery)
- 민감한 값(DB 패스워드, JWT 시크릿 등)은 환경변수 / GitHub Secrets로 주입

## 스택
Spring Boot + Spring Cloud Config Server + Spring Cloud Netflix Eureka Server

## 포트
`8761`

## 설정 파일 위치
- 일반 설정: 레포지토리 내 `/config` 디렉터리 또는 별도 private repo
- 민감 값: 환경변수 (`${ENV_VAR}` 참조)
