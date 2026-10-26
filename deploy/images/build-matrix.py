#!/usr/bin/env python3
"""Select image and verification builds without inferring a service runtime."""
import argparse
from fnmatch import fnmatchcase
from functools import cache
import json
import os
import re
import runpy
import shlex
import subprocess
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
CATALOG = json.loads(Path(__file__).with_name("catalog.json").read_text())
RELEASE_FIELDS = runpy.run_path(str(ROOT / "deploy/images/release_fields.py"))
changed_paths = RELEASE_FIELDS["changed_paths"]


@cache
def build_inputs(service):
    inputs = set()
    # CD may load these tools from a different checkout than the selected source.
    for dockerfile in (Path.cwd() / f"cowork-{service}").glob("Dockerfile.*"):
        content = dockerfile.read_text()
        # Unsupported context expressions select broadly rather than miss a build.
        if re.search(r"(?im)^\s*#\s*escape\s*=\s*`", content) or any(
            "type=cache" not in mount for mount in re.findall(r"--mount=(\S+)", content)
        ):
            return {"."}
        content = "\n".join(line for line in content.splitlines() if not line.lstrip().startswith("#"))
        content = re.sub(r"\\\n", " ", content)
        for arguments in re.findall(r"(?im)^\s*(?:COPY|ADD)\s+(.+)$", content):
            try:
                flags = []
                while arguments.startswith("--"):
                    flag, arguments = arguments.split(None, 1)
                    flags.append(flag)
                if any(flag.startswith("--from=") for flag in flags):
                    continue
                sources = json.loads(arguments) if arguments.startswith("[") else shlex.split(arguments)
                if not isinstance(sources, list) or len(sources) < 2 or not all(isinstance(source, str) for source in sources):
                    return {"."}
                for source in sources[:-1]:
                    if "$" in source or source.startswith("<<") or ".." in PurePosixPath(source).parts:
                        return {"."}
                    if "://" not in source:
                        inputs.add(str(PurePosixPath(source.lstrip("/"))))
            except ValueError:
                return {"."}
    return inputs


def documentation_path(path):
    """Exclude prose, not Markdown assets packaged inside application sources."""
    parts = Path(path).parts
    return (path.startswith(("docs/", ".agents/", ".claude/", ".codex/"))
            or "docs" in parts[:-1]
            or Path(path).name in {"README.md", "AGENTS.md", "CONTRIBUTING.md", "PR_BODY.md"})


def affected(service, paths):
    for path in paths:
        if documentation_path(path):
            continue
        if path == ".dockerignore" or fnmatchcase(path, ".github/workflows/*-ci.yml") or path.startswith(
            ("deploy/images/", ".github/actions/build-image/", ".github/actions/verify-image/")
        ):
            return True
        if path.startswith(f"cowork-{service}/"):
            return True
        if any(source == "." or fnmatchcase(str(candidate), source)
               for source in build_inputs(service) for candidate in (PurePosixPath(path), *PurePosixPath(path).parents)):
            return True
    return False


def matrix(environments, paths=None):
    return {"include": [
        {"service": service, "environment": environment, "context": settings[environment],
         "dockerfile": f"cowork-{service}/Dockerfile.{environment}"}
        for service, settings in CATALOG.items() if paths is None or affected(service, paths)
        for environment in environments
    ]}


def verification_matrix(environments, paths=None):
    return {"include": [
        {"service": dockerfile.parent.name.removeprefix("cowork-"), "environment": environment,
         "dockerfile": str(dockerfile.relative_to(ROOT))}
        for environment in environments
        for dockerfile in sorted(ROOT.glob(f"cowork-*/Dockerfile.{environment}"))
        if paths is None or affected(dockerfile.parent.name.removeprefix("cowork-"), paths)
    ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("local", "prod", "all"), default="all")
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    paths = None
    if args.base:
        base = subprocess.check_output(["git", "merge-base", args.base, args.head], text=True).strip()
        paths = changed_paths(base, args.head)
    environments = ("local", "prod") if args.environment == "all" else (args.environment,)
    selected = verification_matrix(environments, paths) if args.verify else matrix(environments, paths)
    result = json.dumps(selected, separators=(",", ":"))
    print(result)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"matrix={result}\n")
            output.write("services-list=" + (", ".join(dict.fromkeys(entry["service"] for entry in selected["include"])) or "none") + "\n")


if __name__ == "__main__":
    main()
