#!/usr/bin/env python3
"""Select image and verification builds without inferring a service runtime."""
import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = json.loads(Path(__file__).with_name("catalog.json").read_text())
SHARED_FILES = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", ".editorconfig"}
IMAGE_FILES = {".dockerignore", ".github/workflows/cowork-stage-ci.yml",
               ".github/workflows/cowork-prod-ci.yml", ".github/workflows/cowork-prod-cd.yml"}


def documentation_path(path):
    """Exclude prose, not Markdown assets packaged inside application sources."""
    parts = Path(path).parts
    return (path.startswith(("docs/", ".agents/", ".claude/", ".codex/"))
            or "docs" in parts[:-1]
            or Path(path).name in {"README.md", "AGENTS.md", "CONTRIBUTING.md", "PR_BODY.md"})


def affected(service, paths):
    for path in paths:
        # The promotion site consumes TODO prose as its content, even though it has no deployment image.
        if service == "promotion" and path.startswith("docs/todo/"):
            return True
        if documentation_path(path):
            continue
        if path in IMAGE_FILES or path.startswith(("deploy/images/", ".github/actions/build-image/", ".github/actions/verify-image/")):
            return True
        if path.startswith(f"cowork-{service}/"):
            return True
        # All Dockerfiles have a root context. Shared build input changes
        # conservatively select every image; the selected Dockerfile owns its toolchain.
        if "/" not in path or path in SHARED_FILES or path.startswith(("gradle/", "build-logic/", "scripts/")) or (
            path.startswith("cowork-") and path.endswith(("/build.gradle.kts", "/build.gradle"))
        ):
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
        paths = subprocess.check_output(
            ["git", "diff", "--name-only", "--no-renames", "-z", f"{args.base}...{args.head}"],
        ).decode().rstrip("\0").split("\0")
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
