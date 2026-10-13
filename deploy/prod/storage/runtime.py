#!/usr/bin/env python3
"""Deploy SHA-pinned SeaweedFS sources as the unprivileged storage account."""
import json
import os
from pathlib import Path
import runpy
import stat
import subprocess
import sys


SOURCE = Path(__file__).resolve().parent
CONFIG_SOURCE = SOURCE.parents[1] / "config" / "seaweedfs"
DATA_ROOT = Path("/opt/cowork/storage")
DATA_DIRECTORIES = {"seaweedfs": "data", "seaweedfs-admin": "admin-data", "seaweedfs-lifecycle": "lifecycle-data"}
SNAPSHOT = None
COMMAND_ENV = None
PHASE = "argument-validation"


def write(path, content, mode=0o600):
    with path.open("xb") as output:
        os.fchmod(output.fileno(), mode)
        output.write(content if isinstance(content, bytes) else content.encode())


def run(*args, env=None, input_text=None):
    result = subprocess.run(args, env=COMMAND_ENV if env is None else env, input=input_text,
                            text=True, capture_output=True, timeout=600)
    if result.returncode:
        # Keep diagnostics private; upstream output can include credentials.
        # Check snapshots are removed by apply-settings.py. Keep only failure
        # diagnostics in a separate private directory for operator inspection.
        diagnostics = Path.home() / ".local/state/cowork/storage-diagnostics"
        diagnostics.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = diagnostics.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 10001 or info.st_mode & 0o077:
            raise ValueError("Storage diagnostics directory must be private")
        failure = diagnostics / (SNAPSHOT.name + ".log")
        write(failure, (result.stdout + "\n" + result.stderr)[-20 * 1024 * 1024:])
        print(f"[storage] Private failure diagnostics: {failure}", file=sys.stderr)
        raise RuntimeError("Storage deployment command failed")
    return result.stdout


def unique_object(pairs):
    value = {}
    for key, child in pairs:
        if key in value:
            raise ValueError("Duplicate storage settings field")
        value[key] = child
    return value


def prepare():
    settings = SNAPSHOT / "settings.json"
    write(settings, json.dumps(json.loads(os.environ["STORAGE_SETTINGS_JSON"], object_pairs_hook=unique_object)))
    try:
        # Share bootstrap validation without invoking its root-only prepare/apply.
        value = runpy.run_path(str(SOURCE / "bootstrap.py"))["load_settings"](settings)
    finally:
        settings.unlink()
    for name in ("config", "scripts"):
        (SNAPSHOT / name).mkdir(mode=0o700)
    variables = {**COMMAND_ENV, "APP_PROFILE": "prod", "S3_BUCKET": value["bucket"]}
    for role, identity in value["identities"].items():
        prefix = "S3" if role == "admin" else "S3_" + role.upper()
        variables[prefix + "_ACCESS_KEY"] = identity["access_key"]
        variables[prefix + "_SECRET_KEY"] = identity["secret_key"]
    run("sh", str(CONFIG_SOURCE / "render-s3-config.sh"), str(CONFIG_SOURCE / "s3-config.json.template"),
        str(SNAPSHOT / "config" / "s3-config.json"), env=variables)
    os.chmod(SNAPSHOT / "config" / "s3-config.json", 0o400)
    admin = value["identities"]["admin"]
    write(SNAPSHOT / "config" / "admin.env",
          f"AWS_ACCESS_KEY_ID={admin['access_key']}\nAWS_SECRET_ACCESS_KEY={admin['secret_key']}\n")
    for name in ("configure-bucket.sh", "render-cors.sh"):
        write(SNAPSHOT / "scripts" / name, (CONFIG_SOURCE / name).read_bytes(), 0o400)
    write(SNAPSHOT / "compose.env", "\n".join([
        "STORAGE_BIND_IP=" + value["bind_ip"], "S3_BUCKET=" + value["bucket"],
        "S3_REGION=" + value["region"], "PUBLIC_WEB_ORIGINS=" + ",".join(value["public_web_origins"]), "",
    ]))
    # Compose merges volumes by container target. Replace only the three data
    # mounts; IAM, admin.env and scripts remain in this immutable private snapshot.
    override = {"services": {service: {"volumes": [{
        "type": "bind", "source": str(DATA_ROOT / name), "target": "/data",
        "bind": {"create_host_path": False},
    }]} for service, name in DATA_DIRECTORIES.items()}}
    write(SNAPSHOT / "compose.override.json", json.dumps(override) + "\n")
    return value


