#!/usr/bin/env python3
"""Apply one Vault snapshot to an immutable release without sourcing VM env files."""
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
    service, target, sha, owner, action = sys.argv[1:]
    directory = Path(__file__).resolve().parent
    schema = runpy.run_path(str(directory / "vault-settings.py"))
    demand = schema["demand"]
    demand(schema["NAME"].fullmatch(target), "Invalid deployment target")
    demand(re.fullmatch(r"[0-9a-f]{40}", sha), "Invalid release SHA")
    demand(re.fullmatch(r"[a-z0-9][a-z0-9-]*", owner), "Invalid image owner")
    demand((directory / "services" / f"{service}.sh").is_file() and schema["NAME"].fullmatch(service), "Unknown deployment service")
    demand(action in {"check", "deploy"}, "Invalid deployment action")
    data = schema["parse_json"](base64.b64decode(sys.stdin.buffer.read(), validate=True))
    version = data.pop("vault_version")
    # SSH credentials stay on the runner. Validate only the deployment portion again.
    data["ssh"] = {"host": "unused", "port": 22, "user": "unused", "key": "unused", "fingerprint": "SHA256:unused"}
    schema["validate_deployment"](data)
    if service in {"vault", "monitoring", "log-agent"}:
        demand(not data.get("application"), "Infrastructure units use runtime settings only")
    if service not in {"vault", "monitoring", "log-agent"}:
        demand(data.get("runtime", {}).get("APP_CONFIG_PROFILE") in {"local", "prod"}, "APP_CONFIG_PROFILE is required in Vault")
    home = Path.home()
    state = home / ".local/state/cowork"
    snapshots = state / "settings" / target
    snapshots.mkdir(parents=True, exist_ok=True, mode=0o700)
    snapshot = Path(tempfile.mkdtemp(prefix=f"{sha[:12]}-", dir=snapshots))
    # No host-wide environment (including BASH_ENV) can override the Vault snapshot.
    env = {"HOME": str(home), "PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"}
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
