"""Release field definitions and read-only comparisons shared by CI and CD."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path.cwd()
VERSION = re.compile(r"[0-9]{8}\.[0-9]+")


def field(path):
    if re.fullmatch(r"cowork-[a-z-]+/build.gradle.kts", path):
        return r'(?m)^(version = ")[^"]+("\s*)$'
    if re.fullmatch(r"cowork-[a-z-]+/pom.xml", path):
        return r'(<artifactId>cowork-[^<]+</artifactId>\s*<version>)[^<]+(</version>)'
    if path == "cowork-user/src/CoworkUser.csproj":
        return r'(<InformationalVersion>)[^<]+(</InformationalVersion>)'
    if path in {"cowork-authorization/cmd/main.go", "cowork-notification/cmd/server/main.go",
                "cowork-voice/cmd/server/main.go"}:
        return r'(?m)^(// @version\s+)\S+([^\S\n]*)$'
    if path == "cowork-chat/src/main.ts":
        return r"(\.setVersion\(')[^']+('\))"
    return None


def rewrite(path, content, version):
    if re.fullmatch(r"cowork-[a-z-]+/package(?:-lock)?.json", path):
        data = json.loads(content)
        if "version" not in data:
            raise ValueError(f"Missing root package version: {path}")
        data["version"] = version + ".0"
        if path.endswith("/package-lock.json") and "" in data.get("packages", {}):
            data["packages"][""]["version"] = version + ".0"
        return json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    pattern = field(path)
    if pattern is None:
        raise ValueError(f"Unregistered version field: {path}")
    updated, count = re.subn(pattern, lambda match: match[1] + version + match[2], content)
    if count != 1:
        raise ValueError(f"Expected one version field in {path}, found {count}")
    return updated


def normalized(path, content):
    if field(path) or re.fullmatch(r"cowork-[a-z-]+/package(?:-lock)?.json", path):
        try:
            return rewrite(path, content, "0.0")
        except (ValueError, TypeError, KeyError):
            pass
    return content


def module_files(module):
    catalog = json.loads((ROOT / "deploy/images/catalog.json").read_text())
    if module not in catalog:
        raise ValueError(f"Unknown image module: {module}")
    directory = ROOT / f"cowork-{module}"
    candidates = [directory / name for name in (
        "build.gradle.kts", "pom.xml", "package.json", "package-lock.json",
        "src/CoworkUser.csproj", "cmd/main.go", "cmd/server/main.go",
    )]
    if module == "chat":
        candidates.append(directory / "src/main.ts")
    paths = [path.relative_to(ROOT).as_posix() for path in candidates if path.is_file()]
    if not paths:
        raise ValueError(f"No registered version fields for {module}")
    return paths


def current_version(module):
    for path in module_files(module):
        content = (ROOT / path).read_text()
        if path.endswith("/package.json"):
            version = json.loads(content)["version"].removesuffix(".0")
        elif field(path):
            match = re.search(field(path), content)
            if match is None:
                raise ValueError(f"Missing version field: {path}")
            version = match[0][len(match[1]):len(match[0]) - len(match[2]) or None]
        else:
            continue
        if VERSION.fullmatch(version):
            return version
    raise ValueError(f"No valid release version for {module}")


def changed_paths(base, head):
    paths = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z", base, head], text=True,
    ).strip("\0").split("\0")
    changed = []
    for path in filter(None, paths):
        if field(path) or re.fullmatch(r"cowork-[a-z-]+/package(?:-lock)?.json", path):
            contents = [subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True) for ref in (base, head)]
            if all(result.returncode == 0 for result in contents):
                if normalized(path, contents[0].stdout.decode()) == normalized(path, contents[1].stdout.decode()):
                    continue
        changed.append(path)
    return changed
