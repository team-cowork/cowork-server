#!/bin/bash
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')
if [[ "$TOOL_NAME" == "Edit" ]] || [[ "$TOOL_NAME" == "Write" ]]; then
    FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
    CWD=$(echo "$INPUT" | jq -r '.cwd // empty')
    [[ -n "$CWD" ]] || exit 0
    case "$FILE_PATH" in
        *.java|*.kt|*.groovy)
            [[ "$FILE_PATH" = /* ]] || FILE_PATH="$CWD/$FILE_PATH"
            MODULE_DIR=$(dirname "$FILE_PATH")
            while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" ]]; do
                MODULE_DIR=$(dirname "$MODULE_DIR")
            done
            [[ -f "$MODULE_DIR/gradlew" && -f "$MODULE_DIR/build.gradle.kts" ]] || exit 0
            grep -Fq 'libs.plugins.spotless' "$MODULE_DIR/build.gradle.kts" || exit 0
            echo "[Hook] Running spotlessApply for $(basename "$FILE_PATH")" >&2
            cd "$MODULE_DIR" || exit 0
            if ./gradlew spotlessApply -q 2>&1; then
                echo "[Hook] Format OK" >&2
            else
                echo "[Hook] Format failed" >&2
            fi
            ;;
    esac
fi
exit 0
