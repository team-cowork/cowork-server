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

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [team 슬롯](../deploy/prod/slots/team.json)에서 관리합니다.
[이미지 bootstrap](deploy/bootstrap.sh)이 공통 입력을 이 런타임의 환경변수로 변환합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)
