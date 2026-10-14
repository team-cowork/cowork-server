# cowork-roadmap

## 역할

전공·포지션별 온보딩 로드맵과 학습 과제를 관리합니다.

- 공통·팀·프로젝트 범위의 로드맵 생성·조회·수정·삭제
- 트리형 노드와 문서·관련 자료 관리
- 멤버별 과제 출제와 진행 상태 관리

## 스택

- Java 26 / Spring Boot WebFlux
- Gradle
- Spring Data R2DBC / MySQL / Flyway (JDBC)
- Spring Kafka / Eureka / Config Client

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8088`        | `8088`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_ROADMAP_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_ROADMAP_PASSWORD`에서 전달 |

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [roadmap 슬롯](../deploy/prod/slots/roadmap.json)에서 관리합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)
