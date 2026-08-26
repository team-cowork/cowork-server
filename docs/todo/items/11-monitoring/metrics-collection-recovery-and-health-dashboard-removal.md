# 메트릭 수집 장애 분석과 임시 Health Dashboard 제거

- **서비스**: cowork-monitoring, cowork-config, cowork-gateway, 전체 메트릭 제공 서비스, Prometheus, Grafana, Alertmanager
- **우선순위**: 🔴 높음
- **관련 작업**: [Gateway canonical API 계약 모니터링](./gateway-canonical-api-monitoring.md)
- **현재 상태**: 모니터링 도구가 일부 또는 전체 서비스의 메트릭을 정상 수집하지 못함. 장애 범위와 근본 원인은 아직 확정하지 않았으며, 개발자용 `GET /health` 화면을 임시 상태 확인 수단으로 추가함

## 문제

현재 메트릭 수집 장애가 있다는 사실만 확인되었고, 실패가 service discovery, scrape 요청, 메트릭 응답, Prometheus 저장·조회, Grafana datasource·query 중 어느 단계에서 발생하는지는 아직 분리되지 않았다. 근거 없이 설정부터 변경하면 실제 원인을 가리거나 다른 서비스의 정상 수집까지 깨뜨릴 수 있으므로 복구 작업보다 문제 분석이 먼저다.

임시 `GET /health` 화면은 Gateway route와 Eureka 등록 상태를 `UP`, `DEGRADED`, `DOWN`으로 보여줄 뿐이다. 실제 `/actuator/prometheus` 또는 `/metrics` scrape 성공, sample 적재, Grafana query 정상 여부를 증명하지 않으므로 모니터링 도구의 대체재로 취급하지 않는다.

## 작업 원칙

- 증거를 확보하고 실패 구간을 특정하기 전에는 설정 변경을 해결책으로 확정하지 않는다.
- local과 prod의 증상을 구분하고, 한 환경의 성공만으로 다른 환경의 복구를 판정하지 않는다.
- JWT, API key, datasource credential, 내부 주소의 민감한 부분은 분석 기록과 로그에서 마스킹한다.
- metrics·Actuator endpoint를 복구 편의를 이유로 Gateway 외부 route에 공개하지 않는다.
- 임시 Health Dashboard는 아래 제거 조건을 모두 만족한 후 한 번에 제거한다.

## 1단계: 장애 범위와 재현 조건 고정

- 장애가 발생한 최초 시점, 지속 여부, 영향 환경, 영향 서비스를 기록한다.
- Prometheus `/targets`에서 전체 target의 discovery 상태, 최종 scrape URL, health, last scrape, last error를 확인한다.
- 서비스별 기대 scrape 경로와 실제 경로를 비교한다.
  - Spring: `/actuator/prometheus`
  - Go·기타 런타임: 각 서비스 설정의 `/metrics` 또는 명시된 경로
- Eureka를 사용하는 서비스는 등록 여부와 `prometheus.scrape`, `prometheus.path` metadata를 확인한다.
- file service discovery를 사용하는 target은 파일 로드 여부와 relabel 이후 최종 target 잔존 여부를 확인한다.
- 다음 열을 갖는 target matrix를 작성해 정상·실패 서비스를 같은 기준으로 비교한다.

| 서비스 | 환경 | discovery 원본 | 기대 scrape URL | 실제 scrape URL | HTTP 결과 | Prometheus last error | sample 수 |
|---|---|---|---|---|---|---|---|
| 조사 후 기록 | local/prod | Eureka/file/static | 조사 후 기록 | 조사 후 기록 | 조사 후 기록 | 조사 후 기록 | 조사 후 기록 |

## 2단계: 실패 구간별 원인 분석

### Service discovery

- Prometheus가 Eureka 또는 file SD 원본을 읽는지 확인한다.
- service name, instance address, port, scheme, metadata label이 relabel 규칙의 기대값과 일치하는지 확인한다.
- 발견된 target이 `keep`/`drop` relabel 규칙에서 의도치 않게 제거되는지 확인한다.

### 네트워크와 scrape 요청

- Prometheus 컨테이너에서 최종 scrape URL로 직접 요청해 DNS, Docker network, port, timeout 문제를 분리한다.
- HTTP status, redirect, `Content-Type`, 응답 시간, 응답 본문이 Prometheus exposition format인지 확인한다.
- 서비스 healthcheck 성공과 metrics endpoint 성공을 별도로 기록한다.

