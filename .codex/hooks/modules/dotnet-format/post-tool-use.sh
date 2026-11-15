#!/bin/bash
# Formats each edited C# file with dotnet format in the project that owns it.
# The project is found from the file path, so the hook works when the session cwd moves.
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

DOTNET=$(command -v dotnet 2>/dev/null)
for candidate in "${DOTNET_ROOT:-}/dotnet" "$HOME/.dotnet/dotnet" /usr/local/share/dotnet/dotnet; do
    [[ -n "$DOTNET" ]] && break
    [[ -x "$candidate" ]] && DOTNET="$candidate"
done

while IFS= read -r FILE_PATH; do
    case "$FILE_PATH" in
        */bin/*|*/obj/*) continue ;;
        *.cs) ;;
        *) continue ;;
    esac
    PROJECT_DIR=$(dirname "$FILE_PATH")
    while [[ "$PROJECT_DIR" != / ]] && ! compgen -G "$PROJECT_DIR/*.csproj" >/dev/null; do
        PROJECT_DIR=$(dirname "$PROJECT_DIR")
    done
    compgen -G "$PROJECT_DIR/*.csproj" >/dev/null || continue
    if [[ -z "$DOTNET" ]]; then
        echo "[Hook] dotnet is not installed. Install the .NET SDK to enable C# formatting." >&2
        exit 0
    fi

    RELATIVE_PATH="${FILE_PATH#"$PROJECT_DIR"/}"
    echo "[Hook] Running dotnet format whitespace in $(basename "$PROJECT_DIR") for $RELATIVE_PATH" >&2
    # Whitespace formatting follows .editorconfig and needs no restore or build.
    # Style and analyzer fixes are left out: they need a build and can change code meaning.
    if (cd "$PROJECT_DIR" && "$DOTNET" format whitespace . --folder --include "$RELATIVE_PATH" >&2); then
        echo "[Hook] Format OK" >&2
    else
        echo "[Hook] Format failed" >&2
    fi
done < <(edited_files)
exit 0