def verify_runtime():
    for name in DATA_DIRECTORIES:
        state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", "cowork-" + name))
        if not state.get("Running"):
            raise ValueError("A required storage container is not running")
        if name != "seaweedfs-admin" and state.get("Health", {}).get("Status") != "healthy":
            raise ValueError("A required storage container did not pass readiness")


def main():
    global SNAPSHOT, COMMAND_ENV, PHASE
    if len(sys.argv) != 2 or sys.argv[1] not in {"check", "deploy"}:
        raise ValueError("Storage action must be check or deploy")
    if os.geteuid() != 10001 or os.getegid() != 10001:
        raise ValueError("Storage CD requires the cowork-storage account with UID/GID 10001")
    PHASE = "host-bootstrap-validation"
    os.umask(0o077)
    SNAPSHOT = Path(os.environ["DEPLOY_SETTINGS_DIR"])
    info = SNAPSHOT.lstat()
    expected = Path.home() / ".local/state/cowork/settings/storage"
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != 10001 or info.st_mode & 0o077 or
            SNAPSHOT.resolve().parent != expected.resolve()):
        raise ValueError("Storage CD requires a private SHA-specific storage snapshot")
    for name in DATA_DIRECTORIES.values():
        info = (DATA_ROOT / name).lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 10001 or info.st_mode & 0o022:
            raise ValueError("Storage data directories require the completed host bootstrap")
    COMMAND_ENV = {key: os.environ[key] for key in ("HOME", "PATH", "LANG", "DOCKER_CONFIG") if key in os.environ}
    PHASE = "private-snapshot-rendering"
    value = prepare()
    PHASE = "installed-firewall-validation"
    run("sudo", "-n", "/usr/bin/python3", "-I", "/usr/local/libexec/cowork-storage-firewall.py", "check-binding",
        input_text=json.dumps({key: value[key] for key in ("bind_ip", "allowed_cidrs")}))
    compose = ["docker", "compose", "--project-directory", str(SNAPSHOT), "--env-file", str(SNAPSHOT / "compose.env"),
               "-p", "cowork-storage", "-f", str(SOURCE / "compose.yaml"), "-f", str(SNAPSHOT / "compose.override.json")]
    PHASE = "compose-validation"
    run(*compose, "config", "--quiet")
    if sys.argv[1] == "check":
        print("[storage] Settings, Compose and installed ingress policy are valid; no containers were started")
        return
    PHASE = "existing-engine-validation"
    if "cowork-minio" in run("docker", "container", "ls", "--format", "{{.Names}}").splitlines():
        raise ValueError("Retire the previous storage engine before deploying SeaweedFS")
    print("[storage] Pulling SHA-pinned storage images", flush=True)
    PHASE = "image-pull"
    run(*compose, "pull")
    # A single node shares persistent data. Never start a second writer against
    # it or attempt an automatic image rollback after a storage format change.
    PHASE = "server-replacement"
    run(*compose, "stop", "seaweedfs-admin", "seaweedfs-lifecycle")
    run(*compose, "up", "-d", "--force-recreate", "--wait", "--wait-timeout", "180", "seaweedfs")
    PHASE = "bucket-configuration"
    run(*compose, "run", "--rm", "--no-deps", "seaweedfs-init")
    print("[storage] SeaweedFS 4.47 does not implement PublicAccessBlock; private ACL, IAM and bucket policy restrict access")
    PHASE = "admin-and-worker-replacement"
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "seaweedfs-admin")
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "180", "seaweedfs-lifecycle")
    PHASE = "final-readiness"
    verify_runtime()
    print("[storage] Server and lifecycle worker passed readiness; bucket configuration completed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        print(f"[storage] Failed during {PHASE}; inspect the VM state before retrying", file=sys.stderr)
        sys.exit(1)
