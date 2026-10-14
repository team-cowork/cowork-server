#!/bin/bash
# An administrator runs this only from reviewed, root-owned staging files.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
if [ "$EUID" -ne 0 ] || [ "$#" -ne 0 ]; then
  echo '[datastore-firewall] Bootstrap requires root and accepts no arguments' >&2
  exit 1
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
/usr/bin/python3 -I - "$source_dir" <<'PY'
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])
names = ("bootstrap-datastore-firewall.sh", "datastore-firewall.py", "datastore-firewall.service", "policy.json")


def check(path, directory, private=False):
    info = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(info.st_mode) or info.st_uid != 0 or info.st_mode & (0o077 if private else 0o022):
        raise SystemExit("[datastore-firewall] Bootstrap files and parents must be root-owned and not writable by others")


for directory in (source, *source.parents):
    check(directory, True)
for name in names:
    check(source / name, False, private=name == "policy.json")

# The helper is executed only after verifying the trusted root-owned staging.
# No release-cache file or caller-selected executable runs with root privileges.
with (source / "policy.json").open() as policy_file:
    subprocess.run([sys.executable, "-I", str(source / "datastore-firewall.py"), "validate"], stdin=policy_file, check=True)

for name, target, mode in (
    ("datastore-firewall.py", Path("/usr/local/libexec/cowork-datastore-firewall.py"), 0o755),
    ("datastore-firewall.service", Path("/etc/systemd/system/cowork-datastore-firewall.service"), 0o644),
    ("policy.json", Path("/etc/cowork/datastore-firewall/policy.json"), 0o600),
):
    for directory in target.parent.parents:
        if directory.exists():
            check(directory, True)
    target.parent.mkdir(mode=0o700 if name == "policy.json" else 0o755, parents=True, exist_ok=True)
    for directory in (target.parent, *target.parent.parents):
        check(directory, True)
    if name == "policy.json":
        os.chmod(target.parent, 0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=".datastore-firewall-bootstrap-", dir=target.parent)
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
/usr/bin/python3 -I /usr/local/libexec/cowork-datastore-firewall.py restore
systemctl enable cowork-datastore-firewall.service
systemctl start cowork-datastore-firewall.service
echo '[datastore-firewall] Policy applied and Docker prerequisite enabled; Docker was not restarted'
