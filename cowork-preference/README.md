# cowork-preference

## 역할

사용자·팀·채널의 설정과 사용자 정의 역할 정책을 전담 관리합니다.

- 사용자 상태·표시 설정, 팀·채널 설정과 채널별 알림 설정
- 프로젝트 역할과 팀 사용자 정의 역할·권한·멤버 할당
- 채널별 역할의 메시지 읽기 정책과 GitHub 저장소 라벨 정책
- 설정 캐시·상태 만료 처리와 변경 이벤트 발행

## 스택

- Kotlin / Java 26 / Vert.x Coroutines
- Kotlin Toolchain
- Vert.x PostgreSQL Client / PostgreSQL / Flyway
- Vert.x Redis Client / Kafka Client / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `9001`        | `9001`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_PREFERENCE_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_PREFERENCE_PASSWORD`에서 전달 |

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [preference 슬롯](../deploy/prod/slots/preference.json)에서 관리합니다.
[이미지 bootstrap](deploy/bootstrap.sh)이 공통 입력을 이 런타임의 환경변수로 변환합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)
