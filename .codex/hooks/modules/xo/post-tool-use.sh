#!/bin/bash
# Fixes each edited JavaScript or TypeScript file with the XO version of the package that owns it.
# The package is found from the file path, so the hook works when the session cwd moves.
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
        */node_modules/*|*/dist/*) continue ;;
        *.ts|*.tsx|*.mts|*.cts|*.js|*.jsx|*.mjs|*.cjs) ;;
        *) continue ;;
    esac
    MODULE_DIR=$(dirname "$FILE_PATH")
    while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/package.json" ]]; do
        MODULE_DIR=$(dirname "$MODULE_DIR")
    done
    [[ -f "$MODULE_DIR/package.json" ]] || continue
    jq -e '(.devDependencies.xo // .dependencies.xo) != null' "$MODULE_DIR/package.json" >/dev/null 2>&1 || continue

    if [[ ! -x "$MODULE_DIR/node_modules/.bin/xo" ]]; then
        if [[ -f "$MODULE_DIR/bun.lock" ]]; then INSTALL_COMMAND="bun install"; else INSTALL_COMMAND="npm ci"; fi
        echo "[Hook] XO is not installed in $(basename "$MODULE_DIR"). Run '$INSTALL_COMMAND' there to enable formatting." >&2
        continue
    fi

    RELATIVE_PATH="${FILE_PATH#"$MODULE_DIR"/}"
    echo "[Hook] Running xo --fix in $(basename "$MODULE_DIR") for $RELATIVE_PATH" >&2
    if (cd "$MODULE_DIR" && ./node_modules/.bin/xo --fix "$RELATIVE_PATH" >&2); then
        echo "[Hook] Format OK" >&2
    else
        echo "[Hook] XO fixed what it could. Lint problems above remain." >&2
    fi
done < <(edited_files)
exit 0
