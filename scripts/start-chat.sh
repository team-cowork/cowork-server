#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  echo "ERROR: .env file not found at $PROJECT_ROOT/.env"
  echo "Copy .env.example to .env and fill in the required values."
  exit 1
fi

set -a
source "$PROJECT_ROOT/.env"
set +a

cd "$PROJECT_ROOT/cowork-chat"
npm run start:dev
