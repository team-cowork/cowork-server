# 서비스 로그 수집 경로와 필드 정규화

- **서비스**: 전체 앱, Alloy, Loki, Grafana
- **우선순위**: 🟠 중간
- **현재 상태**: local 파일·prod Docker stdout 수집 설정은 있으나 전 서비스 도착과 공통 필드는 검증하지 않았다.
- **관련 작업**: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](../11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

local 파일 수집과 운영 VM별 stdout 수집의 경로가 다르다. 최근 로그 경로·mount 보완이 반영되어
과거 누락 목록을 현재 장애 목록으로 사용할 수 없다. 실제 Loki의 전 서비스 도착은 확인하지 않았다.

런타임마다 JSON 필드와 plain text 출력이 달라 Alloy가 `service`·`level`·timestamp를 같은 의미로
추출하는지도 확인이 필요하다. 수집 설정 존재만으로 공통 로그 계약의 완료를 판단하지 않는다.

## 할 일

- local의 파일 수집 유지·stdout 통일을 결정하고 실제 로거 경로·mount·권한을 맞춘다.
- 서비스별 실제 로그로 `service`·`level`·timestamp의 최소 계약을 정규화한다.
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
