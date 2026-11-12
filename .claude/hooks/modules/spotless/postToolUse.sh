#!/bin/bash
# Formats one file with Spotless in the Gradle module that owns the edited file.
# The module is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
TOOL_NAME=$(jq -r '.tool_name // empty' <<<"$INPUT")
[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0
FILE_PATH=$(jq -r '.tool_input.file_path // empty' <<<"$INPUT")
case "$FILE_PATH" in
    *.java|*.kt|*.kts|*.groovy) ;;
    *) exit 0 ;;
esac
if [[ "$FILE_PATH" != /* ]]; then
    CWD=$(jq -r '.cwd // empty' <<<"$INPUT")
    [[ -n "$CWD" ]] || exit 0
    FILE_PATH="$CWD/$FILE_PATH"
fi
[[ -f "$FILE_PATH" ]] || exit 0

MODULE_DIR=$(dirname "$FILE_PATH")
while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" ]]; do
    MODULE_DIR=$(dirname "$MODULE_DIR")
done
[[ -f "$MODULE_DIR/gradlew" && -f "$MODULE_DIR/build.gradle.kts" ]] || exit 0
grep -Fq 'libs.plugins.spotless' "$MODULE_DIR/build.gradle.kts" || exit 0

echo "[Hook] Running spotlessApply in $(basename "$MODULE_DIR") for $(basename "$FILE_PATH")" >&2
cd "$MODULE_DIR" || exit 0
# spotlessIdeHook formats only this file. Files outside the Spotless targets stay unchanged.
if ./gradlew spotlessApply -q "-PspotlessIdeHook=$FILE_PATH" >&2; then
    echo "[Hook] Format OK" >&2
else
    echo "[Hook] Format failed" >&2
fi
exit 0
