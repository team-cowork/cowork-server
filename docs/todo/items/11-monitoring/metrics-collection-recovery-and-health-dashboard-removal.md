# 메트릭 수집 장애 분석과 임시 Health Dashboard 제거

- **서비스**: monitoring, Gateway, 전체 메트릭 제공 서비스
- **우선순위**: 🔴 높음
- **현재 상태**: scrape 주소 보완은 반영되어 있으나 전체 수집 복구는 확인하지 않았고 임시 `/health` 화면은 남아 있다.
- **관련 작업**: [Gateway canonical API 계약 모니터링](./gateway-canonical-api-monitoring.md)

## 문제

수집 장애 보고 뒤 로컬 scrape 주소를 Compose DNS로 바꿨다. 현재 Prometheus·Grafana의 실제
데이터를 확인하지 않아 장애 범위와 복구 여부는 아직 확정하지 않았다.

Gateway의 임시 `/health` 화면은 남아 있다. Eureka 등록 집계만으로 메트릭 수집과 알림 경로의
정상을 판단할 수 없으므로, 실제 수집 복구를 확인한 뒤 화면을 제거한다.

## 코드 근거

- [운영 scrape 생성](../../../../deploy/prod/monitoring/render-prometheus.py#L28): Eureka discovery와 Config의 인증된 metrics 조회 설정은 있다. target 상태·sample·알림 복구는 실제 환경 확인이 필요하다.
- [임시 화면 controller](../../../../cowork-gateway/src/main/kotlin/com/cowork/gateway/health/controller/HealthDashboardController.kt#L15): HTML·CSS·JS 제공이 그대로 남아 있다.
- [화면 공개 matcher](../../../../cowork-gateway/src/main/kotlin/com/cowork/gateway/security/config/SecurityConfig.kt#L88): 임시 `/health` 공개 허용도 남아 있어 파일 삭제와 함께 제거해야 한다.

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
