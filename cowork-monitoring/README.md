# cowork-monitoring

## 역할

애플리케이션과 인프라의 메트릭·로그·상태 확인·알림 설정을 관리합니다.

- Prometheus: Eureka 메타데이터와 file SD 기반 메트릭 수집, alert rule 평가
- Grafana: Prometheus/Loki 데이터소스와 서비스별 대시보드 프로비저닝
- Loki + Promtail: `cowork_logs` 볼륨의 JSON 로그 수집·조회
- Alertmanager: Discord 웹훅 알림(로컬에서는 웹훅이 없으면 noop 설정)
- Blackbox Exporter: HTTP 상태 확인
- MySQL, PostgreSQL, Redis, Kafka, MongoDB exporter

## 포트

| 구성 요소                      | 포트                     |
|--------------------------------|--------------------------|
| Grafana                        | `3001` (컨테이너 `3000`) |
| Loki                           | `3100`                   |
| Prometheus                     | `9090`                   |
| Alertmanager                   | `9093`                   |
| Blackbox Exporter              | `9115`                   |
| MySQL / Redis / Kafka exporter | `9104` / `9121` / `9308` |
| PostgreSQL / MongoDB exporter  | `9187` / `9216`          |

## 주요 경로

- `prometheus/prometheus.yml`: scrape·service discovery 설정
- `prometheus/rules/`: alert rule
- `grafana/provisioning/`, `grafana/dashboards/`: 데이터소스와 대시보드
- `loki/`, `promtail/`: 로그 저장·수집 설정
- `alertmanager/`: 운영 알림과 로컬 noop 설정
- `blackbox/`: probe 설정

전체 스택은 루트 `docker-compose.yml`에서 기동합니다. 외부 호스트 모니터링은 `EXTERNAL_HOST_URL`이 있을 때 초기화 컨테이너가 Prometheus file SD 파일을 생성하는 임시 경로입니다.

## 설정 공급

`cowork-monitoring`은 애플리케이션 모듈이 아니므로 Config Server를 사용하지 않습니다. Prometheus·Grafana·Loki 일반 설정은 이 디렉터리의 YAML/JSON provisioning 파일이 기준입니다. `GRAFANA_ADMIN_PASSWORD`, exporter의 DB 접속 계정, `DISCORD_WEBHOOK_URL`은 인프라 bootstrap secret이므로 Compose가 배포 환경에서 받고, Alertmanager webhook은 제한 권한 파일로 변환해 마운트합니다.
