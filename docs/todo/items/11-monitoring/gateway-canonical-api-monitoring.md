# Gateway canonical API 계약 모니터링

- **서비스**: Gateway, Prometheus, Blackbox Exporter, Grafana, Alertmanager
- **우선순위**: 🟠 중간
- **현재 상태**: 내부 health·metrics 구성은 있으나 canonical Gateway 경로의 도달성을 별도 감시하지 않는다.
- **선행 작업**: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

현재 health·metrics 수집은 서비스 포트를 직접 사용한다. 이 신호가 정상이어도 Gateway route,
`StripPrefix`, 인증·CORS 또는 downstream 연결은 실패할 수 있다.

`/api/health` 집계와 CORS preflight도 실제 API의 downstream 도달성을 증명하지 않는다.
서비스 생존과 공개 API 계약을 각각 확인하는 운영 probe가 필요하다.

## Probe 정책

| 대상 | 운영 정책 |
|---|---|
| 공개 API | 모듈별 대표 canonical 경로·method·예상 status·downstream을 manifest로 관리한다. |
| 인증 API | 전용 저권한 계정과 읽기 전용 fixture를 사용한다. |
| webhook·변경 API | 실제 업무 이벤트 대신 provider 검증 기능이나 비파괴 probe를 선택한다. |
| CORS·구 경로 | API 도달성과 별도 신호로 감시한다. |

## 코드 근거

- [local probe 목록](../../../../deploy/config/monitoring/prometheus/prometheus.yml#L56): 서비스별 직접 health 주소만 검사하며 Gateway canonical API manifest는 없다.
- [prod probe 생성](../../../../deploy/prod/monitoring/render-prometheus.py#L49): Eureka healthCheckUrl을 probe 대상으로 사용한다. 실제 API method·인증·CORS 계약을 검사하지 않는다.

## 할 일

- 외부 HTTP API 모듈별 읽기 전용 경로 또는 동등한 비파괴 probe를 정한다.
- probe 자격 증명을 secret store로 공급하고 민감한 요청·응답을 저장하지 않는다.
- canonical·구 경로 거부·CORS 결과를 구분하고 기존 내부 health·metrics 경로는 유지한다.
- Grafana·알림에 서비스 상태와 Gateway 경로 상태를 따로 표시한다.

## 검증

- 실제 Gateway를 거친 응답과 예상 downstream을 수동 배포 점검으로 확인한다.
- 검증 환경의 route 장애를 probe·dashboard·alert에서 식별하는지 확인한다.
- 허용·비허용 origin과 인증 상태별 결과 및 probe 계정의 최소 권한을 확인한다.

## 완료 조건

- 외부 HTTP API 모듈 10개의 공개 계약이 운영 probe에 포함되어 있다.
- 서비스가 정상이어도 Gateway 경로 실패와 구 경로 재등장을 식별할 수 있다.
- probe·알림에 시크릿이나 민감한 사용자 데이터가 남지 않는다.
