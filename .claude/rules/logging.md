---
paths:
  - "**/logback*.xml"
  - "cowork-*/pkg/logger/**"
  - "cowork-notification/cmd/server/main.go"
  - "cowork-user/src/Platform/LineLogger.cs"
  - "cowork-chat/src/app.module.ts"
  - "cowork-github/src/app.module.ts"
  - "deploy/runtime/agents/**"
  - "deploy/ansible/tasks/observability.yml"
---

# Logging

- Every service writes one JSON object per log line to stdout, with at least these keys: `@timestamp`
  (ISO-8601/RFC3339Nano), `level` (uppercase `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`), `service`
  (`cowork-<name>`), `module`, `message`. Alloy uses `@timestamp` as the log time (keeping
  the read time when it is missing or unparseable), label `level` (`UNKNOWN` when missing), take
  `service` from the deployment (Nomad Docker job label or host-agent `cowork.service` label), and label
  application lines without `@timestamp` or with `level` `UNKNOWN` `parse_error="true"`.
  The defensive multi-format parsing in `deploy/runtime/agents/config.alloy` is a fallback; fix the app's log output instead.
- `module` is derived automatically from the logger/caller, not set per call site. Logback's
  `ModuleNameConverter` takes the segment right after `domain`/`global` in `com.cowork.<service>.*`
  loggers (so logs group by feature, not by layer name like `service`/`impl`), and falls back to the
  logger name's immediate parent package for flatter in-house packages and third-party loggers. Go
  uses `AddSource` + the caller function's package name. Node uses `nestjs-pino`'s
  `renameContext: 'module'` (reuses Nest's existing per-class logger context). .NET uses the
  `ILogger` `categoryName` already passed to `LineLoggerProvider.CreateLogger`.
- Production reads Docker's captured stdout through `loki.source.docker` and the Docker API,
  so stdout is the one output every service must get right.

- Production reads each container through one Alloy Docker source; do not also tail Nomad allocation files or the Docker JSON files. Send logs to the internal Loki ingress with a per-host client certificate.
