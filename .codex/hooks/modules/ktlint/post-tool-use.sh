#!/bin/bash
# Formats Kotlin files with the ktlint command of the module that owns each edited file.
# The module is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
CWD=$(jq -r '.cwd // empty' <<<"$INPUT")

# Prints each edited file as an absolute path. Codex sends file edits as apply_patch
# (tool_input.command holds the patch); Edit/Write-style payloads use file_path or path.
edited_files() {
    local tool_name text
    tool_name=$(jq -r '.tool_name // empty' <<<"$INPUT")
    case "$tool_name" in
        apply_patch|Edit|Write|write_file|Bash) ;;
        *) return 0 ;;
    esac
    {
        jq -r '.tool_input | objects | (.file_path, .path) | strings' <<<"$INPUT"
        text=$(jq -r '.tool_input
            | if type == "object" then (.command // .input // .patch // empty) else . end
            | if type == "array" then join("\n") elif type == "string" then . else empty end' <<<"$INPUT")
        if [[ "$tool_name" != Bash || "$text" == *'*** Begin Patch'* ]]; then
            sed -nE 's/^\*\*\* (Add File|Update File|Move to): (.+)$/\2/p' <<<"$text"
        fi
    } | while IFS= read -r path; do
        [[ -n "$path" ]] || continue
        if [[ "$path" != /* ]]; then
            [[ -n "$CWD" ]] || continue
            path="$CWD/$path"
        fi
        [[ -f "$path" ]] && echo "$path"
    done | sort -u
}

SEEN_MODULES=" "
while IFS= read -r FILE_PATH; do
    case "$FILE_PATH" in
        *.kt|*.kts) ;;
        *) continue ;;
    esac
    MODULE_DIR=$(dirname "$FILE_PATH")
    while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" && ! -f "$MODULE_DIR/mvnw" && ! -f "$MODULE_DIR/kotlin" ]]; do
        MODULE_DIR=$(dirname "$MODULE_DIR")
    done
    # ktlintFormat formats the whole module, so run it once for each module.
    [[ "$SEEN_MODULES" == *" $MODULE_DIR "* ]] && continue

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
        continue
    fi
    SEEN_MODULES+="$MODULE_DIR "

    echo "[Hook] Running ${FORMAT_COMMAND[*]} in $(basename "$MODULE_DIR") for $(basename "$FILE_PATH")" >&2
    if (cd "$MODULE_DIR" && "${FORMAT_COMMAND[@]}" >&2); then
        echo "[Hook] Format OK" >&2
    else
        echo "[Hook] Format failed" >&2
    fi
done < <(edited_files)
exit 0
