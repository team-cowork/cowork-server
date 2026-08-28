# Grafana 로그 수집 현황과 운영 가이드

이 문서는 2026-07-23의 Docker Compose, Loki, Promtail, 애플리케이션 로거 구현을 기준으로 한다. 기존 구현 계획과 달리 현재 모든 서비스 로그가 Loki에 수집되는 상태는 아니다.

## 현재 구성

```text
애플리케이션 파일 로그
  → Docker named volume: cowork_logs
  → Promtail 3.6.0
  → Loki 3.7.2 (10일 보존)
  → Grafana 13.0.2
```

| 구성 요소 | 로컬 주소               | 설정 파일                                        |
|-----------|-------------------------|--------------------------------------------------|
| Loki      | `http://localhost:3100` | `cowork-monitoring/loki/loki-config.yml`         |
| Promtail  | 외부 포트 없음          | `cowork-monitoring/promtail/promtail-config.yml` |
| Grafana   | `http://localhost:3001` | `cowork-monitoring/grafana/`                     |

Promtail은 `cowork_logs` 볼륨을 read-only로 마운트하고 아래 파일만 읽는다.

```text
/var/log/cowork/*/*.log
```

호스트의 `/var/log/cowork`를 bind mount하지 않으므로 Compose 실행에는 `make init-logs`가 필요하지 않다. 이 명령은 legacy 호스트 로그 디렉터리를 만들지만 일부 디렉터리명이 현재 로거 경로와 다르므로, 호스트 직접 실행에 사용하려면 먼저 `scripts/init-log-dirs.sh`와 대상 로거 경로를 맞춰야 한다.

## 서비스별 실제 수집 상태

| 서비스                 | 런타임 로거                | 현재 파일 경로                                 | Loki 수집 상태                                         |
|------------------------|----------------------------|------------------------------------------------|--------------------------------------------------------|
| `cowork-voice`         | Go `slog` JSON             | `/var/log/cowork/cowork-voice/app.log`         | 수집됨                                                 |
| `cowork-preference`    | Log4j2 ECS JSON            | `/var/log/cowork/preference/app.log`           | 수집됨                                                 |
| `cowork-user`          | Elixir Logger file backend | `/var/log/cowork/user/application.log`         | 파일은 수집되지만 plain text라 공통 JSON 필드가 없음   |
| `cowork-authorization` | Go `slog` JSON             | `/var/log/cowork/cowork-authorization/app.log` | 컨테이너에 `cowork_logs` 볼륨이 없어 미수집            |
| `cowork-config`        | Logback JSON               | 기본 `/app/build/logs/cowork/...`              | Compose가 `COWORK_LOG_DIR`를 지정하지 않아 미수집      |
| `cowork-gateway`       | Logback JSON               | 기본 `/app/build/logs/cowork/...`              | Compose가 `COWORK_LOG_DIR`를 지정하지 않아 미수집      |
| `cowork-team`          | Logback JSON               | 기본 `/app/build/logs/cowork/...`              | Compose가 `COWORK_LOG_DIR`를 지정하지 않아 미수집      |
| `cowork-chat`          | Pino JSON                  | 기본 `/app/build/logs/cowork/chat/app.log`     | Compose가 `COWORK_CHAT_LOG_DIR`를 지정하지 않아 미수집 |
| `cowork-notification`  | Go `slog` stdout           | 파일 출력 없음                                 | 미수집                                                 |
| `cowork-channel`       | Spring 기본 로깅           | 파일 출력 없음                                 | 미수집                                                 |
| `cowork-project`       | Spring 기본 로깅           | 파일 출력 없음                                 | 미수집                                                 |
| `cowork-roadmap`       | Spring 기본 로깅           | 파일 출력 없음                                 | 미수집                                                 |

