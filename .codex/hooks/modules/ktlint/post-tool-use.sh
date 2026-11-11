#!/bin/bash
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name')
if [[ "$TOOL_NAME" == "Edit" ]] || [[ "$TOOL_NAME" == "Write" ]] || [[ "$TOOL_NAME" == "write_file" ]]; then
    FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // .tool_input.path // empty')
    CWD=$(echo "$INPUT" | jq -r '.cwd // empty')
    [[ -n "$CWD" ]] || exit 0
    case "$FILE_PATH" in
        *.kt)
            [[ "$FILE_PATH" = /* ]] || FILE_PATH="$CWD/$FILE_PATH"
            MODULE_DIR=$(dirname "$FILE_PATH")
            while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" && ! -f "$MODULE_DIR/ktlint" && ! -f "$MODULE_DIR/mvnw" && ! -f "$MODULE_DIR/kotlin" ]]; do
                MODULE_DIR=$(dirname "$MODULE_DIR")
            done
            if [[ -f "$MODULE_DIR/ktlint" && -x "$MODULE_DIR/ktlint" ]]; then
                FORMAT_COMMAND=(./ktlint --format)
            elif [[ -f "$MODULE_DIR/gradlew" && -f "$MODULE_DIR/build.gradle.kts" ]] \
                && grep -Fq 'libs.plugins.ktlint' "$MODULE_DIR/build.gradle.kts"; then
                FORMAT_COMMAND=(./gradlew ktlintFormat -q)
            elif [[ -f "$MODULE_DIR/mvnw" && -f "$MODULE_DIR/pom.xml" ]] \
                && grep -Fq '<artifactId>ktlint-maven-plugin</artifactId>' "$MODULE_DIR/pom.xml"; then
                FORMAT_COMMAND=(./mvnw ktlint:format -q)
            elif [[ -f "$MODULE_DIR/kotlin" && -f "$MODULE_DIR/module.yaml" ]] \
                && grep -Fq 'ktlint: enabled' "$MODULE_DIR/module.yaml"; then
                FORMAT_COMMAND=(./kotlin do ktlintFormat)
            else
                exit 0
            fi
            echo "[Hook] Running ${FORMAT_COMMAND[*]} for $(basename "$FILE_PATH")" >&2
            cd "$MODULE_DIR" || exit 0
            if "${FORMAT_COMMAND[@]}" 2>&1; then
                echo "[Hook] Format OK" >&2
            else
                echo "[Hook] Format failed" >&2
            fi
            ;;
    esac
fi
exit 0
