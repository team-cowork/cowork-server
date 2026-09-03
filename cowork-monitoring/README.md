# cowork-monitoring

## 역할

애플리케이션과 인프라의 메트릭·로그·상태 확인·알림 설정을 관리합니다.

- 메트릭 수집과 대시보드 제공
- 서비스 JSON 로그 수집·조회
- HTTP 상태 확인과 Discord 장애 알림

## 스택

- Docker Compose / YAML·JSON 설정 (별도 애플리케이션 빌드 없음)
- Prometheus / Grafana / Alertmanager
- Loki / Promtail
- Blackbox, MySQL, PostgreSQL, Redis, Kafka, MongoDB exporter

## 포트

| 구성 요소 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| Grafana | `3000` | `3001` |
| Prometheus | `9090` | `9090` |
| Loki | `3100` | `3100` |
| Promtail | `9080` | 공개하지 않음 |
| Alertmanager | `9093` | `9093` |
| Blackbox Exporter | `9115` | `9115` |
| MySQL Exporter | `9104` | `9104` |
| PostgreSQL Exporter | `9187` | `9187` |
| Redis Exporter | `9121` | `9121` |
| Kafka Exporter | `9308` | `9308` |
| MongoDB Exporter | `9216` | `9216` |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `GRAFANA_ADMIN_PASSWORD` | 없음 | 필수. Grafana 관리자 비밀번호 |
| `MYSQL_ROOT_PASSWORD` | 없음 | 필수. MySQL exporter 접속 비밀번호 |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 없음 | 필수. PostgreSQL exporter 접속 계정 |
| `MONGO_ROOT_USERNAME` / `MONGO_ROOT_PASSWORD` | 없음 | 필수. MongoDB exporter 접속 계정 |
| `DISCORD_WEBHOOK_URL` | 빈 값 | local에서는 선택, 그 외 프로파일에서는 필수 |
| `SPRING_PROFILES_ACTIVE` | `local` | Alertmanager의 로컬 noop 설정 허용 여부 |
| `ENV` | `local` (Promtail 기본값) | 로그 환경 라벨. 변경 시 Promtail 컨테이너에 별도 주입 |

Config Server를 사용하지 않습니다. 일반 설정은 모듈 내 YAML·JSON provisioning 파일에서 읽고, 시크릿은 Compose가 주입합니다. Discord webhook은 제한 권한 파일로 변환해 Alertmanager에 마운트합니다.
