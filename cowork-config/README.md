# cowork-config

## 역할

서비스 설정과 서비스 디스커버리를 중앙에서 제공합니다.

- Config Server를 통한 서비스별 일반 설정·시크릿 공급
- Eureka 서비스 등록·조회
- Kafka Config Bus를 통한 설정 갱신 이벤트 전달

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Cloud Config Server / Eureka Server
- Spring Cloud Bus (Kafka) / Spring Vault

## 포트

| 용도                   | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------------|---------------|--------------------------|
| Config Server / Eureka | `8761`        | `8761`                   |

호스트 포트는 `COWORK_CONFIG_HOST_PORT`로 변경할 수 있습니다.

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml) 기준입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_ACCOUNTS_JSON` | 없음 | 필수. 서비스별 Config/Eureka 계정 목록 |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Config Bus 브로커 |
| `VAULT_HOST` | `cowork-vault` | 로컬 Vault 호스트 |
| `S3_INTERNAL_ENDPOINT` | `http://seaweedfs:9000` | 서비스 내부 S3 endpoint |
| `PUBLIC_API_BASE_URL` | `http://localhost:8080` | 외부 API 기준 주소 |
| `PUBLIC_WEB_ORIGINS` | `http://localhost:3000,http://localhost:3001,http://localhost:5173` | CORS·WebSocket·OAuth 복귀 origin 목록 |
| `LIVEKIT_URL` | `http://cowork-livekit:7880` | 내부 LiveKit API 주소 |
| `LIVEKIT_WS_URL` | `ws://localhost:7880` | 클라이언트 LiveKit WebSocket 주소 |

서비스 일반 설정은 [configs/](src/main/resources/configs/), 앱 비밀 값은 Vault에서 공급합니다.
로컬 `vault-init`은 `.env`의 비밀 값을 적재합니다.

운영 주소·프로파일·비밀 참조는 [Config 슬롯](../deploy/prod/slots/config.json)에서 관리합니다.
[이미지 bootstrap](deploy/bootstrap.sh)이 공통 입력을 Spring 환경변수로 변환합니다.
운영 Vault는 HTTPS·AppRole을 사용하며, 계정과 AppRole 자격 증명은 Vault `secret/control-plane/prod/`에서 참조합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)
