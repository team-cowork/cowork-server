#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  echo "ERROR: .env file not found at $PROJECT_ROOT/.env"
  exit 1
fi

set -a
source "$PROJECT_ROOT/.env"
set +a

cd "$PROJECT_ROOT"
./gradlew :cowork-team:bootRun
