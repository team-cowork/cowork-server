# Config Server 접근 보호

- **서비스**: cowork-config, 인프라, 모든 Config/Eureka Client
- **우선순위**: 🔴 높음
- **현재 상태**: 분산 배포를 위해 포트 `8761`을 외부 인스턴스에서 접근 가능하게 공개했으며, 별도 접근 제한은 적용하지 않음

## 문제

`cowork-config`는 한 프로세스와 포트 `8761`에서 Config Server와 Eureka Server를 함께 제공한다. 여러 인스턴스에 분산 배포된 모듈이 접근할 수 있도록 포트를 모든 네트워크 인터페이스에 공개했지만, 현재 Config API에는 요청 인증이 없다.

Config Server는 Vault에서 조회한 데이터베이스 비밀번호, JWT·세션 서명키, OAuth 자격 증명, SeaweedFS 자격 증명 등을 각 서비스에 전달한다. 따라서 포트에 도달할 수 있는 사용자가 `/{application}/{profile}` 형식의 Config API로 다른 서비스의 설정과 시크릿을 조회할 수 있다.

현재의 전체 인터페이스 공개는 외부 인스턴스 연결을 우선한 임시 결정이다. 연결 범위를 다시 localhost로 축소하지 않고, 인증·전송 암호화·네트워크 정책을 후속 작업으로 적용한다.

## 보호 범위

같은 포트를 공유하는 다음 엔드포인트를 구분해서 정책을 설계해야 한다.

| 범위 | 대표 경로 | 필요한 정책 |
|---|---|---|
| Config API | `/{application}/{profile}` | Config Client 인증, 서비스별 설정 조회 권한 |
| Eureka API | `/eureka/**` | Eureka Client 인증 및 등록·조회 권한 |
| Actuator | `/actuator/**` | 운영자 전용 접근, 불필요한 endpoint 비공개 |

Config API에만 인증을 추가하면서 Eureka Client 기동을 깨뜨리거나, 반대로 Eureka 호환성을 위해 전체 포트를 익명 공개하는 구성이 되지 않도록 엔드포인트별 보안 규칙을 검증한다.

## 할 일

### 인증과 권한

- Config Client와 Config Server 사이에 인증을 적용한다.
- 서비스별 자격 증명을 분리하고, 한 서비스가 다른 서비스의 설정을 조회하지 못하도록 application/profile 단위 권한을 적용한다.
- Eureka Client의 등록·조회 인증 방식을 별도로 결정하고 모든 런타임의 호환성을 확인한다.
- Config Server가 Vault에 접근할 때 사용하는 토큰도 최소 권한 정책과 짧은 수명 또는 안전한 갱신 방식을 적용한다.

### 전송 및 네트워크 보호

- 운영 환경의 Config/Eureka 조회 구간에 TLS 또는 mTLS를 적용한다.
- 인증 적용 후 운영 방화벽이나 보안 그룹에서 허용된 배포 인스턴스만 포트 `8761`에 접근하도록 제한한다.
- 신뢰할 수 없는 외부 네트워크에 Actuator와 Eureka 관리 기능이 노출되지 않도록 경로별 접근 정책을 적용한다.

### 관측과 운영

- 인증 실패, 권한 밖 application/profile 조회, 비정상적인 반복 조회를 시크릿 값 없이 감사 로그로 남긴다.
- 자격 증명 발급·교체·폐기와 장애 시 복구 절차를 문서화한다.
- 인증 방식과 로컬·운영 환경별 접속 방법을 `docs/configuration.md`에 반영한다.

## 검증

- 인증되지 않은 Config API 요청이 거부되는 통합 테스트를 추가한다.
- 한 서비스의 자격 증명으로 다른 서비스의 설정을 조회할 수 없는지 검증한다.
- 정상 자격 증명을 사용하는 Spring Boot, Go, NestJS, Vert.x, Elixir Config Client가 모두 기동하는지 검증한다.
- Eureka 등록·heartbeat·registry 조회가 인증 적용 후에도 정상인지 검증한다.
- 허용된 원격 인스턴스에서는 접속되고 허용되지 않은 네트워크에서는 차단되는지 배포 환경에서 검증한다.
- 로그와 오류 응답에 토큰, 비밀번호, Vault 응답값이 포함되지 않는지 확인한다.

## 완료 조건

- 인증되지 않은 Config Client 요청은 거부된다.
- 한 서비스의 자격 증명으로 다른 서비스의 설정이나 시크릿을 조회할 수 없다.
- 허용되지 않은 외부 네트워크에서는 Config Server와 Eureka 포트에 접근할 수 없다.
- 설정 조회 과정에서 시크릿이 평문 네트워크로 전송되지 않는다.
- 인증을 사용하는 모든 Config/Eureka Client의 기동 검증이 자동화되어 있다.
