#!/bin/bash
# Fixes one JavaScript or TypeScript file with the XO version of the package that owns it.
# The package is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
TOOL_NAME=$(jq -r '.tool_name // empty' <<<"$INPUT")
[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0
FILE_PATH=$(jq -r '.tool_input.file_path // empty' <<<"$INPUT")
case "$FILE_PATH" in
    */node_modules/*|*/dist/*) exit 0 ;;
    *.ts|*.tsx|*.mts|*.cts|*.js|*.jsx|*.mjs|*.cjs) ;;
    *) exit 0 ;;
esac
if [[ "$FILE_PATH" != /* ]]; then
    CWD=$(jq -r '.cwd // empty' <<<"$INPUT")
    [[ -n "$CWD" ]] || exit 0
    FILE_PATH="$CWD/$FILE_PATH"
fi
[[ -f "$FILE_PATH" ]] || exit 0

MODULE_DIR=$(dirname "$FILE_PATH")
while [[ "$MODULE_DIR" != / && ! -f "$MODULE_DIR/package.json" ]]; do
    MODULE_DIR=$(dirname "$MODULE_DIR")
done
[[ -f "$MODULE_DIR/package.json" ]] || exit 0
jq -e '(.devDependencies.xo // .dependencies.xo) != null' "$MODULE_DIR/package.json" >/dev/null 2>&1 || exit 0

if [[ ! -x "$MODULE_DIR/node_modules/.bin/xo" ]]; then
    if [[ -f "$MODULE_DIR/bun.lock" ]]; then INSTALL_COMMAND="bun install"; else INSTALL_COMMAND="npm ci"; fi
    echo "[Hook] XO is not installed in $(basename "$MODULE_DIR"). Run '$INSTALL_COMMAND' there to enable formatting." >&2
    exit 0
fi

RELATIVE_PATH="${FILE_PATH#"$MODULE_DIR"/}"
echo "[Hook] Running xo --fix in $(basename "$MODULE_DIR") for $RELATIVE_PATH" >&2
cd "$MODULE_DIR" || exit 0
if ./node_modules/.bin/xo --fix "$RELATIVE_PATH" >&2; then
    echo "[Hook] Format OK" >&2
else
    echo "[Hook] XO fixed what it could. Lint problems above remain." >&2
fi
exit 0
