# cowork-project

## 역할

팀 내 프로젝트·멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 생성·조회·수정·삭제와 순서 변경
- 프로젝트 멤버와 역할 관리
- GitHub 저장소 연결, PR 조회·승인·병합과 라벨 정책 변경 요청

## 스택

- Kotlin / Java 26 / Spring Boot
- Maven (`pom.xml`; Gradle은 Maven 실행 위임)
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client / OpenFeign

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8084`        | `8084`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_CONFIG_PROFILE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_PROJECT_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_PROJECT_PASSWORD`에서 전달 |
