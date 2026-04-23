#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
RUN_DIR="$PROJECT_ROOT/.run"
LOG_DIR="$RUN_DIR/logs"

load_env() {
  if [ ! -f "$PROJECT_ROOT/.env" ]; then
    echo "ERROR: .env file not found at $PROJECT_ROOT/.env"
    exit 1
  fi

  set -a
  source "$PROJECT_ROOT/.env"
  set +a
}

pid_file() {
  echo "$RUN_DIR/$SERVICE_NAME.pid"
}

log_file() {
  echo "$LOG_DIR/$SERVICE_NAME.log"
}

is_running() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

current_pid() {
  local file
  file="$(pid_file)"
  if [ -f "$file" ]; then
    cat "$file"
  fi
}

start_service() {
  mkdir -p "$LOG_DIR"

  local pid
  pid="$(current_pid || true)"
  if is_running "$pid"; then
    echo "$SERVICE_NAME is already running (pid $pid)."
    echo "Log: $(log_file)"
    exit 0
  fi

  load_env

  (
    cd "$SERVICE_WORKDIR"
    nohup "${SERVICE_COMMAND[@]}" >"$(log_file)" 2>&1 &
    echo $! >"$(pid_file)"
  )

  pid="$(current_pid)"
  echo "Started $SERVICE_NAME in background (pid $pid)."
  echo "Log: $(log_file)"
}

stop_service() {
  local pid
  pid="$(current_pid || true)"
  if ! is_running "$pid"; then
    echo "$SERVICE_NAME is not running."
    rm -f "$(pid_file)"
    return 0
  fi

  kill "$pid"
  for _ in {1..30}; do
    if ! is_running "$pid"; then
      rm -f "$(pid_file)"
      echo "Stopped $SERVICE_NAME."
      return 0
    fi
    sleep 1
  done

  echo "ERROR: $SERVICE_NAME did not stop within 30 seconds (pid $pid)."
  exit 1
}

status_service() {
  local pid
  pid="$(current_pid || true)"
  if is_running "$pid"; then
    echo "$SERVICE_NAME is running (pid $pid)."
  else
    echo "$SERVICE_NAME is not running."
  fi
}

tail_logs() {
  local file
  file="$(log_file)"
  if [ ! -f "$file" ]; then
    echo "Log file does not exist yet: $file"
    exit 1
  fi
  tail -f "$file"
}

foreground_service() {
  load_env
  cd "$SERVICE_WORKDIR"
  exec "${SERVICE_COMMAND[@]}"
}

run_managed_service() {
  local action="${1:-start}"

  case "$action" in
    start)
      start_service
      ;;
    stop)
      stop_service
      ;;
    restart)
      stop_service
      start_service
      ;;
    status)
      status_service
      ;;
    logs)
      tail_logs
      ;;
    foreground|fg)
      foreground_service
      ;;
    *)
      echo "Usage: $0 [start|stop|restart|status|logs|foreground]"
      exit 1
      ;;
  esac
}
