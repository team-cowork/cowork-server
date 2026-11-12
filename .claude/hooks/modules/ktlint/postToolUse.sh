#!/bin/bash
# Formats Kotlin files with the ktlint command of the module that owns the edited file.
# The module is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
TOOL_NAME=$(jq -r '.tool_name // empty' <<<"$INPUT")
[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0
FILE_PATH=$(jq -r '.tool_input.file_path // empty' <<<"$INPUT")
case "$FILE_PATH" in
    *.kt|*.kts) ;;
    *) exit 0 ;;
esac
if [[ "$FILE_PATH" != /* ]]; then
    CWD=$(jq -r '.cwd // empty' <<<"$INPUT")
    [[ -n "$CWD" ]] || exit 0
    FILE_PATH="$CWD/$FILE_PATH"
fi
[[ -f "$FILE_PATH" ]] || exit 0

MODULE_DIR=$(dirname "$FILE_PATH")
while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" && ! -f "$MODULE_DIR/mvnw" && ! -f "$MODULE_DIR/kotlin" ]]; do
    MODULE_DIR=$(dirname "$MODULE_DIR")
done

if [[ -f "$MODULE_DIR/gradlew" && -f "$MODULE_DIR/build.gradle.kts" ]] \
    && grep -Fq 'libs.plugins.ktlint' "$MODULE_DIR/build.gradle.kts"; then
    FORMAT_COMMAND=(./gradlew ktlintFormat -q)
elif [[ "$FILE_PATH" == *.kt && -f "$MODULE_DIR/mvnw" && -f "$MODULE_DIR/pom.xml" ]] \
    && grep -Fq '<artifactId>ktlint-maven-plugin</artifactId>' "$MODULE_DIR/pom.xml"; then
    FORMAT_COMMAND=(./mvnw ktlint:format -q)
elif [[ "$FILE_PATH" == *.kt && -f "$MODULE_DIR/kotlin" && -f "$MODULE_DIR/module.yaml" ]] \
    && grep -Fq 'ktlint: enabled' "$MODULE_DIR/module.yaml"; then
    FORMAT_COMMAND=(./kotlin do ktlintFormat)
else
    exit 0
fi

echo "[Hook] Running ${FORMAT_COMMAND[*]} in $(basename "$MODULE_DIR") for $(basename "$FILE_PATH")" >&2
cd "$MODULE_DIR" || exit 0
if "${FORMAT_COMMAND[@]}" >&2; then
    echo "[Hook] Format OK" >&2
else
    echo "[Hook] Format failed" >&2
fi
exit 0
