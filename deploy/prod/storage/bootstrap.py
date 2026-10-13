#!/usr/bin/env python3
"""Prepare or apply the dedicated production SeaweedFS bundle on its VM.

This command never removes a container, volume, object, or database. Retire a
previous storage engine explicitly before applying a fresh bundle.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from urllib.parse import urlsplit


SOURCE = Path(__file__).resolve().parent
CONFIG_SOURCE = SOURCE.parents[1] / "config" / "seaweedfs"
PRIVATE = [ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
FAILURE_DIR = None


def run(*args, env=None):
    result = subprocess.run(args, env=env, text=True, capture_output=True, timeout=600)
    if result.returncode:
        # Docker's rendered configuration and upstream errors may contain keys.
        if FAILURE_DIR is not None and FAILURE_DIR.is_dir():
            write(FAILURE_DIR / "last-command-failure.log", result.stdout + "\n" + result.stderr)
        raise RuntimeError(f"Storage setup command failed: {args[0]}; inspect the private last-command-failure.log")
    return result.stdout


def private_network(value):
    network = ipaddress.ip_network(value, strict=True)
    if network.version != 4 or not any(network.subnet_of(parent) for parent in PRIVATE):
        raise ValueError("Use explicit RFC1918 IPv4 addresses")
    return str(network)


def load_settings(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ValueError("Storage settings must be a regular mode 0600 file")
    value = json.loads(path.read_text())
    if set(value) != {"bind_ip", "allowed_cidrs", "bucket", "region", "public_web_origins", "identities"}:
        raise ValueError("Invalid storage settings fields")
    value["bind_ip"] = str(ipaddress.IPv4Address(value["bind_ip"]))
    private_network(value["bind_ip"])
    if not isinstance(value["allowed_cidrs"], list) or not 1 <= len(value["allowed_cidrs"]) <= 32:
        raise ValueError("Supply explicit storage peers")
    value["allowed_cidrs"] = sorted({private_network(peer) for peer in value["allowed_cidrs"]})
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", value["bucket"]):
        raise ValueError("Invalid bucket name")
    if not re.fullmatch(r"[a-z0-9-]+", value["region"]):
        raise ValueError("Invalid signing region")
    origins = value["public_web_origins"]
    if not isinstance(origins, list) or not origins:
        raise ValueError("Supply the production HTTPS web origins")
    for origin in origins:
        if not isinstance(origin, str) or not re.fullmatch(r"https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?", origin):
            raise ValueError("Production web origins must be explicit HTTPS origins")
        parsed = urlsplit(origin)
        if not parsed.hostname or (parsed.port is not None and not 1 <= parsed.port <= 65535):
            raise ValueError("Invalid HTTPS origin")
    if set(value["identities"]) != {"admin", "team", "user", "chat"}:
        raise ValueError("Supply separate admin, Team, User and Chat identities")
    access_keys = set()
    for identity in value["identities"].values():
        if set(identity) != {"access_key", "secret_key"}:
            raise ValueError("Invalid storage identity")
        for key in identity.values():
            if not re.fullmatch(r"[A-Za-z0-9_+=/.-]{20,128}", key) or key.startswith(("local-", "seaweedfs", "REPLACE_")):
                raise ValueError("Use generated production storage credentials")
        if identity["access_key"] in access_keys:
            raise ValueError("Storage access keys must be distinct")
        access_keys.add(identity["access_key"])
    return value


def secure_directory(path, uid=0, mode=0o700):
    path.mkdir(mode=mode, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, uid) or info.st_mode & 0o022:
        raise ValueError("Storage directories must not be symlinks or writable by other users")
    os.chown(path, uid, uid)
    os.chmod(path, mode)


def write(path, content, mode=0o600, uid=0):
    fd, temporary = tempfile.mkstemp(prefix=".storage-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            os.fchmod(output.fileno(), mode)
            os.fchown(output.fileno(), uid, uid)
            output.write(content if isinstance(content, bytes) else content.encode())
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def prepare(settings_path, directory):
    value = load_settings(settings_path)
    secure_directory(directory)
    secure_directory(directory / "config")
    secure_directory(directory / "scripts", mode=0o755)
    for name in ("data", "admin-data", "lifecycle-data"):
        secure_directory(directory / name, uid=10001, mode=0o750)
    variables = {"APP_PROFILE": "prod", "S3_BUCKET": value["bucket"], "PATH": os.environ["PATH"]}
    for role, identity in value["identities"].items():
        prefix = "S3" if role == "admin" else "S3_" + role.upper()
        variables[prefix + "_ACCESS_KEY"] = identity["access_key"]
        variables[prefix + "_SECRET_KEY"] = identity["secret_key"]
    # Reuse the same IAM rendering source as local Compose and PR #489.
    run("sh", str(CONFIG_SOURCE / "render-s3-config.sh"), str(CONFIG_SOURCE / "s3-config.json.template"),
        str(directory / "config" / "s3-config.json"), env=variables)
    os.chown(directory / "config" / "s3-config.json", 10001, 10001)
    os.chmod(directory / "config" / "s3-config.json", 0o400)
    admin = value["identities"]["admin"]
    write(directory / "config" / "admin.env", f"AWS_ACCESS_KEY_ID={admin['access_key']}\nAWS_SECRET_ACCESS_KEY={admin['secret_key']}\n")
    for name in ("configure-bucket.sh", "render-cors.sh"):
        write(directory / "scripts" / name, (CONFIG_SOURCE / name).read_bytes(), mode=0o444)
    for name in ("compose.yaml", "firewall.py", "firewall.service"):
        write(directory / name, (SOURCE / name).read_bytes())
    write(directory / "compose.env", "\n".join([
        "STORAGE_BIND_IP=" + value["bind_ip"], "S3_BUCKET=" + value["bucket"],
        "S3_REGION=" + value["region"], "PUBLIC_WEB_ORIGINS=" + ",".join(value["public_web_origins"]), "",
    ]))
    write(directory / "firewall.json", json.dumps({key: value[key] for key in ("bind_ip", "allowed_cidrs")}) + "\n")
    print("Production storage files prepared; no service was started or removed")


def apply(directory):
    # All executable/configuration sources must be root-controlled on the VM.
    for path in (directory, directory / "compose.yaml", directory / "compose.env",
                 directory / "firewall.py", directory / "firewall.service", directory / "firewall.json"):
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError("Apply requires a root-owned prepared bundle")
    existing = run("docker", "container", "ls", "--format", "{{.Names}}").splitlines()
    if "cowork-minio" in existing:
        raise ValueError("Retire the old MinIO service before applying SeaweedFS")
    compose = ["docker", "compose", "--env-file", str(directory / "compose.env"), "-f", str(directory / "compose.yaml")]
    run(*compose, "config", "--quiet")
    secure_directory(Path("/etc/cowork"))
    secure_directory(Path("/usr/local/libexec"), mode=0o755)
    write(Path("/etc/cowork/storage-firewall.json"), (directory / "firewall.json").read_bytes())
    write(Path("/usr/local/libexec/cowork-storage-firewall.py"), (directory / "firewall.py").read_bytes(), mode=0o700)
    write(Path("/etc/systemd/system/cowork-storage-firewall.service"), (directory / "firewall.service").read_bytes(), mode=0o644)
    run("systemctl", "daemon-reload")
    run("systemctl", "enable", "cowork-storage-firewall.service")
    # Restarting this required unit can stop Docker and every DB container.
    # Apply in place, then activate the unit only when it is not already active.
    run("python3", "-I", "/usr/local/libexec/cowork-storage-firewall.py")
    run("systemctl", "start", "cowork-storage-firewall.service")
    run(*compose, "pull")
    # Shared network namespaces must be re-created after a storage replacement.
    run(*compose, "stop", "seaweedfs-admin", "seaweedfs-lifecycle")
    run(*compose, "up", "-d", "--force-recreate", "--wait", "--wait-timeout", "180", "seaweedfs")
    run(*compose, "run", "--rm", "--no-deps", "seaweedfs-init")
    print("PublicAccessBlock is unsupported by SeaweedFS 4.47; private ACL and prefix/bucket policies were applied")
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "seaweedfs-admin")
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "180", "seaweedfs-lifecycle")
    print("SeaweedFS is running; bucket configuration completed; lifecycle worker is ready")


def main():
    global FAILURE_DIR
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "apply"))
    parser.add_argument("--directory", type=Path, default=Path("/opt/cowork/storage"))
    parser.add_argument("--settings", type=Path)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit("Run storage bootstrap as root on the storage VM")
    if not args.directory.is_absolute():
        raise ValueError("The storage directory must be absolute")
    FAILURE_DIR = args.directory
    os.umask(0o077)
    if args.operation == "prepare":
        if args.settings is None:
            parser.error("prepare requires --settings")
        prepare(args.settings, args.directory)
    else:
        apply(args.directory)


if __name__ == "__main__":
    main()
