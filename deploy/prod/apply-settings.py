#!/usr/bin/env python3
"""Apply one Vault snapshot to an immutable release without sourcing VM env files."""
import argparse
import base64
import json
import os
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("service", "target", "sha", "owner", "action"):
        parser.add_argument(name)
    parser.add_argument("--release-directory", type=Path)
    args = parser.parse_args()
    service, target, sha, owner, action = args.service, args.target, args.sha, args.owner, args.action
    tools_directory = Path(__file__).resolve().parent
    schema = runpy.run_path(str(tools_directory / "vault-settings.py"))
    demand = schema["demand"]
    demand(schema["NAME"].fullmatch(target), "Invalid deployment target")
    demand(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid release SHA")
    demand(re.fullmatch(r"[a-z0-9][a-z0-9-]*", owner), "Invalid image owner")
    home = Path.home()
    release_directory = args.release_directory or tools_directory.parents[1]
    if args.release_directory is not None:
        expected = home / ".local/share/cowork/releases" / sha
        demand(release_directory.is_absolute() and release_directory == expected,
               "Release directory must match the SHA-specific deployment cache")
        demand(release_directory.is_dir() and not release_directory.is_symlink(),
               "Release directory must be a real directory")
        marker = release_directory / ".source-sha"
        demand(marker.is_file() and not marker.is_symlink() and marker.read_text().strip() == sha,
               "Release directory SHA does not match the requested release")
    directory = release_directory / "deploy/prod"
    demand(directory.resolve() == release_directory.resolve() / "deploy/prod",
           "Deployment scripts must remain inside the release directory")
    demand(schema["NAME"].fullmatch(service) and (directory / "services" / f"{service}.sh").is_file(),
           "Unknown deployment service")
    demand((directory / "deploy.sh").is_file() and not (directory / "deploy.sh").is_symlink(),
           "Release deployment script must be a regular file")
    demand(action in {"check", "deploy"}, "Invalid deployment action")
    data = schema["parse_json"](base64.b64decode(sys.stdin.buffer.read(), validate=True))
    version = data.pop("vault_version")
    # SSH credentials stay on the runner. Validate only the deployment portion again.
    data["ssh"] = {"host": "unused", "port": 22, "user": "unused", "key": "unused", "fingerprint": "SHA256:unused"}
    schema["validate_deployment"](data, target)
    for isolated in schema["ISOLATED_RUNTIME_SETTINGS"]:
        demand((service == isolated) == (target == isolated), f"The {isolated} target requires the {isolated} service")
    if service in {"vault", "monitoring", "storage", "datastores", "log-agent"}:
        demand(not data.get("application"), "Infrastructure units use runtime settings only")
    if service not in {"vault", "monitoring", "storage", "datastores", "log-agent"}:
        demand(data.get("runtime", {}).get("APP_CONFIG_PROFILE") in {"local", "prod"}, "APP_CONFIG_PROFILE is required in Vault")
    state = home / ".local/state/cowork"
    snapshots = state / "settings" / target
    snapshots.mkdir(parents=True, exist_ok=True, mode=0o700)
    snapshot = Path(tempfile.mkdtemp(prefix=f"{sha[:12]}-", dir=snapshots))
    # No host-wide environment (including BASH_ENV) can override the Vault snapshot.
    env = {"HOME": str(home), "PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"}
    # Only the workflow's private Docker auth directory crosses the environment boundary.
    # Registry tokens stay outside the Vault snapshot and container environment.
    docker_config = os.environ.get("DOCKER_CONFIG")
    if docker_config:
        auth_directory = Path(docker_config)
        demand(auth_directory.is_absolute() and auth_directory.is_dir() and not auth_directory.is_symlink(),
               "DOCKER_CONFIG must be an absolute private directory")
        auth_stat = auth_directory.stat()
        demand(auth_stat.st_uid == os.getuid() and auth_stat.st_mode & 0o077 == 0,
               "DOCKER_CONFIG must be owned by the deployment user with private permissions")
        env["DOCKER_CONFIG"] = str(auth_directory)
    env.update(data.get("runtime", {}))
    env.update(DEPLOY_SHA=sha, DEPLOY_IMAGE_OWNER=owner, DEPLOY_IMAGE_TAG=f"sha-{sha}",
               DEPLOY_SETTINGS_DIR=str(snapshot), DEPLOY_TARGET=target,
               DEPLOY_STATE_DIR=str(state))
    with (snapshot / "application.env0").open("xb") as stream:
        os.chmod(stream.name, 0o600)
        for key, value in data.get("application", {}).items():
            stream.write(f"{key}={value}\0".encode())
    (snapshot / "source.json").write_text(json.dumps({"sha": sha, "vault_version": version}))
    print(f"[deploy] {target}: Vault version {version}", flush=True)
    try:
        result = subprocess.run(["/bin/bash", str(directory / "deploy.sh"), service, action], env=env)
    finally:
        # Generated infrastructure configuration is retained for Docker restart/rollback.
        (snapshot / "application.env0").unlink()
        if action == "check":
            shutil.rmtree(snapshot)
    return result.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError) as error:
        print(f"[apply-settings] {error}", file=sys.stderr)
        sys.exit(1)
