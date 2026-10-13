# 메트릭 수집 장애 분석과 임시 Health Dashboard 제거

- **서비스**: monitoring, Gateway, 전체 메트릭 제공 서비스
- **우선순위**: 🔴 높음
- **현재 상태**: scrape 주소 보완과 코드로 확정된 수집 결함 3건의 수정은 반영되어 있으나 운영 수집 복구는 아직 검증하지 않았고 임시 `/health` 화면은 남아 있다.
- **관련 작업**: [Gateway canonical API 계약 모니터링](./gateway-canonical-api-monitoring.md)

> **2026-10-13 진척:** 운영 Prometheus·Grafana·Alertmanager에 접근하지 않고 저장소 코드만으로 discovery → 네트워크 → metrics 응답 → query·dashboard·alert 경로를 로컬·운영별로 정적 점검했다. 코드로 확정되는 결함 3건(chat `/metrics` 경로, 운영 Eureka server URL, 인프라 대시보드 label)을 공급원에서 고쳤다. 운영 target 상태·sample 갱신·Grafana·Alertmanager 동작은 아직 검증하지 않았고, 복구 여부는 확정하지 않았다. 확인할 가설과 명령은 아래 `코드 점검 결과`에 있다. 임시 `/health` 화면은 운영 검증이 끝나기 전이므로 제거하지 않았다.

## 문제

수집 장애 보고 뒤 로컬 scrape 주소를 Compose DNS로 바꿨다. 현재 Prometheus·Grafana의 실제
데이터를 확인하지 않아 장애 범위와 복구 여부는 아직 확정하지 않았다.

Gateway의 임시 `/health` 화면은 남아 있다. Eureka 등록 집계만으로 메트릭 수집과 알림 경로의
정상을 판단할 수 없으므로, 실제 수집 복구를 확인한 뒤 화면을 제거한다.

## 코드 근거