### 서비스의 메트릭 노출

- 각 런타임에서 metrics exporter가 활성화되어 있고 endpoint가 실제로 등록됐는지 확인한다.
- Config Server가 전달한 설정과 서비스가 최종 적용한 설정을 비교한다.
- endpoint 응답이 비어 있거나 scrape 중 예외가 발생한다면 애플리케이션 로그와 최소 재현 요청을 연결한다.

### Prometheus 저장과 조회

- target이 `UP`이어도 `scrape_samples_scraped`, `scrape_samples_post_metric_relabeling`이 기대대로 증가하는지 확인한다.
- metric relabeling이 필요한 series를 제거하는지 확인한다.
- 대표 metric과 `up{job=...}`을 Prometheus에서 직접 조회해 scrape 성공과 query 성공을 분리한다.

### Grafana와 Alertmanager

- Grafana datasource health와 Prometheus 직접 query 결과를 비교한다.
- dashboard variable, label selector, renamed job·application label 때문에 panel만 비는지 확인한다.
- alert rule의 query와 label matcher가 실제 적재된 series를 참조하는지 확인한다.

## 3단계: 근본 원인 수정

- target matrix와 단계별 증거로 하나의 실패 구간과 근본 원인을 명시한다.
- 원인을 재현하는 최소 검증 절차 또는 자동 테스트를 먼저 만든다.
- 확인된 원인만 최소 범위로 수정하며, 분석 중 발견한 별도 문제를 같은 변경에 섞지 않는다.
- 설정을 변경하면 local/prod, Eureka/file SD, 각 런타임의 placeholder 처리 차이를 다시 확인한다.
- 수정 내용과 영향받은 target을 이 문서 또는 별도 runbook에 기록한다.

## 복구 검증

- 영향 대상 전체가 최소 3회 연속 scrape interval 동안 `UP`이다.
- 각 target의 last error가 비어 있고 sample 수가 0보다 크며 연속 scrape에서 갱신된다.
- Spring과 비-Spring 서비스의 대표 metric을 Prometheus에서 직접 조회할 수 있다.
- Grafana의 관련 panel이 같은 시간 범위와 label로 데이터를 표시한다.
- 의도적으로 검증 target 하나를 실패시키면 dashboard와 alert에서 실패를 식별할 수 있다.
- Prometheus 또는 대상 서비스를 재시작한 뒤에도 discovery와 scrape가 자동 복구된다.
- 운영 적용 대상이면 prod에서도 같은 검증을 완료하고 민감 정보가 로그·label에 남지 않았음을 확인한다.

## 임시 Health Dashboard 제거 조건

다음 조건을 모두 만족하기 전에는 임시 화면을 제거하지 않는다.

- 메트릭 수집 장애의 근본 원인과 수정 근거가 기록되어 있다.
- 영향 환경과 서비스가 위 복구 검증을 모두 통과했다.
- Grafana와 Alertmanager가 서비스 상태 이상을 식별할 수 있다.
- 임시 `/health` 화면이 운영 판단이나 장애 대응 절차에서 더 이상 필요하지 않음을 확인했다.

조건 충족 후 `TODO(temporary health dashboard)`를 검색해 다음 항목을 제거한다.

- `HealthDashboardController`와 `/health`, `/health/health.css`, `/health/health.js` handler
- `cowork-gateway/src/main/resources/health-dashboard/`의 `index.html`, `health.css`, `health.js`
- `SecurityConfig`의 `GET /health`, `/health/**` 공개 matcher
- `HealthDashboardControllerTest`
- `SecurityConfigTest`의 `/health` 및 asset 공개·비공개 사례

기존 JSON API인 `GET /api/health`, `HealthCheckController`, `ServiceStatus`는 임시 화면과 별개이므로 제거하지 않는다. 제거 후 `./gradlew :cowork-gateway:test`와 `:cowork-gateway:ktlintCheck`를 실행한다.

## 완료 조건

- 메트릭 수집 장애의 재현 조건, 영향 범위, 실패 구간, 근본 원인이 증거와 함께 기록되어 있다.
- 확인된 원인이 수정되고 영향 대상의 scrape·저장·query·dashboard·alert가 모두 검증되었다.
- 복구가 재시작과 rediscovery 이후에도 유지된다.
- `TODO(temporary health dashboard)`로 표시된 임시 HTML 화면과 관련 공개 경로·테스트가 제거되었다.
- 기존 `GET /api/health`와 다른 Gateway 기능이 회귀 없이 동작한다.
