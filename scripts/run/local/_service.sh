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
  local id="$1"
  [ -n "$id" ] || return 1

  # numeric pid
  if [[ "$id" =~ ^[0-9]+$ ]]; then
    kill -0 "$id" 2>/dev/null
    return $?
  fi

  # screen session name
  screen -ls 2>/dev/null | grep -q "[[:space:]]\\+\\.[^[:space:]]*${id}[[:space:]]" || \
    screen -ls 2>/dev/null | grep -q "\\.${id}[[:space:]]"
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

  local id
  id="$(current_pid || true)"
  if is_running "$id"; then
    echo "$SERVICE_NAME is already running ($id)."
    echo "Log: $(log_file)"
    exit 0
  fi

  load_env

  (
    cd "$SERVICE_WORKDIR"
    if command -v screen >/dev/null 2>&1; then
      # Prefer screen for long-running local services; detached background processes
      # tend to be reaped by some execution environments.
      screen -S "$SERVICE_NAME" -X quit >/dev/null 2>&1 || true
      # Old macOS screen may not support -Logfile. Redirect output ourselves.
      cmd=""
      for arg in "${SERVICE_COMMAND[@]}"; do
        cmd+=" $(printf '%q' "$arg")"
      done
      screen -dmS "$SERVICE_NAME" bash -lc "cd $(printf '%q' "$SERVICE_WORKDIR"); exec${cmd} >>$(printf '%q' "$(log_file)") 2>&1"
      echo "$SERVICE_NAME" >"$(pid_file)"
    else
      nohup "${SERVICE_COMMAND[@]}" >"$(log_file)" 2>&1 &
      echo $! >"$(pid_file)"
    fi
  )

  id="$(current_pid)"
  echo "Started $SERVICE_NAME in background ($id)."
  echo "Log: $(log_file)"
}

stop_service() {
  local id
  id="$(current_pid || true)"
  if ! is_running "$id"; then
    echo "$SERVICE_NAME is not running."
    rm -f "$(pid_file)"
    return 0
  fi

  if [[ "$id" =~ ^[0-9]+$ ]]; then
    kill "$id"
  else
    screen -S "$id" -X quit || true
  fi

  for _ in {1..30}; do
    if ! is_running "$id"; then
      rm -f "$(pid_file)"
      echo "Stopped $SERVICE_NAME."
      return 0
    fi
    sleep 1
  done

  echo "ERROR: $SERVICE_NAME did not stop within 30 seconds ($id)."
  exit 1
}

status_service() {
  local id
  id="$(current_pid || true)"
  if is_running "$id"; then
    echo "$SERVICE_NAME is running ($id)."
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
