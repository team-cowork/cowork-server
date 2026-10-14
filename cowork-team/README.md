# cowork-team

## 역할

팀·초대·멤버 권한과 팀 이미지를 관리합니다.

- 팀 생성·조회·수정·삭제와 멤버 초대·참여·제거
- 기본 멤버십 역할 관리와 사용자 정의 역할 조회·변경 요청
- 팀 아이콘 업로드와 GitHub App 설치 연결
- 팀·멤버 상태 변경과 사용자 알림 발행

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client
- Spring Cloud AWS S3 / SeaweedFS (S3 호환)

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8085`        | `8085`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_TEAM_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_TEAM_PASSWORD`에서 전달 |