- [운영 scrape 생성](../../../../deploy/prod/monitoring/render-prometheus.py#L28): Eureka discovery와 Config의 인증된 metrics 조회 설정은 있다. target 상태·sample·알림 복구는 실제 환경 확인이 필요하다.
- [임시 화면 controller](../../../../cowork-gateway/src/main/kotlin/com/cowork/gateway/health/controller/HealthDashboardController.kt#L15): HTML·CSS·JS 제공이 그대로 남아 있다.
- [화면 공개 matcher](../../../../cowork-gateway/src/main/kotlin/com/cowork/gateway/security/config/SecurityConfig.kt#L88): 임시 `/health` 공개 허용도 남아 있어 파일 삭제와 함께 제거해야 한다.

## 코드 점검 결과 (2026-10-13)

저장소 코드만으로 확인한 결과다. 운영 Prometheus target, Grafana, Alertmanager의 실제 상태는 아직 검증하지 않았다.

### 경로별 판정

| 구간 | 로컬 | 운영 |
|------|------|------|
| discovery | `deploy/config/monitoring/prometheus/prometheus.yml`의 Eureka SD(`cowork-services`)가 `prometheus.scrape=true` 메타데이터 인스턴스만 수집한다. 인스턴스는 Compose DNS 이름 또는 컨테이너 IP로 등록된다. | `deploy/prod/monitoring/render-prometheus.py`가 같은 규칙으로 생성한다. 인스턴스 주소는 `ADVERTISE_IP:HOST_PORT`다(`deploy/prod/lib/environment.sh`). |
| 인증 | Prometheus는 `monitoring` 전용 계정 파일(`deploy/compose/stack.yaml`의 `control-plane-monitoring-auth-init`)로 Eureka 조회와 Config `/actuator/prometheus`를 호출한다. | 렌더러가 `CONFIG_CLIENT_USERNAME`·`CONFIG_CLIENT_PASSWORD`를 쓴다. `ControlPlaneAccessPolicy`가 `/actuator/prometheus`를 `monitoring` 계정에만 허용하므로(`ControlPlaneAccessPolicy.kt` 8행) monitoring 배포 문서에 `cowork-monitoring` 계정이 들어 있어야 한다. 코드에는 이를 검사하는 곳이 없다. |
| Config 수집 | 파일 SD(`sd/external-services.json`)로 `cowork-config:8761`을 수집한다. | 정적 target(`cowork-external-services`)이다. |
| 인프라 | `infrastructure` job의 `service` label(`mysql`, `redis`, `kafka`, `postgres`, `mongodb`) | 같다. |

### 서비스별 판정

| 서비스 | 수집 경로·인증 | query 일치 |
|--------|----------------|------------|
| gateway | `/actuator/prometheus`(`application.yml` 41행). `SecurityConfig`가 `/actuator/**`를 허용하고 응답 래퍼도 제외한다. | `application`·`service` label 일치 |
| team, channel, project, roadmap | `/actuator/prometheus`. 노출은 `cowork-config/src/main/resources/configs/application.yml` 26행(roadmap은 자체 설정)이다. Security 설정이 없다. team은 `/actuator/**`를 래핑에서 제외하고 channel·project는 래핑이 꺼져 있다. roadmap의 래핑 여부는 아직 검증하지 않았다. | 일치. roadmap은 R2DBC라 `hikaricp_*`가 없을 수 있으나 알림 대상이 아니다. |
| config | Eureka에 등록하지 않아 별도 job으로 수집한다. `monitoring` 계정만 허용한다. | `service="config"`, `application="cowork-config"` 일치 |
| authorization, notification, voice | 앱 포트의 `/metrics`(Go). 인증이 없다. | `cowork_{서비스}_http_*` 이름이 코드와 일치 |
| chat | 메타데이터는 `/metrics`다. **수정 전에는 전역 접두사 때문에 `/chat/metrics`에만 노출되어 있었다.** | `nodejs_*`, `service="chat"` 일치 |
| preference | `/metrics`(Vert.x). 인증이 없다. | `vertx_*` metric 이름은 아직 검증하지 않았다. |
| user | `/actuator/prometheus`(Elixir). 인증이 없다. | `application="cowork-user"` 일치 |

### 코드로 확정해 수정한 원인

- chat: `app.setGlobalPrefix('chat', { exclude: [...] })`가 `metrics`를 제외하지 않아 Prometheus 컨트롤러가 `/chat/metrics`로 노출된다. Eureka 메타데이터(`prometheus.path=/metrics`)·`AuthGuard`·projection 미들웨어는 모두 접두사 없는 `/metrics`를 전제로 한다. 코드상 로컬·운영 모두 chat scrape가 `404`로 실패해 `up == 0`이 되고 `coworkServiceDown`이 chat에 대해 발화할 수 있었다. 실제 target 상태는 아직 검증하지 않았다. `cowork-chat/src/main.ts`의 제외 목록에 `metrics`를 추가했다.
- 운영 Eureka SD: Prometheus는 `server` 값 뒤에 `/apps`를 그대로 붙인다. 배포 문서(`docs/config-server-access.md`)와 `deploy/prod/settings.example.json`은 `EUREKA_SERVER_URL`을 `.../eureka/`(끝 슬래시)로 안내하고, 렌더러는 그대로 `server`에 넣어 `.../eureka//apps`를 요청하게 된다. 같은 값을 쓰는 chat·Go·user·preference 클라이언트는 모두 끝 슬래시를 제거한다. `deploy/prod/monitoring/render-prometheus.py`에서 끝 슬래시를 제거하도록 고쳤다. Config Server가 `//apps`를 거부하는지는 아직 검증하지 않았다.
- 인프라 대시보드: 2026-04-27에 `mysql`·`redis`·`kafka`·`postgres` job이 `infrastructure` job의 `service` label로 합쳐졌지만 `infrastructure.json`은 `job="mysql"` 같은 selector를 유지해 항상 빈 결과였다. 쿼리를 `service="..."`로 바꿨다.

### 운영에서 확인할 가설

| 가설 | 확인 |
|------|------|
| monitoring 배포 문서가 `monitoring` 계정이 아닌 계정을 쓴다. Config target만 `403`이 된다. | Prometheus `GET /api/v1/targets?state=any`에서 job `cowork-external-services`의 `lastError`·`health`를 본다. Config 로그의 `Reject control-plane authorization` 건과 monitoring VM IP(`remote`)를 대조하고, 배포 문서의 `CONFIG_CLIENT_USERNAME`이 `cowork-monitoring`으로 시작하는지 확인한다(비밀번호는 출력하지 않는다). |
| 수정 전 렌더 설정이 Eureka SD에서 실패했다. | 이전 배포의 Prometheus에서 `prometheus_sd_refresh_failures_total{mechanism="eureka"}`과 `prometheus_sd_discovered_targets{config="cowork-services"}`를 본다. 재배포 뒤에는 `count by (service) (up{job="cowork-services"})`에 서비스가 모두 나오는지 확인한다. |
| monitoring VM에서 `ADVERTISE_IP:HOST_PORT`로 닿지 않는다(방화벽·`BIND_IP`). | monitoring VM에서 인스턴스별로 `curl -s -o /dev/null -w '%{http_code}' http://<ADVERTISE_IP>:<HOST_PORT>/actuator/prometheus`(Go·chat·preference는 `/metrics`)를 실행한다. Config VM의 `CONFIG_ALLOWED_CIDRS`에 monitoring VM이 있는지도 확인한다. |
| chat이 수정 뒤에도 `down`이다. | 배포 뒤 `curl -s -o /dev/null -w '%{http_code}' http://<chat>:8087/metrics`가 `200`이고 `up{service="chat"}`가 `1`인지 확인한다. |
| preference의 Vert.x metric 이름이 대시보드와 다르다. | `curl -s http://<preference>:9001/metrics`에서 `vertx_http_server_requests_total`, `vertx_http_server_response_time_seconds_bucket`이 있는지 확인한다. |
| Grafana·Alertmanager가 수집 결과를 반영하지 않는다. | 같은 시간대의 `up`·`http_server_requests_seconds_count`를 Prometheus와 Grafana에서 비교한다. Prometheus `GET /api/v1/rules`의 규칙 `health`와 Alertmanager `GET /api/v2/alerts`로 발화·전달을 확인하고, 비핵심 target을 잠시 중지해 `coworkServiceDown`이 Discord까지 가는지 본다. |
| 재시작 뒤 수집이 복구되지 않는다. | Prometheus 컨테이너와 서비스를 각각 재시작한 뒤 `up{job=~"cowork-.*"}`가 3회 연속 scrape에서 `1`인지 확인한다. |

이 점검에서 코드로 판단할 수 없어 수정하지 않은 항목은 위 표의 가설로만 남긴다.

## 할 일

- 영향 환경·서비스·시간을 정하고 Prometheus target의 last error·sample 갱신을 확인한다.
- discovery → 네트워크 → metrics 응답 → 저장·query → Grafana·Alertmanager 순서로 실패 구간을 좁힌다.
- 확인된 원인을 공급원 설정이나 해당 서비스에서 수정하고 근거를 기록한다.
- 아래 복구 기준을 만족한 뒤 `TODO(temporary health dashboard)`의 controller·assets·공개 matcher를 제거한다.
- 제거에 따라 불필요해진 테스트를 정리하고 핵심 권한·보안 단위 테스트만 유지한다.

## 검증

- 영향 target이 3회 연속 scrape에서 `UP`이고 sample이 갱신되며 대표 metric을 직접 조회할 수 있는지 확인한다.
- Grafana의 같은 시간·label 결과와 Alertmanager의 실제 실패 감지를 확인한다.
- Prometheus·서비스 재시작 후 discovery와 수집이 복구되는지 수동 운영 점검한다.
- 임시 화면 제거 뒤 `GET /api/health`와 기존 Gateway route는 정적으로 확인한다.

## 완료 조건

- 장애 범위·근본 원인·수정 근거와 scrape·query·dashboard·alert 확인 결과가 기록되어 있다.
- 재시작 뒤에도 수집이 유지되어 있다.
- 임시 `/health` HTML·assets·공개 matcher가 제거되어 있고 기존 JSON 상태 API는 유지되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#60](https://github.com/team-cowork/cowork-server/pull/60) · [#416](https://github.com/team-cowork/cowork-server/pull/416) · [#451](https://github.com/team-cowork/cowork-server/pull/451).
- [대조 코드](../../../../cowork-gateway/src/main/kotlin/com/cowork/gateway/health/controller/HealthDashboardController.kt): 운영 scrape 생성기는 있으나 임시 HealthDashboardController·assets와 SecurityConfig 공개 matcher가 유지된다.
- 판정: **부분 구현**. 실제 수집 복구 판정과 임시 화면·공개 matcher 제거를 남은 범위로 유지한다.
