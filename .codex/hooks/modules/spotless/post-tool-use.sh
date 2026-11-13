#!/bin/bash
# Formats each edited file with Spotless in the Gradle module that owns it.
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

while IFS= read -r FILE_PATH; do
    case "$FILE_PATH" in
        *.java|*.kt|*.kts|*.groovy) ;;
        *) continue ;;
    esac
    MODULE_DIR=$(dirname "$FILE_PATH")
    while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/gradlew" ]]; do
        MODULE_DIR=$(dirname "$MODULE_DIR")
    done
    [[ -f "$MODULE_DIR/gradlew" && -f "$MODULE_DIR/build.gradle.kts" ]] || continue
    grep -Fq 'libs.plugins.spotless' "$MODULE_DIR/build.gradle.kts" || continue

    echo "[Hook] Running spotlessApply in $(basename "$MODULE_DIR") for $(basename "$FILE_PATH")" >&2
    # spotlessIdeHook formats only this file. Files outside the Spotless targets stay unchanged.
    if (cd "$MODULE_DIR" && ./gradlew spotlessApply -q "-PspotlessIdeHook=$FILE_PATH" >&2); then
        echo "[Hook] Format OK" >&2
    else
        echo "[Hook] Format failed" >&2
    fi
done < <(edited_files)
exit 0
