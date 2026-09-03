# cowork-config

## 역할

서비스 설정과 서비스 디스커버리를 중앙에서 제공합니다.

- Config Server를 통한 서비스별 일반 설정·시크릿 공급
- Eureka 서비스 등록·조회
- Kafka Config Bus를 통한 설정 갱신 이벤트 전달

## 스택

- Kotlin / Java 25 / Spring Boot
- Gradle
- Spring Cloud Config Server / Eureka Server
- Spring Cloud Bus (Kafka) / Spring Vault

## 포트

| 용도                   | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------------|---------------|--------------------------|
| Config Server / Eureka | `8761`        | `8761`                   |

호스트 포트는 `COWORK_CONFIG_HOST_PORT`로 변경할 수 있습니다.

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                                        | 기본값                                | 설명                                                   |
|---------------------------------------------|---------------------------------------|--------------------------------------------------------|
| `SPRING_PROFILES_ACTIVE`                    | `local`                               | 설정 프로파일 (`local` 또는 `prod`)                    |
| `KAFKA_BOOTSTRAP_SERVERS`                   | `kafka:9092`                          | Config Bus Kafka 브로커                                |
| `VAULT_HOST`                                | `cowork-vault`                        | Vault 호스트. 운영에서는 외부 Vault 주소 필수          |
| `VAULT_PORT`                                | `8200` (앱 기본값)                    | Vault 포트                                             |
| `VAULT_SCHEME`                              | `http` (local 앱 기본값)              | local에서만 변경 가능. prod는 `https` 고정             |
| `VAULT_TOKEN`                               | 로컬 개발 토큰 (앱 기본값)            | prod에서는 외부 Vault 토큰 필수                        |
| `COWORK_CONFIG_HOST_PORT`                   | `8761`                                | Compose 호스트 공개 포트                               |
| `S3_INTERNAL_ENDPOINT`                      | `http://seaweedfs:9000`               | 서비스 내부 S3 endpoint                                |
| `S3_PUBLIC_ENDPOINT`                        | `http://localhost:9000`               | 클라이언트가 접근하는 S3 endpoint. 운영 주소 지정 필요 |
| `S3_PUBLIC_BASE_URL`                        | `http://localhost:9000/cowork-bucket` | 공개 파일 URL 기준 주소. 운영 주소 지정 필요           |
| `LIVEKIT_URL`                               | `http://cowork-livekit:7880`          | 서비스 내부 LiveKit API endpoint                       |
| `LIVEKIT_WS_URL`                            | `ws://localhost:7880`                 | 클라이언트 LiveKit WebSocket 주소. 운영 주소 지정 필요 |
| `PUBLIC_WEB_ORIGIN` / `PUBLIC_API_BASE_URL` | 없음                                  | prod에서 필수. Config Server 컨테이너에 별도 주입      |
| `GITHUB_APP_SERVICE_URL`                    | 없음                                  | prod에서 필수. Config Server 컨테이너에 별도 주입      |

일반 설정은 [configs/](src/main/resources/configs/), 시크릿은 Vault에서 공급합니다. 로컬 `vault-init`은 `.env`의 시크릿을 Vault에 적재하며, 운영에서는 외부 Vault를 미리 준비해야 합니다.

위 prod 필수값과 외부 Vault 토큰은 기본 Compose가 전달하지 않으므로 배포 환경에서 추가로 주입해야 합니다. 설정 우선순위와 프로파일별 상세 규칙은 [설정 가이드](../docs/configuration.md)를 참고합니다.
