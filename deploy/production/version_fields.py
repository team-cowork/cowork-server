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
    if re.fullmatch(r"cowork-(?:authorization|notification|voice)/swagger/docs\.go", path):
        return r'(?m)^(\tVersion:\s+")[^"]+(",[^\S\n]*)$'
    if re.fullmatch(r"cowork-(?:authorization|notification|voice)/swagger/swagger\.json", path):
        return r'(?m)^( {8}"version": ")[^"]+(",?[^\S\n]*)$'
    if re.fullmatch(r"cowork-(?:authorization|notification|voice)/swagger/swagger\.yaml", path):
        return r'(?m)^(  version: ")[^"]+("[^\S\n]*)$'
    if path == "cowork-chat/src/main.ts":
        return r"(\.setVersion\(')[^']+('\))"
    if path in {"cowork-user/src/openapi.json", "cowork-preference/src/main/resources/openapi.json",
                "cowork-chat/public/asyncapi.json"}:
        return r'(?m)^(    "version": ")[^"]+(",?[^\S\n]*)$'
    if re.fullmatch(r"cowork-[a-z-]+/src/main/(?:kotlin|java)/com/cowork/[a-z]+/global/config/OpenApiConfig\.(?:kt|java)", path):
        return r'(\bversion = ")[^"]+(")'
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
        candidates += [directory / "src/main.ts", directory / "public/asyncapi.json"]
    if module == "user":
        candidates.append(directory / "src/openapi.json")
    if module == "preference":
        candidates.append(directory / "src/main/resources/openapi.json")
    if module in {"channel", "project", "team", "roadmap"}:
        language, extension = ("java", "java") if module == "roadmap" else ("kotlin", "kt")
        candidates.append(directory / f"src/main/{language}/com/cowork/{module}/global/config/OpenApiConfig.{extension}")
    if module in {"authorization", "notification", "voice"}:
        candidates += [directory / "swagger" / name for name in ("docs.go", "swagger.json", "swagger.yaml")]
    paths = [path.relative_to(ROOT).as_posix() for path in candidates if path.is_file()]
    if not paths:
        raise ValueError(f"No registered version fields for {module}")
    return paths


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
