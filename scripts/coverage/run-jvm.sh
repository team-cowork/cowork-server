#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo 'Usage: run-jvm.sh <gradle|maven|amper> <module> <absolute-source-root> <absolute-output-dir>' >&2
  exit 2
fi

runner=$1
module=$2
[[ $3 = /* && $4 = /* ]] || { echo 'Source and output paths must be absolute.' >&2; exit 2; }
source_root=$(cd "$3" && pwd -P)
output=$(cd "$4" && pwd -P)
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)

case "$runner:$module" in
  gradle:cowork-gateway|gradle:cowork-config|gradle:cowork-channel|gradle:cowork-team|gradle:cowork-roadmap) ;;
  maven:cowork-project|amper:cowork-preference) ;;
  *) echo "Unsupported JVM runner/module: $runner/$module" >&2; exit 2 ;;
esac

module_dir="$source_root/$module"
[[ -d "$module_dir" ]] || { echo "Module is absent: $module" >&2; exit 1; }
case "$output/" in
  "$module_dir/build/"*|"$module_dir/target/"*)
    echo 'Coverage output must be outside the cleaned build/target directory.' >&2
    exit 2
    ;;
esac

# Clear prior data, then allow the agent to append coverage from every test JVM.
# A previous report must never turn a failed or skipped measurement into a success.
rm -f "$output/report.xml" "$output/jacoco.exec"

validate_tests() {
  python3 - "$1" <<'PY'
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

directory = Path(sys.argv[1])
reports = list(directory.glob('TEST-*.xml'))
if not reports:
    raise SystemExit(f'No fresh JUnit reports found in {directory}')
tests = failures = errors = skipped = 0
for report in reports:
    root = ET.parse(report).getroot()
    if root.tag != 'testsuite':
        raise SystemExit(f'Unexpected JUnit report format: {report}')
    tests += int(root.get('tests', '0'))
    failures += int(root.get('failures', '0'))
    errors += int(root.get('errors', '0'))
    skipped += int(root.get('skipped', '0'))
if failures or errors or tests <= skipped:
    raise SystemExit(f'Incomplete test run: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped')
print(f'Validated {tests} tests ({skipped} skipped), no failures or errors.')
PY
}

# Pin both version and SHA-256; the shared cache lives outside either source checkout.
download_jacoco() {
  local artifact=$1 classifier=$2 checksum=$3 destination=$4
  if [[ -f "$destination" ]] && verify_sha256 "$destination" "$checksum"; then
    return
  fi
  local temporary
  temporary=$(mktemp "${destination}.XXXXXX")
  if ! curl --fail --silent --show-error --location --retry 3 \
    "https://repo.maven.apache.org/maven2/org/jacoco/$artifact/0.8.14/$artifact-0.8.14-$classifier.jar" \
    --output "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  if ! verify_sha256 "$temporary" "$checksum"; then
    echo "JaCoCo checksum mismatch: $artifact" >&2
    rm -f "$temporary"
    return 1
  fi
  mv "$temporary" "$destination"
}

verify_sha256() {
  python3 - "$1" "$2" <<'PY'
import hashlib
import sys
from pathlib import Path
sys.exit(0 if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2] else 1)
PY
}

case "$runner" in
  gradle)
    cd "$source_root"
    gradle_args=(--no-daemon --console=plain --no-build-cache --no-configuration-cache
      --init-script "$script_dir/jacoco.init.gradle"
      "-PcoverageModule=$module" "-PcoverageOutput=$output"
      ":$module:clean" ":$module:coworkCoverageReport")
    if [[ "$module" == cowork-roadmap ]]; then
      # compileJava depends on a formatter that writes source files; coverage must not.
      gradle_args+=(-x :cowork-roadmap:spotlessApply)
    fi
    bash ./gradlew "${gradle_args[@]}"
    validate_tests "$module_dir/build/test-results/test"
    ;;
  maven)
    cd "$module_dir"
    bash ./mvnw --batch-mode --no-transfer-progress \
      -Dmaven.test.skip=false -DskipTests=false -Dmaven.test.failure.ignore=false -DfailIfNoTests=true \
      "-Djacoco.destFile=$output/jacoco.exec" "-Djacoco.dataFile=$output/jacoco.exec" \
      -Djacoco.append=true \
      clean org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent \
      test org.jacoco:jacoco-maven-plugin:0.8.14:report
    validate_tests "$module_dir/target/surefire-reports"
    cp "$module_dir/target/site/jacoco/jacoco.xml" "$output/report.xml"
    ;;
  amper)
    kotlin_cli=${KOTLIN_CLI:-kotlin}
    if ! "$kotlin_cli" --version | grep -Eq 'version 0\.11\.0([[:space:]]|$)'; then
      echo 'Coverage requires Kotlin Toolchain (Amper) 0.11.0.' >&2
      exit 1
    fi
    tools_dir=${COVERAGE_TOOLS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/cowork-coverage}/jacoco-0.8.14
    mkdir -p "$tools_dir"
    tools_dir=$(cd "$tools_dir" && pwd -P)
    agent="$tools_dir/jacocoagent.jar"
    cli="$tools_dir/jacococli.jar"
    download_jacoco org.jacoco.agent runtime \
      3fb76eea65f81bd9415202bab34b6571728841dff1ab8e6bbe81adc2e299face "$agent"
    download_jacoco org.jacoco.cli nodeps \
      811c7f8c6b358c5d68a8973cfa867f6892be7a671b697a4b13c4b447e6daf75c "$cli"
    cd "$module_dir"
    "$kotlin_cli" clean
    "$kotlin_cli" test --jvm-args="\"-javaagent:$agent=destfile=$output/jacoco.exec,append=true\""
    validate_tests "$module_dir/build/reports/$module/jvm"
    classes="$module_dir/build/artifacts/CompiledJvmArtifact/${module}jvm"
    # Amper 0.11.0 separates main and test artifacts. Never scan the test artifact.
    python3 - "$classes/kotlin-output" <<'PY'
import sys
from pathlib import Path
if not any(Path(sys.argv[1]).rglob('*.class')):
    raise SystemExit(f'Amper production classes not found at {sys.argv[1]}')
PY
    class_args=(--classfiles "$classes/kotlin-output")
    if [[ -d "$classes/java-output" ]]; then
      class_args+=(--classfiles "$classes/java-output")
    fi
    java -jar "$cli" report "$output/jacoco.exec" \
      "${class_args[@]}" --sourcefiles "$module_dir/src/main/kotlin" \
      --name "$module" --xml "$output/report.xml"
    ;;
esac

[[ -s "$output/jacoco.exec" && -s "$output/report.xml" ]] || {
  echo 'JaCoCo execution data or XML report is missing.' >&2
  exit 1
}
