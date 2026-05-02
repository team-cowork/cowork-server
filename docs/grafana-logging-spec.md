# Grafana 로깅 기능 구현 스펙

## 개요

Cowork Server의 모든 마이크로서비스(Spring Boot, Go, NestJS, Vert.x)에서 발생하는 로그를 
Loki + Promtail 스택으로 수집하고 Grafana에서 시각화한다.  
기존 Prometheus + Grafana 모니터링 인프라에 로그 레이어를 추가하는 작업이다.

---

## 아키텍처

```
[ Spring Boot 서비스 (WSL2 호스트) ]
  → RollingFileAppender → /var/log/cowork/{service}/*.log
                                    ↓
[ Promtail (Docker) ]
  → volume mount /var/log/cowork 감시
  → labels: service={name}, env=local
                                    ↓
[ Loki (Docker) ]
  → filesystem 스토리지 (Docker volume: loki_data)
  → 보존 기간: 10일
                                    ↓
[ Grafana (Docker) ]
  → Loki 데이터소스 추가
  → 로그 대시보드 (Error Rate 시계열 / Log Level 분포 / Live Tail)
```

### 서비스별 로그 수집 방식

| 서비스                  | 언어                 | 로그 방식                            | 수집 경로                          |
|----------------------|--------------------|----------------------------------|--------------------------------|
| cowork-gateway       | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/gateway/       |
| cowork-config        | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/config/        |
| cowork-user          | Elixir             | Logger + file backend            | /var/log/cowork/user/          |
| cowork-team          | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/team/          |
| cowork-notification  | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/notification/  |
| cowork-channel       | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/channel/       |
| cowork-project       | Kotlin/Spring Boot | logstash-logback-encoder → 파일    | /var/log/cowork/project/       |
| cowork-authorization | Go                 | slog (JSONHandler) → 파일          | /var/log/cowork/authorization/ |
| cowork-voice         | Go                 | slog (JSONHandler) → 파일          | /var/log/cowork/voice/         |
| cowork-chat          | NestJS             | pino + nestjs-pino → 파일          | /var/log/cowork/chat/          |
| cowork-preference    | Vert.x             | Log4j2 + JsonTemplateLayout → 파일 | /var/log/cowork/preference/    |

### Access Log 제외 대상

Prometheus가 주기적으로 호출하는 메트릭 수집 엔드포인트는 access log에서 제외한다.

| 런타임                   | 메트릭 경로                 | 제외 방식                                                            |
|-----------------------|------------------------|------------------------------------------------------------------|
| Spring Boot / Gateway | `/actuator/prometheus` | `sdk.logging.not-logging-urls` 또는 Gateway `AccessLogFilter`에서 제외 |
| Go / NestJS / Vert.x  | `/metrics`             | HTTP logging middleware 또는 router logger에서 제외                    |

---

## JSON 로그 필드 스키마

모든 서비스가 출력하는 JSON 로그의 공통 필드:

```json
{
  "timestamp": "2026-04-21T10:00:00.000Z",
  "level": "ERROR",
  "service": "cowork-gateway",
  "message": "JWT validation failed",
  "userId": "u-001",
  "teamId": "t-001",
  "method": "GET",
  "path": "/api/teams/123",
  "status": 401,
  "logger": "com.cowork.gateway.filter.JwtFilter",
  "thread": "reactor-http-nio-3"
}
```

> **참고**: traceId/spanId는 이번 스코프에 포함하지 않음. 향후 Micrometer Tracing 도입 시 MDC에서 자동 주입 가능.

---

## 구현 상세

### 1. 인프라: Loki + Promtail

#### 디렉토리 구조

```
cowork-monitoring/
  loki/
    loki-config.yml
  promtail/
    promtail-config.yml
```

#### `cowork-monitoring/loki/loki-config.yml`

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  retention_period: 240h   # 10일

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem
```

#### `cowork-monitoring/promtail/promtail-config.yml`

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: cowork-services
    static_configs:
      - targets: ['localhost']
        labels:
          env: ${ENV:-local}
          __path__: /var/log/cowork/*/*.log
    pipeline_stages:
      - json:
          expressions:
            level: level
            service: service
      - labels:
          level:
          service:
      - timestamp:
          source: timestamp
          format: RFC3339Nano
```

#### `docker-compose.yml` 추가 서비스

