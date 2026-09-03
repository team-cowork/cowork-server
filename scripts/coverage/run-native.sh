#!/usr/bin/env bash
# Native reports share the PR's measurement settings across both source revisions.
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <go|jest|node|elixir> <module> <absolute-source-root> <absolute-output-dir>" >&2
  exit 2
fi

runner=$1
module=$2
source_root=$3
output_dir=$4
tool_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

case "$source_root:$output_dir" in
  /*:/*) ;;
  *) echo "Source and output directories must be absolute paths." >&2; exit 2 ;;
esac
case "$runner:$module" in
  go:cowork-authorization|go:cowork-notification|go:cowork-voice|jest:cowork-chat|node:cowork-promotion|elixir:cowork-user) ;;
  *) echo "Unsupported coverage runner/module: $runner/$module" >&2; exit 2 ;;
esac

mkdir -p "$output_dir"
rm -f "$output_dir/report.json" "$output_dir/report.out" \
  "$output_dir/raw-go.out" "$output_dir/coverage-summary.json" \
  "$output_dir/coverage.coverdata"
cd "$source_root/$module"

case "$runner" in
  go)
    go test -coverpkg=./... -covermode=atomic -coverprofile="$output_dir/raw-go.out" ./...
    # Swagger's generated registration code is not application code. Keep the
    # unfiltered profile as evidence; the canonical profile excludes only it.
    awk 'NR == 1 || $1 !~ /\/docs\/docs\.go:/' \
      "$output_dir/raw-go.out" > "$output_dir/report.out"
    ;;
  jest)
    npm ci --no-audit --no-fund
    npm run test:cov -- --runInBand \
      --rootDir="$source_root/$module/src" \
      --coverageDirectory="$output_dir" \
      --coverageReporters=json-summary --coverageReporters=text-summary \
      --coverageThreshold='{}' \
      --collectCoverageFrom='**/*.{ts,js}' \
      --collectCoverageFrom='!**/*.{spec,test}.{ts,js}' \
      --collectCoverageFrom='!**/*.d.ts' \
      --collectCoverageFrom='!**/__tests__/**' \
      --collectCoverageFrom='!**/node_modules/**' \
      --collectCoverageFrom='!**/generated/**'
    cp "$output_dir/coverage-summary.json" "$output_dir/report.json"
    ;;
  node)
    npm ci --no-audit --no-fund
    coverage_temp=$(mktemp -d "${TMPDIR:-/tmp}/cowork-c8.XXXXXX")
    trap 'rm -rf "$coverage_temp"' EXIT
    # An explicit configuration prevents either checkout's local c8/nyc config
    # from changing the measurement. --all includes files never loaded by tests.
    printf '{}\n' > "$coverage_temp/config.json"
    npm exec --yes --package=c8@12.0.0 -- c8 \
      --config="$coverage_temp/config.json" \
      --all --src=src --src=scripts \
      --include='src/**/*.{js,mjs,cjs,ts}' --include='scripts/**/*.{js,mjs,cjs,ts}' \
      --exclude='**/*.{spec,test}.{js,mjs,cjs,ts}' \
      --exclude='**/*.d.ts' --exclude='**/node_modules/**' \
      --exclude='**/generated/**' --exclude='**/__tests__/**' \
      --check-coverage=false \
      --reporter=json-summary --reporter=text-summary \
      --reports-dir="$output_dir" --temp-directory="$coverage_temp/v8" \
      npm test
    cp "$output_dir/coverage-summary.json" "$output_dir/report.json"
    ;;
  elixir)
    export MIX_ENV=test
    mix local.hex --force
    mix local.rebar --force
    mix deps.get
    # Exporting skips Mix's default 90% summary threshold. --no-start preserves
    # the existing test suite's isolation from databases, Kafka, and services.
    rm -f cover/coverage.coverdata
    test_status=0
    mix test --no-start --cover --export-coverage coverage || test_status=$?
    if [[ -f cover/coverage.coverdata ]]; then
      cp cover/coverage.coverdata "$output_dir/coverage.coverdata"
    fi
    if [[ "$test_status" -ne 0 ]]; then
      exit "$test_status"
    fi
    elixir "$tool_dir/export-elixir.exs" \
      "$output_dir/coverage.coverdata" "$output_dir/report.json"
    ;;
esac
