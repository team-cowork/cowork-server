# Gateway canonical API 계약 모니터링

- **서비스**: Gateway, Prometheus, Blackbox Exporter, Grafana, Alertmanager
- **우선순위**: 🟠 중간
- **현재 상태**: 공개 `/api/health`의 HTTPS·JSON 응답 probe는 구현되어 있으며 업무 API별 probe는 없다.

## 문제

서비스의 직접 health 응답이 정상이어도 Gateway route·인증·CORS·downstream 연결은 실패할 수 있다.
공개 `/api/health` probe는 DNS·TLS·프록시·Gateway 응답 형식을 확인한다. 이 집계 응답이나
CORS preflight만으로 실제 업무 API의 도달성을 판정할 수 없다.

## Probe 정책

| 대상 | 정책 |
|---|---|
| 공개 API | 대표 canonical 경로·method·예상 status·downstream을 정한다. |
| 인증 API | 전용 저권한 계정과 읽기 전용 fixture를 사용한다. |
| webhook·변경 API | provider 검증 기능이나 동등한 비파괴 probe를 사용한다. |
| CORS·구 경로 | API 도달성과 별도 신호로 감시한다. |

## 할 일

- 외부 HTTP API 모듈 10개의 probe를 구현한다.
- 자격 증명은 secret store로 공급하고 민감한 요청·응답은 저장하지 않는다.
- 업무 API별 상태를 Grafana·알림에 연결한다.

## 검증

- 실제 Gateway 응답·downstream과 허용·비허용 origin, 인증 상태별 결과를 수동 확인한다.
- 검증 환경의 route 장애가 dashboard·alert에 반영되는지 확인한다.

## 완료 조건

- 서비스가 정상이어도 Gateway 경로 실패와 구 경로 재등장을 식별할 수 있다.
- 외부 HTTP API 모듈 10개가 포함되어 있고 probe·알림에 시크릿이나 민감한 사용자 데이터가 남지 않는다.
