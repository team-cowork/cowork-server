#!/bin/bash
# An administrator runs this only from reviewed, root-owned staging files.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
if [ "$EUID" -ne 0 ] || [ "$#" -ne 0 ]; then
  echo '[backend-firewall] Bootstrap requires root and accepts no arguments' >&2
  exit 1
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
/usr/bin/python3 -I - "$source_dir" <<'PY'
import os
import fcntl
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])
policy_mode = os.environ.get("COWORK_BACKEND_POLICY_MODE", "stage")
if policy_mode not in {"apply", "stage"}:
    raise SystemExit("[backend-firewall] Expected an apply or stage bootstrap mode")
names = ("bootstrap-backend-firewall.sh", "backend-firewall.py", "backend-firewall.service", "policy.json")


def check(path, directory, private=False):
    info = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(info.st_mode) or info.st_uid != 0 or info.st_mode & (0o077 if private else 0o022):
        raise SystemExit("[backend-firewall] Bootstrap files and parents must be root-owned and not writable by others")


for directory in (source, *source.parents):
    check(directory, True)
for name in names:
    check(source / name, False, private=name == "policy.json")

# The helper is executed only after verifying the trusted root-owned staging.
# No release-cache file or caller-selected executable runs with root privileges.
with (source / "policy.json").open() as policy_file:
    subprocess.run([sys.executable, "-I", str(source / "backend-firewall.py"), "validate"], stdin=policy_file, check=True)

state_directory = Path("/etc/cowork/backend-firewall")
state_directory.mkdir(parents=True, exist_ok=True, mode=0o700)
for directory in (state_directory, *state_directory.parents):
    check(directory, True, private=directory == state_directory)
lock = (state_directory / ".lock").open("a")
os.chmod(lock.name, 0o600)
fcntl.flock(lock, fcntl.LOCK_EX)
if (state_directory / "transaction.json").exists():
    raise SystemExit("[backend-firewall] Resolve the retained deployment transaction before changing desired policy")
active_exists = (state_directory / "policy.json").exists()
if policy_mode == "stage" and not active_exists:
    service = os.environ.get("COWORK_BACKEND_SERVICE", "")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", service):
        raise SystemExit("[backend-firewall] First staged installation requires the backend service identity")
    containers = subprocess.check_output(["docker", "container", "ls", "--all", "--format", "{{.Names}}"], text=True).splitlines()
    if "cowork-" + service in containers:
        raise SystemExit("[backend-firewall] A running or retained backend needs an initial reviewed active policy before transactional staging")
entries = [
    ("backend-firewall.py", Path("/usr/local/libexec/cowork-backend-firewall.py"), 0o755),
    ("backend-firewall.service", Path("/etc/systemd/system/cowork-backend-firewall.service"), 0o644),
    ("policy.json", state_directory / "desired.json", 0o600),
]
if policy_mode == "apply" or not active_exists:
    entries.append(("policy.json", state_directory / "policy.json", 0o600))
for name, target, mode in entries:
    for directory in target.parent.parents:
        if directory.exists():
            check(directory, True)
    target.parent.mkdir(mode=0o700 if name == "policy.json" else 0o755, parents=True, exist_ok=True)
    for directory in (target.parent, *target.parent.parents):
        check(directory, True)
    if name == "policy.json":
        os.chmod(target.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".backend-firewall-bootstrap-", dir=target.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            os.fchown(output.fileno(), 0, 0)
            output.write((source / name).read_bytes())
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
        directory = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
PY
systemctl daemon-reload
/usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py restore
systemctl enable cowork-backend-firewall.service
systemctl start cowork-backend-firewall.service
echo '[backend-firewall] Reviewed desired policy staged; active protection restored without restarting Docker'
