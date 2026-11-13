#!/bin/bash
# Formats each edited Go file like the module's golangci-lint formatters (gofmt and goimports).
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

find_tool() {
    command -v "$1" 2>/dev/null && return 0
    local gopath
    gopath=$(go env GOPATH 2>/dev/null) || return 1
    [[ -x "$gopath/bin/$1" ]] && echo "$gopath/bin/$1"
}

GOLANGCI_LINT=$(find_tool golangci-lint)
GOIMPORTS=$(find_tool goimports)
GOLANGCI_FMT=false
[[ -n "$GOLANGCI_LINT" ]] && "$GOLANGCI_LINT" fmt --help >/dev/null 2>&1 && GOLANGCI_FMT=true

while IFS= read -r FILE_PATH; do
    case "$FILE_PATH" in
        */vendor/*) continue ;;
        *.go) ;;
        *) continue ;;
    esac
    MODULE_DIR=$(dirname "$FILE_PATH")
    while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/go.mod" ]]; do
        MODULE_DIR=$(dirname "$MODULE_DIR")
    done
    [[ -f "$MODULE_DIR/go.mod" ]] || continue
    RELATIVE_PATH="${FILE_PATH#"$MODULE_DIR"/}"
    MODULE_PATH=$(awk '$1 == "module" { print $2; exit }' "$MODULE_DIR/go.mod")

    # golangci-lint v2 reads the gofmt and goimports settings from .golangci.yml, like CI does.
    if [[ "$GOLANGCI_FMT" == true && -f "$MODULE_DIR/.golangci.yml" ]]; then
        FORMAT_COMMAND=("$GOLANGCI_LINT" fmt --config .golangci.yml "$RELATIVE_PATH")
    elif [[ -n "$GOIMPORTS" ]]; then
        # The local prefix of each .golangci.yml is the go.mod module path.
        FORMAT_COMMAND=("$GOIMPORTS" -w ${MODULE_PATH:+-local "$MODULE_PATH"} "$RELATIVE_PATH")
    elif command -v gofmt >/dev/null 2>&1; then
        echo "[Hook] golangci-lint v2 and goimports are not installed. Only gofmt runs, so import grouping is not checked." >&2
        FORMAT_COMMAND=(gofmt -w "$RELATIVE_PATH")
    else
        echo "[Hook] No Go formatter is installed. Install Go to enable formatting." >&2
        exit 0
    fi

    echo "[Hook] Running $(basename "${FORMAT_COMMAND[0]}") in $(basename "$MODULE_DIR") for $RELATIVE_PATH" >&2
    if (cd "$MODULE_DIR" && "${FORMAT_COMMAND[@]}" >&2); then
        echo "[Hook] Format OK" >&2
    else
        echo "[Hook] Format failed" >&2
    fi
done < <(edited_files)
exit 0
