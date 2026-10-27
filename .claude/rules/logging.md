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
  (`cowork-<name>`), `module`, `message`. Both Alloy pipelines use `@timestamp` as the log time (keeping
  the read time when it is missing or unparseable), label `level` (`UNKNOWN` when missing), take
  `service` from the deployment (prod's Docker `cowork.service` label, the local
  `/var/log/cowork/<service>` directory), and label application lines without `@timestamp` or `level`
  `parse_error="true"`. The local pipeline (`deploy/config/monitoring/alloy/config.alloy`) has no format
  fallback, unlike the defensive multi-format parsing in `deploy/prod/log-agent/config.alloy`. Do not
  grow that prod fallback further — fix the app's log output instead.
- `module` is derived automatically from the logger/caller, not set per call site. Logback's
  `ModuleNameConverter` takes the segment right after `domain`/`global` in `com.cowork.<service>.*`
  loggers (so logs group by feature, not by layer name like `service`/`impl`), and falls back to the
  logger name's immediate parent package for flatter in-house packages and third-party loggers. Go
  uses `AddSource` + the caller function's package name. Node uses `nestjs-pino`'s
  `renameContext: 'module'` (reuses Nest's existing per-class logger context). .NET uses the
  `ILogger` `categoryName` already passed to `LineLoggerProvider.CreateLogger`.
- In local dev, also mirror stdout to `/var/log/cowork/<service>/app.log` (the `cowork_logs` volume)
  so the local Alloy file-tailing pipeline picks it up; production only reads Docker's own captured
  stdout (`/var/lib/docker/containers/*/*-json.log`), so stdout is the one output every service must
  get right.
