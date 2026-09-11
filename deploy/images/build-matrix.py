#!/usr/bin/env python3
"""Describe image inputs for PR builds and production publishing (no containers)."""
import argparse
import json
import os
import subprocess
from pathlib import Path

CATALOG = json.loads(Path(__file__).with_name("catalog.json").read_text())
GRADLE_FILES = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", ".editorconfig"}
IMAGE_FILES = {".dockerignore", ".github/workflows/cowork-images-ci.yml", ".github/workflows/cowork-prod-cd.yml"}


def affected(service, paths):
    for path in paths:
        if path in IMAGE_FILES or path.startswith(("deploy/images/", ".github/actions/build-image/")):
            return True
        if path.startswith(f"cowork-{service}/"):
            return True
        if CATALOG[service]["build"] == "gradle" and (
            path in GRADLE_FILES or path.startswith(("gradle/", "build-logic/"))
            or (path.startswith("cowork-") and path.endswith("/build.gradle.kts"))
        ):
            # Root-context images configure every included Gradle project.
            return True
    return False


def matrix(environments, paths=None):
    return {"include": [
        {"service": service, "environment": environment, "context": settings[environment],
         "dockerfile": f"cowork-{service}/Dockerfile.{environment}"}
        for service, settings in CATALOG.items() if paths is None or affected(service, paths)
        for environment in environments
    ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("local", "prod", "all"), default="all")
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    paths = None
    if args.base:
        # Include deletions and both paths of renames; the PR base is a merge base.
        paths = subprocess.check_output(
            ["git", "diff", "--name-only", "--no-renames", "-z", f"{args.base}...{args.head}"],
        ).decode().rstrip("\0").split("\0")
    environments = ("local", "prod") if args.environment == "all" else (args.environment,)
    result = json.dumps(matrix(environments, paths), separators=(",", ":"))
    print(result)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"matrix={result}\n")


if __name__ == "__main__":
    main()
