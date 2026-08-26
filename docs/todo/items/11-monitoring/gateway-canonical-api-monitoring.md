# Gateway canonical API 계약 모니터링

- **서비스**: cowork-gateway, cowork-monitoring, Prometheus, Blackbox Exporter, Grafana, Alertmanager
- **우선순위**: 🟠 중간
- **파생 원본**: [외부 API 모듈 네임스페이스 통일](../10-api/public-route-namespace-migration.md)
- **선행 작업**: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./metrics-collection-recovery-and-health-dashboard-removal.md)
- **현재 상태**: 내부 health·metrics 수집 구성이 존재하지만 실제 메트릭 수집 장애를 분석 중이며, canonical Gateway API의 end-to-end 도달성도 별도로 감시하지 않음

## 문제

현재 Prometheus는 Eureka metadata의 `prometheus.path`를 사용해 각 서비스의 `/actuator/prometheus` 또는 `/metrics`를 직접 수집하도록 구성되어 있다. 다만 실제 수집 장애가 있으므로 이 문서에서는 수집이 정상이라고 전제하지 않으며, 선행 TODO에서 discovery부터 Grafana query까지 실패 구간과 근본 원인을 먼저 확인한다. Blackbox Exporter와 Compose healthcheck도 서비스 포트의 `/actuator/health` 또는 `/health`를 직접 호출한다.

메트릭 수집을 복구하더라도 이 구성만으로는 Gateway의 route predicate·`StripPrefix`·보안 matcher·CORS·downstream 연결 오류를 식별할 수 없다. 따라서 canonical 외부 API가 실패해도 서비스가 정상으로 표시될 수 있다. `/api/health`도 Eureka 등록 상태를 집계하므로 개별 canonical 경로의 실제 요청 성공을 보장하지 않는다.

외부 경로가 `/api/{module-name}{downstream-path}`로 전환되면 서비스 생존 모니터링과 외부 API 계약 모니터링을 분리해야 한다.

## 경로 정책

### 그대로 유지할 내부 운영 경로

- Prometheus scrape: 서비스별 `/actuator/prometheus` 또는 `/metrics`
- 서비스 healthcheck: 서비스별 `/actuator/health` 또는 `/health`
- Gateway 자체 health: `/actuator/health`
- Gateway 서비스 상태 집계: `/api/health`

이 경로들은 Docker network 또는 운영 내부망에서 서비스 포트로 직접 접근하며 `/api/{module}` namespace로 옮기지 않는다. 외부 API 표준화를 이유로 서비스 metrics·health·Actuator·직접 Swagger 경로를 Gateway에 catch-all로 노출하지 않는다.

### 새로 감시할 외부 계약

- canonical URL이 의도한 Gateway route에 매칭되는지 확인한다.
- Gateway가 `/api/{module}` 두 세그먼트만 제거하고 downstream path를 보존하는지 확인한다.
- 인증 요청은 전용 저권한 synthetic identity와 읽기 전용 fixture를 사용한다.
- public webhook이나 mutation endpoint에 실제 이벤트를 보내는 방식은 피하고, 별도 검증 환경·provider test 기능·비파괴 검증 수단을 사용한다.
- CORS preflight는 별도 신호로 감시하되, Gateway가 직접 응답할 수 있으므로 downstream 도달성 검사를 대체하지 않는다.

## 할 일

### 현재 모니터링 inventory 고정

- `cowork-monitoring/prometheus/prometheus.yml`의 Eureka scrape와 Blackbox target을 snapshot으로 남긴다.
- `cowork-monitoring/prometheus/sd/external-services.json`의 외부 서비스 scrape path를 확인한다.
- `docker-compose.yml`의 서비스별 healthcheck 경로를 목록화한다.
- 각 런타임의 Eureka `prometheus.path` metadata와 Gateway `HealthCheckController`의 역할을 문서화한다.
- 기존 모니터링 설정에 구 외부 API 경로가 소비처로 남아 있지 않은지 검사한다.

### canonical API synthetic check 설계

- 모듈별 대표 canonical endpoint, HTTP method, 인증 방식, 예상 status, downstream service를 manifest로 관리한다.
- 읽기 전용 대표 endpoint가 없는 모듈은 전용 fixture 또는 비파괴 contract probe 방식을 결정한다.
- 인증 synthetic check의 자격 증명은 저장소나 Prometheus label에 평문으로 기록하지 않고 secret file 또는 운영 secret store에서 공급한다.
- canonical path, 구 경로 negative, CORS, Gateway route binding을 서로 다른 probe 결과로 구분한다.
- `/api/health` 성공만으로 전체 canonical route 정상으로 판정하지 않는다.

### 대시보드와 알림

- Grafana에 서비스 생존 상태와 Gateway 외부 계약 상태를 분리해 표시한다.
- canonical probe 실패 시 module, method, public path, 예상 downstream을 식별할 수 있는 label을 제공한다.
- 서비스는 UP인데 Gateway 계약만 실패하는 경우를 별도 알림으로 만든다.
- 무별칭 경로 전환 배포 중 구 경로 성공을 이상 상태로 감지할 수 있게 negative probe 또는 배포 검증을 연결한다.
- 알림에는 JWT, OAuth code, webhook signature, 사용자 데이터가 포함되지 않게 한다.

## 검증

- 기존 Prometheus scrape, Blackbox 서비스 health, Compose healthcheck가 namespace 전환 전후 동일하게 성공한다.
- 모듈별 canonical probe가 실제 Gateway를 경유해 예상 downstream 응답을 받는다.
- 의도적으로 route predicate 또는 `StripPrefix`를 잘못 설정한 검증 환경에서 canonical probe가 실패한다.
- 서비스 직접 health는 성공하지만 Gateway route가 실패하는 상황이 대시보드와 알림에서 구분된다.
- 구 외부 경로는 실패하고 canonical 경로만 성공하는지 배포 검증에서 확인한다.
- 허용·비허용 origin의 preflight 결과와 인증 유무에 따른 status가 계약과 일치한다.
- synthetic identity가 데이터 변경이나 관리자 기능을 수행할 권한을 갖지 않는지 확인한다.

## 완료 조건

- 서비스 내부 health·metrics 수집과 Gateway 외부 API 계약 감시가 별도 신호로 운영된다.
- 외부 HTTP API 모듈 10개의 canonical 경로가 end-to-end probe 또는 동등한 자동 검증에 포함된다.
- Gateway 경로가 깨진 상태에서 서비스 health만 정상이라는 이유로 전체 시스템이 정상 표시되지 않는다.
- 구 외부 경로의 재등장을 감지할 수 있다.
- 대시보드·알림·runbook에 canonical module path와 담당 서비스가 일관되게 표시된다.
- 모니터링 자격 증명과 요청·응답에 시크릿 또는 민감한 사용자 데이터가 남지 않는다.
