#!/usr/bin/env bash
# Optional native adapters selected explicitly by a module's Dockerfile verify stage.
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo "Usage: $0 <go|jest|node|elixir> <module> <absolute-source-root> <absolute-output-dir> <core-test-target>..." >&2
  exit 2
fi

runner=$1
module=$2
source_root=$3
output_dir=$4
shift 4
# The Dockerfile owns the reviewed core-business test selection. Never discover tests here.
test_targets=("$@")
tool_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

case "$source_root:$output_dir" in
  /*:/*) ;;
  *) echo "Source and output directories must be absolute paths." >&2; exit 2 ;;
esac
case "$runner" in
  go|jest|node|elixir) ;;
  *) echo "Unsupported coverage runner: $runner" >&2; exit 2 ;;
esac

source_root=$(cd "$source_root" && pwd -P)
module_dir=$(python3 - "$source_root" "$module" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
relative = Path(sys.argv[2])
module = (root / relative).resolve()
if relative.is_absolute() or '..' in relative.parts or module == root or not module.is_relative_to(root):
    raise SystemExit('Module must be a relative directory inside the source checkout')
if not module.is_dir():
    raise SystemExit(f'Module is absent: {relative}')
print(module)
PY
)
mkdir -p "$output_dir"
output_dir=$(cd "$output_dir" && pwd -P)
rm -f "$output_dir/report.json" "$output_dir/report.out" \
  "$output_dir/raw-go.out" "$output_dir/coverage-summary.json" \
  "$output_dir/coverage.coverdata"
cd "$module_dir"
for target in "${test_targets[@]}"; do
  case "$target" in
    -*|/*|*..*|*\**|*\?*) echo "Core test targets must be explicit module-relative paths." >&2; exit 2 ;;
  esac
  if [[ "$runner" = go ]]; then
    [[ -d "$target" ]] || { echo "Core test package is absent." >&2; exit 2; }
  else
    [[ -f "$target" ]] || { echo "Core test file is absent." >&2; exit 2; }
  fi
done

case "$runner" in
  go)
    go test -coverpkg=./... -covermode=atomic -coverprofile="$output_dir/raw-go.out" "${test_targets[@]}"
    # Swagger's generated registration code is not application code. Keep the
    # unfiltered profile as evidence; the canonical profile excludes only it.
    awk 'NR == 1 || $1 !~ /\/swagger\/docs\.go:/' \
      "$output_dir/raw-go.out" > "$output_dir/report.out"
    ;;
  jest)
    npm ci --no-audit --no-fund
    # Reuse test's configuration flags and environment for both invocations. Extra
    # separators preserve appended Jest arguments through ordinary npm run chains.
    forwarding=$(python3 - <<'PY'
import json
import re
import shlex
from pathlib import Path
scripts = json.loads(Path('package.json').read_text()).get('scripts', {})
script = scripts.get('test')
if not script:
    print(-1)
    raise SystemExit(0)
seen = set()
separators = 0
name = 'test'
while True:
    if name in seen:
        raise SystemExit('Cyclic npm test-script forwarding is unsupported')
    seen.add(name)
    lexer = shlex.shlex(script, posix=True, punctuation_chars=';&|<>')
    lexer.whitespace_split = True
    tokens = list(lexer)
    if any(token and all(character in ';&|<>' for character in token) for token in tokens):
        raise SystemExit('Jest test scripts with shell chains or redirection need an explicit adapter')
    if tokens and tokens[0] in ('cross-env', 'env'):
        tokens.pop(0)
    while tokens and re.match(r'^[A-Za-z_][A-Za-z0-9_]*=', tokens[0]):
        tokens.pop(0)
    if not tokens or tokens[0] != 'npm':
        break
    tokens = tokens[1:]
    while tokens and tokens[0] in ('--silent', '-s', '--quiet', '-q'):
        tokens.pop(0)
    if not tokens or tokens[0] not in ('run', 'run-script'):
        raise SystemExit('Only npm run <script> forwarding is supported for Jest test scripts')
    tokens = tokens[1:]
    while tokens and tokens[0] in ('--silent', '-s', '--quiet', '-q'):
        tokens.pop(0)
    if not tokens or tokens[0].startswith('-'):
        raise SystemExit('The forwarded npm test script could not be resolved')
    name = tokens[0]
    if '--' not in tokens[1:]:
        separators += 1
    script = scripts.get(name)
    if not script:
        raise SystemExit(f'Forwarded npm test script does not exist: {name}')
print(separators)
PY
)
    if [[ "$forwarding" -lt 0 ]]; then
      jest_command=(npm exec --yes=false -- jest)
    else
      jest_command=(npm --silent test --)
      while [[ "$forwarding" -gt 0 ]]; do
        jest_command+=(--)
        forwarding=$((forwarding - 1))
      done
    fi
    # Resolve the module's own root, transforms, test selection and coverage scope.
    # Only append shared exclusions; never replace a declared production-file scope.
    "${jest_command[@]}" --showConfig > "$output_dir/jest-config.json"
    python3 - "$output_dir/jest-config.json" "$output_dir/jest-coverage-globs.txt" <<'PY'
import json
import sys
from pathlib import Path
configs = json.loads(Path(sys.argv[1]).read_text())['configs']
scopes = [config.get('collectCoverageFrom') or ['**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}'] for config in configs]
if not scopes or any(scope != scopes[0] for scope in scopes):
    raise SystemExit('Jest projects with different coverage scopes require a project-aware adapter')
patterns = scopes[0] + [
    '!**/*.{spec,test}.{js,jsx,mjs,cjs,ts,tsx,mts,cts}',
    '!**/*.d.{ts,mts,cts}',
    '!**/{test,tests,__tests__,node_modules,generated,build,dist,coverage}/**',
]
if any(not isinstance(pattern, str) or '\n' in pattern or '\r' in pattern for pattern in patterns):
    raise SystemExit('Invalid Jest coverage glob')
Path(sys.argv[2]).write_text(''.join(f'{pattern}\n' for pattern in patterns))
PY
    coverage_args=()
    while IFS= read -r pattern; do
      coverage_args+=("--collectCoverageFrom=$pattern")
    done < "$output_dir/jest-coverage-globs.txt"
    "${jest_command[@]}" --runTestsByPath "${test_targets[@]}" --coverage --runInBand \
      --coverageDirectory="$output_dir" \
      --coverageReporters=json-summary --coverageReporters=text-summary \
      --coverageThreshold='{}' "${coverage_args[@]}"
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
      --all --src=. --include='**/*.{js,jsx,mjs,cjs,ts,tsx,mts,cts}' \
      --exclude='**/*.{spec,test}.{js,jsx,mjs,cjs,ts,tsx,mts,cts}' \
      --exclude='**/*.d.{ts,mts,cts}' \
      --exclude='**/{test,tests,__tests__,node_modules,generated,build,dist,coverage}/**' \
      --check-coverage=false \
      --reporter=json-summary --reporter=text-summary \
      --reports-dir="$output_dir" --temp-directory="$coverage_temp/v8" \
      node --test "${test_targets[@]}"
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
    mix test --no-start --cover --export-coverage coverage "${test_targets[@]}" || test_status=$?
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
