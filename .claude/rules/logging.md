---
paths:
  - "**/logback*.xml"
  - "cowork-*/pkg/logger/**"
  - "cowork-notification/cmd/server/main.go"
  - "cowork-user/src/Platform/LineLogger.cs"
  - "cowork-chat/src/app.module.ts"
  - "cowork-github/src/app.module.ts"
  - "deploy/config/monitoring/alloy/**"
  - "deploy/prod/log-agent/**"
---

# Logging

- Every service writes one JSON object per log line to stdout, with at least these keys: `@timestamp`
  (ISO-8601/RFC3339Nano), `level` (uppercase `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`), `service`
  (`cowork-<name>`), `module`, `message`. Alloy's local pipeline (`deploy/config/monitoring/alloy/config.alloy`)
  reads exactly these key names as Loki labels; it has no per-service fallback, unlike the
  defensive multi-format parsing in `deploy/prod/log-agent/config.alloy`. Do not grow that prod
  fallback further — fix the app's log output instead.
- `module` is derived automatically from the logger/caller, not set per call site: Logback via
  `ModuleNameConverter` (immediate parent package of the logger name), Go via `AddSource` +
  parsing the function's package name, Node via `nestjs-pino`'s `renameContext: 'module'` (reuses
  Nest's existing per-class logger context), .NET via the `ILogger` `categoryName` already passed
  to `LineLoggerProvider.CreateLogger`.
- In local dev, also mirror stdout to `/var/log/cowork/<service>/app.log` (the `cowork_logs` volume)
  so the local Alloy file-tailing pipeline picks it up; production only reads Docker's own captured
  stdout (`/var/lib/docker/containers/*/*-json.log`), so stdout is the one output every service must
  get right.