```yaml
  loki:
    image: grafana/loki:3.5.0
    container_name: cowork-loki
    restart: unless-stopped
    ports:
      - "3100:3100"
    volumes:
      - ./cowork-monitoring/loki/loki-config.yml:/etc/loki/local-config.yaml:ro
      - loki_data:/loki
    command: -config.file=/etc/loki/local-config.yaml
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3100/ready || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

  promtail:
    image: grafana/promtail:3.5.0
    container_name: cowork-promtail
    restart: unless-stopped
    volumes:
      - ./cowork-monitoring/promtail/promtail-config.yml:/etc/promtail/config.yml:ro
      - /var/log/cowork:/var/log/cowork:ro
      - promtail_positions:/tmp
    command: -config.file=/etc/promtail/config.yml
    depends_on:
      loki:
        condition: service_healthy
```

`volumes` 섹션에 추가:
```yaml
  loki_data:
  promtail_positions:
```

---

### 2. Grafana: Loki 데이터소스 프로비저닝

#### `cowork-monitoring/grafana/provisioning/datasources/loki.yml` (신규 파일)

```yaml
apiVersion: 1

datasources:
  - name: Loki
    type: loki
    url: http://loki:3100
    isDefault: false
    editable: false
    jsonData:
      maxLines: 1000
      derivedFields:
        - name: service
          matcherRegex: '"service":"(\w[\w-]*)"'
          url: ""
```

---

### 3. Grafana: 로그 대시보드

`cowork-monitoring/grafana/dashboards/cowork-logs.json` 신규 파일.

#### 패널 구성

| 패널                | 타입          | LogQL 쿼리                                                                | 설명                             |
|-------------------|-------------|-------------------------------------------------------------------------|--------------------------------|
| Error Rate (서비스별) | Time series | `sum by (service) (rate({env="local"} \| json \| level="ERROR" [5m]))`  | 5분 롤링 ERROR 발생률                |
| Log Level 분포      | Bar chart   | `sum by (level, service) (count_over_time({env="local"} \| json [5m]))` | 서비스별 INFO/WARN/ERROR 비율        |
| Live Tail         | Logs panel  | `{env="local", service=~".+"} \| json`                                  | 최신 로그 실시간 표시, service 변수 필터 지원 |

#### 대시보드 변수

- `$service`: label_values(service) - 서비스 드롭다운 필터
- `$level`: label_values(level) - 로그 레벨 필터

---

### 4. Spring Boot 서비스 변경

#### `gradle/libs.versions.toml` 추가

```toml
[versions]
logstash-logback = "8.0"

[libraries]
logstash-logback-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstash-logback" }
```

#### 각 Spring Boot 서비스 `build.gradle.kts` 추가

```kotlin
dependencies {
    // 기존 의존성 유지
    implementation(libs.logstash.logback.encoder)
}
```

대상 서비스: cowork-gateway, cowork-user, cowork-team, cowork-notification, cowork-config, cowork-channel, cowork-project

#### `src/main/resources/logback-spring.xml` (각 Spring Boot 서비스 공통)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty name="SERVICE_NAME" source="spring.application.name" defaultValue="cowork-unknown"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME}"}</customFields>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>teamId</includeMdcKeyName>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/cowork/${SERVICE_NAME}/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/cowork/${SERVICE_NAME}/app.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>10</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME}"}</customFields>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>teamId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

> **MDC 주입**: Gateway 필터(또는 각 서비스의 인터셉터/WebFilter)에서 요청 처리 시작 시 `MDC.put("userId", ...)`, `MDC.put("teamId", ...)` 호출 필요.

#### Gateway: HTTP method/path/status 로그

Gateway의 GlobalFilter에서 요청 완료 후 구조화 로그 출력:

```kotlin
val path = request.path.value()

return chain.filter(exchange).doFinally {
    if (path == "/actuator/prometheus" || path == "/metrics") {
        return@doFinally
    }

    log.info(
        "{}",
        StructuredArguments.entries(mapOf(
            "method" to request.method.name(),
            "path" to path,
            "status" to response.statusCode?.value(),
            "userId" to MDC.get("userId")
        ))
    )
}
```

---

### 5. Go 서비스 변경 (cowork-authorization, cowork-voice)

각 서비스 진입점에서 slog JSON 핸들러 초기화:

```go
import (
    "log/slog"
    "os"
)

func initLogger(serviceName string) *slog.Logger {
    return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
        Level: slog.LevelInfo,
    })).With("service", serviceName)
}
```

파일 출력은 stdout을 `/var/log/cowork/{service}/app.log`로 리다이렉트하거나
`os.OpenFile`로 파일 핸들러를 사용:

