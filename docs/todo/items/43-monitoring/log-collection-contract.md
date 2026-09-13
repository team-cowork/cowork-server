# 서비스 로그 수집 경로와 필드 정규화

- **서비스**: 전체 앱, Alloy, Loki, Grafana
- **우선순위**: 🟠 중간
- **현재 상태**: local 파일 수집과 운영 VM별 stdout 수집 설정은 있으나 모든 서비스의 로그 도착·공통 label은 검증하지 않았다
- **관련 작업**: [메트릭 수집 장애 복구](../11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

local Alloy는 `cowork_logs` 볼륨의 `/var/log/cowork/*/*.log`를 읽는다. authorization은
공유 볼륨이 없고, config·gateway·team·chat은 기본 로그 경로가 이 수집 경로와 다르다.
notification·channel·project·roadmap에는 이 경로로 쓰는 파일 출력이 없다.
`deploy/local/init-log-dirs.sh`도 일부 로거의 실제 디렉터리명과 일치하지 않는다.

운영 Alloy는 Docker JSON stdout 로그를 수집하지만, 런타임마다 JSON 필드와 plain text 출력이
다르므로 `service`·`level` label을 모든 로그에서 추출할 수 있다는 보장은 없다.
기존 로그 문서의 공통 스키마는 구현 완료 계약이 아니었다.
정적 설정을 대조했으며 실행 중 Loki의 전체 수집 상태는 확인하지 않았다.

## 할 일

- local 수집을 stdout으로 통일할지 파일 수집을 유지할지 결정한다. 파일 방식을 유지하면
  누락된 mount·로그 경로·appender를 맞추고 호스트 로그 초기화 경로도 실제 로거와 일치시킨다.
- Go·Spring·Pino·ECS·Elixir의 실제 로그를 기준으로 `service`, `level`, timestamp의 최소 계약을
  정하고 Alloy 또는 로거에서 정규화한다. 보장되지 않는 `userId`·`teamId`를 필수 필드로 가정하지 않는다.
- project·roadmap 전용 대시보드를 추가하고 기존 로그 쿼리를 실제 label 집합에 맞춘다.
- 수집 누락, plain text, 잘못된 JSON을 식별할 운영 지표와 확인 방법을 정한다.

## 검증

- local·prod에서 서비스별 대표 로그를 발생시켜 Loki 도착, 원래 시각·레벨·서비스 구분을 확인한다.
- 재기동·로그 회전 후 수집 재개 여부와 민감 정보가 로그·label에 포함되지 않는지 운영 점검한다.
- Grafana 쿼리가 실제 수집 데이터로 표시되는지 확인한다.

## 완료 조건

- 모든 서비스의 로그가 선택한 수집 경로로 전달되며 누락 여부를 식별할 수 있다.
- 공통 label과 timestamp가 실제 로그에서 검증되어 있고 대시보드가 이 계약을 사용한다.
