#!/bin/bash
# Formats one Go file like the module's golangci-lint formatters (gofmt and goimports).
# The module is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
TOOL_NAME=$(jq -r '.tool_name // empty' <<<"$INPUT")
[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0
FILE_PATH=$(jq -r '.tool_input.file_path // empty' <<<"$INPUT")
case "$FILE_PATH" in
    */vendor/*) exit 0 ;;
    *.go) ;;
    *) exit 0 ;;
esac
if [[ "$FILE_PATH" != /* ]]; then
    CWD=$(jq -r '.cwd // empty' <<<"$INPUT")
    [[ -n "$CWD" ]] || exit 0
    FILE_PATH="$CWD/$FILE_PATH"
fi
[[ -f "$FILE_PATH" ]] || exit 0

MODULE_DIR=$(dirname "$FILE_PATH")
while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/go.mod" ]]; do
    MODULE_DIR=$(dirname "$MODULE_DIR")
done
[[ -f "$MODULE_DIR/go.mod" ]] || exit 0
RELATIVE_PATH="${FILE_PATH#"$MODULE_DIR"/}"
cd "$MODULE_DIR" || exit 0

find_tool() {
    command -v "$1" 2>/dev/null && return 0
    local gopath
    gopath=$(go env GOPATH 2>/dev/null) || return 1
    [[ -x "$gopath/bin/$1" ]] && echo "$gopath/bin/$1"
}

GOLANGCI_LINT=$(find_tool golangci-lint)
GOIMPORTS=$(find_tool goimports)
MODULE_PATH=$(awk '$1 == "module" { print $2; exit }' go.mod)

# golangci-lint v2 reads the gofmt and goimports settings from .golangci.yml, like CI does.
if [[ -n "$GOLANGCI_LINT" && -f .golangci.yml ]] && "$GOLANGCI_LINT" fmt --help >/dev/null 2>&1; then
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
if "${FORMAT_COMMAND[@]}" >&2; then
    echo "[Hook] Format OK" >&2
else
    echo "[Hook] Format failed" >&2
fi
exit 0