```go
logFile, _ := os.OpenFile("/var/log/cowork/authorization/app.log",
    os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
logger = slog.New(slog.NewJSONHandler(io.MultiWriter(os.Stdout, logFile), nil)).
    With("service", "cowork-authorization")
```

> 로그 파일 디렉토리는 서비스 시작 전 `mkdir -p /var/log/cowork/authorization` 필요.

---

### 6. NestJS 서비스 변경 (cowork-chat)

#### 패키지 추가

```bash
npm install nestjs-pino pino-pretty pino
```

#### `app.module.ts`

```typescript
import { LoggerModule } from 'nestjs-pino';
import { createWriteStream } from 'fs';

LoggerModule.forRoot({
  pinoHttp: {
    level: process.env.NODE_ENV === 'production' ? 'info' : 'debug',
    stream: createWriteStream('/var/log/cowork/chat/app.log', { flags: 'a' }),
    autoLogging: {
      ignore: (req) => req.url?.split('?')[0] === '/metrics',
    },
    formatters: {
      level: (label) => ({ level: label }),
    },
    base: { service: 'cowork-chat' },
    messageKey: 'message',
  },
})
```

#### `main.ts`

```typescript
import { Logger } from 'nestjs-pino';
app.useLogger(app.get(Logger));
```

---

### 7. WSL2 로그 디렉토리 초기화

서비스 실행 전 아래 스크립트로 디렉토리 생성 및 권한 설정:

```bash
# scripts/init-log-dirs.sh
#!/bin/bash
SERVICES=(gateway config user team notification channel project authorization voice chat preference)
for svc in "${SERVICES[@]}"; do
    sudo mkdir -p /var/log/cowork/$svc
    sudo chmod 755 /var/log/cowork/$svc
done
```

`Makefile`에 target 추가:
```makefile
init-logs:
    bash scripts/init-log-dirs.sh
```

### 8. Vert.x 서비스 변경 (cowork-preference)

#### `build.gradle.kts` 의존성 추가

```kotlin
dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.apache.logging.log4j:log4j-layout-template-json:2.24.3")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")
}
```

#### `src/main/resources/log4j2.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="CONSOLE" target="SYSTEM_OUT">
      <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json">
        <EventTemplateAdditionalField key="service" value="cowork-preference"/>
      </JsonTemplateLayout>
    </Console>
    <RollingFile name="FILE"
                 fileName="/var/log/cowork/preference/app.log"
                 filePattern="/var/log/cowork/preference/app.%d{yyyy-MM-dd}.log">
      <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json">
        <EventTemplateAdditionalField key="service" value="cowork-preference"/>
      </JsonTemplateLayout>
      <Policies>
        <TimeBasedTriggeringPolicy/>
      </Policies>
      <DefaultRolloverStrategy max="10"/>
    </RollingFile>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="CONSOLE"/>
      <AppenderRef ref="FILE"/>
    </Root>
  </Loggers>
</Configuration>
```

Vert.x 실행 옵션에 SLF4J 브릿지 설정 추가:
```
-Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory
```

---

## 구현 순서

1. **WSL2 디렉토리 초기화**: `make init-logs` 실행
2. **인프라**: `loki-config.yml`, `promtail-config.yml` 작성 → `docker-compose.yml` 추가 → `docker-compose up loki promtail` 검증
3. **Grafana 데이터소스**: `loki.yml` provisioning 추가 → Grafana 재시작 → Explore에서 Loki 연결 확인
4. **Spring Boot 로깅**: `libs.versions.toml` + `build.gradle.kts` 수정 → `logback-spring.xml` 작성 → 서비스 재기동 → `/var/log/cowork/gateway/app.log` JSON 확인
5. **Go 서비스 로깅**: authorization, voice에 slog 파일 핸들러 추가
6. **NestJS 로깅**: nestjs-pino 설치 및 설정
7. **Vert.x 로깅**: log4j2 + JsonTemplateLayout 설정
8. **MDC 주입**: Gateway WebFilter에서 userId/teamId MDC 주입 구현
9. **Grafana 대시보드**: `cowork-logs.json` 작성 및 프로비저닝 확인

---

## 스코프 외 (추후 고려)

- **로그 기반 Alert**: MVP 단계이므로 미포함. 향후 Grafana Alerting으로 서비스별 ERROR 임계치 설정 가능.
- **분산 트레이싱 연동**: traceId/spanId 필드 추가는 Micrometer Tracing 도입 시 MDC 자동 주입으로 확장 가능.
