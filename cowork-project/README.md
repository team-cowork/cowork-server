# cowork-project

## 역할

팀 내 프로젝트·멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 생성·조회·수정·삭제와 순서 변경
- 프로젝트 멤버와 역할 관리
- GitHub 저장소 연결, PR 조회·승인·병합과 라벨 정책 변경 요청

## 스택

- Kotlin / Java 26 / Spring Boot
- Maven (`pom.xml`, `mvnw` / `mvnw.cmd`)
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client / OpenFeign

## 빌드 및 실행

`cowork-project` 디렉터리에서 실행합니다. Windows에서는 `mvnw.cmd`를 사용합니다.

```bash
./mvnw package
./mvnw test
./mvnw clean
```

로컬 서비스는 저장소 루트에서 `bash deploy/local/services/project.sh foreground`로 실행합니다.
이 스크립트는 로컬 환경변수를 읽고 `./mvnw spring-boot:run`을 호출합니다.

루트 Gradle의 `build`, `clean`, `test`에는 이 모듈이 포함되지 않습니다.

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
