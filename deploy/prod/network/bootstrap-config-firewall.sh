#!/bin/bash
# An administrator runs this only from reviewed, root-owned staging files.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
if [ "$EUID" -ne 0 ] || [ "$#" -ne 0 ]; then
  echo '[config-firewall] Bootstrap requires root and accepts no arguments' >&2
  exit 1
fi
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
/usr/bin/python3 -I - "$source_dir" <<'PY'
import os
from pathlib import Path
import stat
import sys
import tempfile

source = Path(sys.argv[1])
names = ("bootstrap-config-firewall.sh", "config-firewall.py", "config-firewall.service")


def check(path, directory):
    info = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise SystemExit("[config-firewall] Bootstrap files and parent directories must be root-owned and not writable by others")


for directory in (source, *source.parents):
    check(directory, True)
for name in names:
    check(source / name, False)

for name, target, mode in (
    ("config-firewall.py", Path("/usr/local/libexec/cowork-config-firewall.py"), 0o755),
    ("config-firewall.service", Path("/etc/systemd/system/cowork-config-firewall.service"), 0o644),
):
    target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    for directory in (target.parent, *target.parent.parents):
        check(directory, True)
    descriptor, temporary = tempfile.mkstemp(prefix=".config-firewall-bootstrap-", dir=target.parent)
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
echo '[config-firewall] Trusted helper installed; the first deployment saves policy and enables restoration'