Compose가 대부분의 애플리케이션에 `cowork_logs:/var/log/cowork`를 마운트하더라도, 애플리케이션이 그 경로에 파일을 쓰지 않으면 Promtail은 수집할 수 없다. `cowork-authorization`은 파일 경로는 맞지만 현재 공유 볼륨 mount 자체가 빠져 있다.

## Promtail 파싱 규칙

현재 pipeline은 JSON에서 `level`, `service`, `@timestamp`를 읽고 `level`과 `service`를 Loki label로 만든다.

```yaml
pipeline_stages:
  - json:
      expressions:
        level: level
        service: service
  - labels:
      level:
      service:
  - timestamp:
      source: "@timestamp"
      format: RFC3339Nano
```

Go `slog` 로거는 시간을 `@timestamp`로 바꾸고 `service`를 추가하므로 이 규칙과 맞는다. 다음 차이는 현재 남아 있다.

- `cowork-user` 파일 로그는 JSON이 아니므로 `service`, `level`, `@timestamp`를 추출할 수 없다.
- `cowork-preference`의 ECS JSON은 레벨 필드가 Promtail이 기대하는 단순 `level`과 다를 수 있어 label을 실제 로그로 확인해야 한다.
- 공통 스키마에 정의됐던 `userId`, `teamId`, HTTP method/path/status는 모든 런타임에서 보장되지 않는다.

따라서 LogQL을 작성할 때 모든 로그에 `service`와 `level` label이 있다고 가정하지 않는다.

## Grafana 프로비저닝

Grafana는 기동 시 Prometheus와 Loki 데이터소스, 대시보드를 자동으로 불러온다.

- 데이터소스: `cowork-monitoring/grafana/provisioning/datasources/`
- 대시보드 provider: `cowork-monitoring/grafana/provisioning/dashboards/dashboards.yml`
- 대시보드 JSON: `cowork-monitoring/grafana/dashboards/`

현재 대시보드는 다음 범주로 구성된다.

- 전체: `overview.json`, `infrastructure.json`, `cowork-logs.json`
- 서비스: config, gateway, authorization, user, team, channel, notification, chat, voice, preference

`cowork-project`와 `cowork-roadmap` 전용 대시보드는 현재 없다.

## 확인 절차

```bash
docker compose up -d loki promtail prometheus grafana
docker compose ps loki promtail grafana
docker compose logs --tail=100 promtail
```

Loki에서 label과 스트림을 확인한다.

```bash
curl -s http://localhost:3100/loki/api/v1/labels
curl -s http://localhost:3100/loki/api/v1/label/service/values
```

Grafana Explore에서 우선 아래처럼 넓게 조회한 뒤 실제 label을 확인한다.

```logql
{env="local"}
```

JSON 로그만 필터링할 때는 다음처럼 사용한다.

```logql
{env="local"} | json | level="ERROR"
```

## 전체 서비스 수집을 완료하려면

문서상의 현황을 "모든 서비스 수집 완료"로 바꾸기 전에 아래 구현이 필요하다.

1. authorization에 `cowork_logs` 볼륨을 mount하고, Spring/Chat 서비스가 Compose에서 `/var/log/cowork/<service>`에 쓰도록 로그 경로 환경 변수를 지정한다.
2. channel, project, roadmap에 JSON file appender를 추가한다.
3. notification에 stdout 외 JSON file writer를 추가하거나 Docker log 수집 경로를 별도로 구성한다.
4. user 로그를 JSON으로 바꾸거나 Promtail에 전용 plain-text 파서를 추가한다.
5. ECS와 비-ECS 필드명을 하나의 Promtail pipeline으로 정규화한다.
6. project, roadmap 대시보드를 추가하고 기존 대시보드 쿼리를 실제 label 집합으로 검증한다.

메트릭 수집 엔드포인트는 애플리케이션 access log에서 제외한다. Spring 계열은 공통 `sdk.logging.not-logging-urls`, Chat은 Pino `autoLogging.ignore`, 그 외 런타임은 각 HTTP middleware에서 `/metrics` 또는 health 경로를 제외한다.
