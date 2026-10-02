# 서비스 로그 수집 경로와 필드 정규화

- **서비스**: 전체 앱, Alloy, Loki, Grafana
- **우선순위**: 🟠 중간
- **현재 상태**: local 파일·prod stdout 수집 설정은 있으나 Chat의 파일 우선 출력과 prod parser의 필드·timestamp 정규화 공백이 남아 있다.
- **관련 작업**: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](../11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

local 파일 수집과 운영 VM별 stdout 수집의 경로가 다르다. 최근 로그 경로·mount 보완이 반영되어
과거 누락 목록을 현재 장애 목록으로 사용할 수 없다. 실제 Loki의 전 서비스 도착은 확인하지 않았다.

Chat의 Pino는 production에서도 파일 stream을 우선 사용하고 생성 실패 때만 stdout으로 fallback한다.
prod Alloy는 Docker stdout 파일만 읽으므로 파일 출력의 성공만으로 중앙 수집을 보장하지 않는다.

local Alloy는 JSON의 `@timestamp`를 event timestamp로 적용하지만 prod에는 해당 stage가 없다.
user의 plain text·Preference의 ECS JSON처럼 출력 형식도 달라 top-level `service`·`level`만 읽는
현재 parser를 모든 서비스의 공통 계약으로 사용할 수 없다.

## 코드 근거

- [Chat 로그 출력](../../../../cowork-chat/src/app.module.ts#L57): NODE_ENV와 무관하게 파일 stream을 우선 선택한다. prod stdout 수집과의 연결이 필요하다.
- [prod 수집](../../../../deploy/prod/log-agent/config.alloy#L16): Docker stdout과 top-level level·service만 읽고 application timestamp stage는 없다.
- [local 수집](../../../../deploy/config/monitoring/alloy/config.alloy#L24): 파일 로그에서 JSON @timestamp를 읽는다. plain text·ECS 출력은 별도 대조·정규화가 필요하다.

## 할 일

- local의 파일 수집 유지·stdout 통일을 결정하고 실제 로거 경로·mount·권한을 맞춘다.
- Chat의 prod 출력과 Docker stdout 수집을 연결한다. 파일을 유지하면 별도 수집 경로를 명시한다.
- 서비스별 실제 로그로 `service`·`level`·timestamp의 최소 계약을 정규화한다.
- prod의 application timestamp와 Docker 기록 시각을 구분하고 plain text·ECS 처리 정책을 적용한다.
- VM별 Alloy target 등록과 전 서비스 도착을 확인한다.
- project·roadmap 조회와 기존 Grafana 쿼리를 실제 label에 맞춘다.
- 누락·plain text·잘못된 JSON을 식별할 지표와 운영 확인 방법을 정한다.

## 검증

- local·prod의 대표 로그에서 Loki 도착·원래 시각·레벨·서비스 구분을 확인한다.
- 재기동·로그 회전 뒤 수집 재개와 민감 정보의 로그·label 포함 여부를 수동 점검한다.
- Grafana 쿼리가 실제 데이터로 표시되는지 확인한다.

## 완료 조건

- 모든 서비스의 로그 도착과 누락 여부를 확인할 수 있다.
- 검증한 공통 필드·timestamp를 대시보드가 사용한다.
