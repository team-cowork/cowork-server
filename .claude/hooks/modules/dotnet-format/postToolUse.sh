#!/bin/bash
# Formats one C# file with dotnet format in the project that owns the edited file.
# The project is found from the file path, so the hook works when the session cwd moves.
INPUT=$(cat)
TOOL_NAME=$(jq -r '.tool_name // empty' <<<"$INPUT")
[[ "$TOOL_NAME" == "Edit" || "$TOOL_NAME" == "Write" ]] || exit 0
FILE_PATH=$(jq -r '.tool_input.file_path // empty' <<<"$INPUT")
case "$FILE_PATH" in
    */bin/*|*/obj/*) exit 0 ;;
    *.cs) ;;
    *) exit 0 ;;
esac
if [[ "$FILE_PATH" != /* ]]; then
    CWD=$(jq -r '.cwd // empty' <<<"$INPUT")
    [[ -n "$CWD" ]] || exit 0
    FILE_PATH="$CWD/$FILE_PATH"
fi
[[ -f "$FILE_PATH" ]] || exit 0

PROJECT_DIR=$(dirname "$FILE_PATH")
while [[ "$PROJECT_DIR" != / ]] && ! compgen -G "$PROJECT_DIR/*.csproj" >/dev/null; do
    PROJECT_DIR=$(dirname "$PROJECT_DIR")
done
compgen -G "$PROJECT_DIR/*.csproj" >/dev/null || exit 0

DOTNET=$(command -v dotnet 2>/dev/null)
for candidate in "${DOTNET_ROOT:-}/dotnet" "$HOME/.dotnet/dotnet" /usr/local/share/dotnet/dotnet; do
    [[ -n "$DOTNET" ]] && break
    [[ -x "$candidate" ]] && DOTNET="$candidate"
done
if [[ -z "$DOTNET" ]]; then
    echo "[Hook] dotnet is not installed. Install the .NET SDK to enable C# formatting." >&2
    exit 0
fi

RELATIVE_PATH="${FILE_PATH#"$PROJECT_DIR"/}"
echo "[Hook] Running dotnet format whitespace in $(basename "$PROJECT_DIR") for $RELATIVE_PATH" >&2
cd "$PROJECT_DIR" || exit 0
# Whitespace formatting follows .editorconfig and needs no restore or build.
# Style and analyzer fixes are left out: they need a build and can change code meaning.
if "$DOTNET" format whitespace . --folder --include "$RELATIVE_PATH" >&2; then
    echo "[Hook] Format OK" >&2
else
    echo "[Hook] Format failed" >&2
fi
exit 0
