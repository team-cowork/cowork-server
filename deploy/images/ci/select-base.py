#!/usr/bin/env python3
"""Keep failed push changes in CI until the same branch and workflow succeeds."""
import json
import os
import re
import subprocess
from urllib.parse import urlencode


def api(path):
    result = subprocess.run(
        ["gh", "api", path], check=True, capture_output=True, text=True, timeout=30,
    )
    return json.loads(result.stdout)


def successful_push_base():
    repository = os.environ["GITHUB_REPOSITORY"]
    branch = os.environ["GITHUB_REF_NAME"]
    workflow = api(f"repos/{repository}/actions/runs/{os.environ['GITHUB_RUN_ID']}")["workflow_id"]
    page = 1
    while True:
        query = urlencode({"branch": branch, "event": "push", "status": "success", "per_page": 100, "page": page})
        runs = api(f"repos/{repository}/actions/workflows/{workflow}/runs?{query}")["workflow_runs"]
        for run in runs:
            sha = run["head_sha"]
            if not re.fullmatch(r"[0-9a-f]{40}", sha):
                raise ValueError("Invalid successful commit")
            ancestor = subprocess.run(
                ["git", "merge-base", "--is-ancestor", sha, "HEAD"], capture_output=True,
            )
            if ancestor.returncode == 0:
                return sha
            if ancestor.returncode != 1:
                raise ValueError("Cannot compare successful commit")
        if len(runs) < 100:
            return ""
        page += 1


def main():
    event = os.environ.get("GITHUB_EVENT_NAME")
    base = os.environ.get("PR_BASE_SHA", "") if event == "pull_request" else ""
    if event == "push":
        try:
            base = successful_push_base()
        except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
            print("CI history could not be compared; selecting all modules and images")
    print(f"CI comparison base: {base or 'none (full selection)'}")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"base={base}\n")


if __name__ == "__main__":
    main()
