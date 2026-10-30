#!/usr/bin/env python3
"""Validate deployment source and native job specs without starting services."""
import argparse
import ast
import json
import os
from pathlib import Path
import subprocess

from deploy.nomad.render import ROOT, render_jobs
from deploy.production.release import validate_manifest


def check_syntax():
    shell_files = sorted((ROOT / "deploy").rglob("*.sh"))
    python_files = sorted((ROOT / "deploy").rglob("*.py"))
    for path in shell_files:
        subprocess.run(["bash", "-n", str(path)], check=True)
    for path in python_files:
        ast.parse(path.read_text(), filename=str(path))
    for path in sorted((ROOT / "deploy").rglob("*.json")):
        json.loads(path.read_text())
    print(f"Deployment syntax valid: {len(shell_files)} shell and {len(python_files)} Python files; JSON parsed")



def check_compose():
    env = {key: value for key, value in os.environ.items() if not key.startswith("COMPOSE_")}
    command = ["docker", "compose", "--project-directory", str(ROOT), "--env-file", "/dev/null",
               "-p", "cowork-validation", "-f", str(ROOT / "deploy/runtime/vault/compose.yaml"), "config", "--quiet"]
    subprocess.run(command, env=env, check=True)
    print("Vault: Compose configuration valid")



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, help="Validate committed image and secret references")
    parser.add_argument("--vault-mount", default=os.environ.get("VAULT_KV_MOUNT", "secret"))
    args = parser.parse_args()
    os.chdir(ROOT)
    check_syntax()
    check_compose()
    manifest = json.loads(args.manifest.read_text()) if args.manifest else None
    if manifest is not None:
        validate_manifest(manifest)
    _, stages = render_jobs(manifest, args.vault_mount, validate=True)
    print(f"{sum(map(len, stages.values()))} native Nomad jobs validated offline; no services were started")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
