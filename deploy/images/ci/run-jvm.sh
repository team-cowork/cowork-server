#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo 'Usage: run-jvm.sh <gradle|maven|kotlin-toolchain> <module> <absolute-source-root> <absolute-output-dir> <core-test-class>...' >&2
  exit 2
fi

runner=$1
module=$2
[[ $3 = /* && $4 = /* ]] || { echo 'Source and output paths must be absolute.' >&2; exit 2; }
source_root=$(cd "$3" && pwd -P)
mkdir -p "$4"
output=$(cd "$4" && pwd -P)
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
shift 4
test_classes=("$@")
# Exact class identities are supplied by the module Dockerfile, never inferred centrally.
for test_class in "${test_classes[@]}"; do
  [[ "$test_class" =~ ^[A-Za-z_][A-Za-z0-9_.]*$ ]] || { echo "Invalid core test class." >&2; exit 2; }
done
test_selection=$(IFS=,; printf '%s' "${test_classes[*]}")

case "$runner" in
  gradle|maven|kotlin-toolchain) ;;
  *) echo "Unsupported JVM coverage runner: $runner" >&2; exit 2 ;;
esac

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
reports = list(directory.rglob('TEST-*.xml'))
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
    "https://repo.maven.apache.org/maven2/org/jacoco/$artifact/0.8.15/$artifact-0.8.15-$classifier.jar" \
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
    gradle_wrapper="$module_dir/gradlew"
    if [[ ! -f "$gradle_wrapper" ]]; then
      gradle_wrapper="$source_root/gradlew"
    fi
    [[ -f "$gradle_wrapper" ]] || { echo 'Gradle wrapper not found.' >&2; exit 1; }
    gradle_args=(--no-daemon --console=plain --no-build-cache --no-configuration-cache
      --project-dir "$module_dir"
      --init-script "$script_dir/jacoco.init.gradle"
      "-PcoverageProjectDir=$module_dir" "-PcoverageOutput=$output" "-PcoverageTestClasses=$test_selection"
      clean coworkCoverageReport)
    bash "$gradle_wrapper" "${gradle_args[@]}"
    validate_tests "$module_dir/build/test-results/test"
    ;;
  maven)
    cd "$module_dir"
    if [[ -f "$module_dir/mvnw" ]]; then
      maven_command=(bash "$module_dir/mvnw")
    elif [[ -f "$source_root/mvnw" ]]; then
      maven_command=(bash "$source_root/mvnw")
    else
      maven_command=(mvn)
    fi
    "${maven_command[@]}" --batch-mode --no-transfer-progress \
      -Dmaven.test.skip=false -DskipTests=false -Dmaven.test.failure.ignore=false -DfailIfNoTests=true \
      "-Djacoco.destFile=$output/jacoco.exec" "-Djacoco.dataFile=$output/jacoco.exec" \
      -Djacoco.append=true "-Dtest=$test_selection" \
      clean org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent \
      test org.jacoco:jacoco-maven-plugin:0.8.15:report
    validate_tests "$module_dir/target/surefire-reports"
    cp "$module_dir/target/site/jacoco/jacoco.xml" "$output/report.xml"
    ;;
  kotlin-toolchain)
    kotlin_cli=${KOTLIN_CLI:-"$module_dir/kotlin"}
    export KOTLIN_CLI_WRAPPER_ALWAYS_USE_INTRINSIC_VERSION=1
    expected_version=$(sed -n 's/^kotlin_cli_version=//p' "$kotlin_cli")
    if [[ -z "$expected_version" ]] || ! "$kotlin_cli" --version | grep -Fq "version $expected_version "; then
      echo 'Coverage requires the Kotlin Toolchain version pinned by its project wrapper.' >&2
      exit 1
    fi
    tools_dir=${COVERAGE_TOOLS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/cowork-coverage}/jacoco-0.8.15
    mkdir -p "$tools_dir"
    tools_dir=$(cd "$tools_dir" && pwd -P)
    agent="$tools_dir/jacocoagent.jar"
    cli="$tools_dir/jacococli.jar"
    download_jacoco org.jacoco.agent runtime \
      fb5b0036a0899ea97edfa0fc2c7985b55f3f7c5695028163e5e60d4f3cf6075d "$agent"
    download_jacoco org.jacoco.cli nodeps \
      d2b74b20b415163c1f53261e7c5ef4445b788327094208ff821e0c2baf9bc8f1 "$cli"
    cd "$module_dir"
    "$kotlin_cli" clean
    test_args=()
    for test_class in "${test_classes[@]}"; do
      test_args+=("--include-classes=$test_class" "--include-classes=$test_class/*")
    done
    "$kotlin_cli" test "${test_args[@]}" --jvm-args="\"-javaagent:$agent=destfile=$output/jacoco.exec,append=true\""
    validate_tests "$module_dir/build/reports"
    # Artifact names use Kotlin Toolchain's module identity, which need not match the directory.
    # The pinned JVM layout gives production artifacts a jvm suffix and tests jvmTest.
    python3 - "$module_dir/build/artifacts/CompiledJvmArtifact" "$output/kotlin-toolchain-classdirs.txt" <<'PY'
import sys
from pathlib import Path
artifacts = Path(sys.argv[1])
class_dirs = sorted(
    output for artifact in artifacts.glob('*jvm') if artifact.is_dir()
    for name in ('kotlin-output', 'java-output')
    if (output := artifact / name).is_dir() and any(output.rglob('*.class'))
)
if not class_dirs:
    raise SystemExit(f'Kotlin Toolchain production classes not found at {artifacts}')
Path(sys.argv[2]).write_text(''.join(f'{path}\n' for path in class_dirs))
PY
    class_args=()
    while IFS= read -r classes; do
      class_args+=(--classfiles "$classes")
    done < "$output/kotlin-toolchain-classdirs.txt"
    java -jar "$cli" report "$output/jacoco.exec" \
      "${class_args[@]}" --sourcefiles "$module_dir/src" \
      --name "$module" --xml "$output/report.xml"
    ;;
esac

[[ -s "$output/jacoco.exec" && -s "$output/report.xml" ]] || {
  echo 'JaCoCo execution data or XML report is missing.' >&2
  exit 1
}
