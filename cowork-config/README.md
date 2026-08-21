# cowork-config

## 역할

모든 서비스가 가장 먼저 의존하는 중앙 설정·서비스 디스커버리 서버입니다.

- Spring Cloud Config Server: 서비스별 설정 제공
- Eureka Server: 서비스 등록과 조회
- Spring Cloud Bus: Kafka를 통한 설정 갱신 이벤트 전달
- Actuator/Prometheus: 상태와 메트릭 제공

## 설정 소스

| 프로파일 | 설정 소스                                                  |
|----------|------------------------------------------------------------|
| `local`  | Compose 인메모리 Vault + `src/main/resources/configs/`      |
| `prod`   | 외부 Vault + `src/main/resources/configs/`                 |

비밀 값은 Vault에서, 비밀이 아닌 공통 값은 native 설정에서 공급합니다.

프로파일은 `local`과 `prod` 둘뿐이며, 각 프로파일에 Gateway와 10개 business service의 설정 파일이 모두 존재합니다. Config Client가 적용하는 우선순위는 `직접 환경변수 > overrides > Vault > native > 애플리케이션 기본값`입니다.

Config Server는 응답 문자열의 `${VAR}`를 해석하지 않습니다. Go·NestJS·Elixir 서비스는 클라이언트에서도 해석하지 않으므로 해당 서비스 설정에는 리터럴 값을 쓰고 Vault나 `overrides`로 덮어씁니다.

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Cloud Config Server + Eureka Server
- Spring Cloud Bus(Kafka), Spring Vault

## 포트와 운영 엔드포인트

- 포트 및 Eureka UI: `8761`
- Health: `/actuator/health`
- Prometheus: `/actuator/prometheus`
- Config 조회: `/{application}/{profile}`

## 의존성

- Kafka: Config Bus
- Vault: `local`, `prod` 프로파일

이 서비스가 준비된 뒤 Gateway와 비즈니스 서비스를 기동합니다.

## 주요 환경 변수

| 변수                                                             | 설명                           |
|------------------------------------------------------------------|--------------------------------|
| `SPRING_PROFILES_ACTIVE`                                         | 설정 소스 프로파일(기본 `local`) |
| `KAFKA_BOOTSTRAP_SERVERS`                                        | Config Bus Kafka 브로커        |
| `VAULT_HOST` / `VAULT_PORT` / `VAULT_SCHEME` / `VAULT_TOKEN`     | Vault 연결 정보                |

로컬 `vault-init`은 `.env`의 시크릿을 `secret/application`과 `secret/cowork-*`에 저장합니다. 운영에서는 로컬 Vault 초기화 컨테이너를 사용하지 않으며 외부 Vault를 배포 전에 준비해야 합니다.
