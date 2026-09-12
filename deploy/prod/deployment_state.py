#!/usr/bin/env python3
"""Record actual successful rollouts separately from Actions environment/check jobs."""
import argparse
import json
import os
import re
import subprocess
from urllib.parse import urlencode

TASK = "cowork-runtime"
NAME = re.compile(r"[a-z][a-z0-9-]{0,63}")
SHA = re.compile(r"[0-9a-f]{40}")


def api(path, payload=None):
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    command = ["gh", "api", "--method", "POST" if payload is not None else "GET",
               f"repos/{repository}/{path}"]
    if payload is not None:
        command.extend(["--input", "-"])
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def pages(path, query=None):
    page = 1
    while True:
        values = api(path + "?" + urlencode({**(query or {}), "per_page": 100, "page": page}))
        yield from values
        if len(values) < 100:
            return
        page += 1


def successful_sha(service, target):
    if not NAME.fullmatch(service) or not NAME.fullmatch(target):
        raise ValueError("Invalid deployment unit")
    # Only explicit post-readiness records count, never check-only Actions jobs.
    for deployment in pages("deployments", {"environment": f"Prod-CD({target})", "task": TASK}):
        payload = deployment.get("payload") or {}
        if isinstance(payload, str):
            payload = json.loads(payload)
        if payload.get("service") != service or payload.get("target") != target:
            continue
        if any(status["state"] == "success" for status in pages(f"deployments/{deployment['id']}/statuses")):
            sha = deployment["sha"]
            if not SHA.fullmatch(sha):
                raise ValueError("Invalid recorded deployment SHA")
            return sha
    return None


def record(service, target, sha):
    if not NAME.fullmatch(service) or not NAME.fullmatch(target) or not SHA.fullmatch(sha):
        raise ValueError("Invalid successful deployment")
    deployment = api("deployments", {
        "ref": sha, "task": TASK, "environment": f"Prod-CD({target})",
        "auto_merge": False, "required_contexts": [], "production_environment": True,
        "payload": {"service": service, "target": target},
        "description": "Runtime applied and readiness passed",
    })
    api(f"deployments/{deployment['id']}/statuses", {
        "state": "success", "auto_inactive": False,
        "description": "Runtime applied and readiness passed",
        "log_url": f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    })
    print(f"Recorded successful deployment: {target} at {sha}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service")
    parser.add_argument("target")
    parser.add_argument("sha")
    args = parser.parse_args()
    record(args.service, args.target, args.sha)
