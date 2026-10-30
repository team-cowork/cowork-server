#!/usr/bin/env python3
"""Install checksum-pinned deployment CLIs without executing downloaded scripts."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import platform
import urllib.request
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--tools', nargs='+', choices=['nomad', 'tofu'], default=['nomad', 'tofu'])
args = parser.parse_args()
versions = json.loads((Path(__file__).resolve().parents[1] / 'config/versions.json').read_text())
if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64'):
    raise SystemExit('The automated installer supports the pinned Linux amd64 deployment platform')
args.directory.mkdir(parents=True, exist_ok=True)
for name in args.tools:
    spec = versions[name]
    version = spec['version']
    archive = f'{name}_{version}_linux_amd64.zip'
    url = (f'https://releases.hashicorp.com/nomad/{version}/{archive}' if name == 'nomad'
           else f'https://github.com/opentofu/opentofu/releases/download/v{version}/{archive}')
    with urllib.request.urlopen(url, timeout=60) as response:
        payload = response.read()
    if hashlib.sha256(payload).hexdigest() != spec['sha256']['linux_amd64']:
        raise SystemExit(f'{name}: archive checksum mismatch')
    with zipfile.ZipFile(io.BytesIO(payload)) as bundle:
        binary = bundle.read(name)
    destination = args.directory / name
    temporary = destination.with_suffix('.new')
    temporary.write_bytes(binary)
    temporary.chmod(0o755)
    temporary.replace(destination)
    print(f'Installed {name} {version}')
