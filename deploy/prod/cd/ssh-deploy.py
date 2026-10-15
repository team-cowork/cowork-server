#!/usr/bin/env python3
"""Apply a release over OpenSSH after verifying the pinned host fingerprint."""
import base64
import hashlib
import ipaddress
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

REMOTE_KEYS = (
    'DEPLOY_PAYLOAD', 'DEPLOY_SHA', 'DEPLOY_IMAGE_SHA', 'DEPLOY_TOOLS_SHA',
    'DEPLOY_SERVICE', 'DEPLOY_TARGET', 'DEPLOY_OWNER', 'DEPLOY_ACTION',
    'DEPLOY_GHCR_USER', 'DEPLOY_GHCR_TOKEN',
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def private_file(path, value):
    with path.open('x') as stream:
        os.chmod(path, 0o600)
        stream.write(value)


def verified_host_keys(host, port, fingerprint, env):
    result = subprocess.run(['ssh-keyscan', '-T', '10', '-p', str(port), host],
                            capture_output=True, text=True, timeout=30, env=env)
    matches = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) != 3 or line.startswith('#'):
            continue
        try:
            key = base64.b64decode(parts[2], validate=True)
        except ValueError:
            continue
        actual = 'SHA256:' + base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=')
        if actual == fingerprint:
            matches.append(line)
    require(matches, 'SSH host key does not match the pinned SHA256 fingerprint')
    return '\n'.join(matches) + '\n'


def main():
    host, user = os.environ['SSH_HOST'], os.environ['SSH_USER']
    port = int(os.environ['SSH_PORT'])
    key, fingerprint = os.environ['SSH_KEY'], os.environ['SSH_FINGERPRINT']
    if ':' in host:
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            raise ValueError('Invalid SSH host') from None
    else:
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', host), 'Invalid SSH host')
    require(re.fullmatch(r'[a-z_][a-z0-9_-]*', user), 'Invalid SSH user')
    require(1 <= port <= 65535, 'Invalid SSH port')
    require('PRIVATE KEY-----' in key and '\0' not in key, 'Invalid SSH private key')
    require(re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', fingerprint), 'Invalid SSH host fingerprint')
    values = {name: os.environ[name] for name in REMOTE_KEYS}
    require(all('\0' not in value for value in values.values()), 'Invalid remote input')
    # Only the SSH input stream carries deployment secrets, never argv or child env.
    script = 'set -euo pipefail\n' + ''.join(
        name + '=' + shlex.quote(value) + '\n' for name, value in values.items()
    ) + Path(__file__).with_name('remote-deploy.sh').read_text()
    env = {name: os.environ[name] for name in ('PATH', 'HOME', 'LANG', 'LC_ALL') if name in os.environ}
    with tempfile.TemporaryDirectory(prefix='cowork-ssh-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        root = Path(directory)
        identity, known_hosts = root / 'identity', root / 'known_hosts'
        private_file(identity, key.rstrip() + '\n')
        private_file(known_hosts, verified_host_keys(host, port, fingerprint, env))
        command = [
            'ssh', '-F', '/dev/null', '-T', '-p', str(port), '-i', str(identity),
            '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'IdentityAgent=none',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'GlobalKnownHostsFile=/dev/null',
            '-o', 'UserKnownHostsFile=' + str(known_hosts), '-o', 'UpdateHostKeys=no',
            '-o', 'ConnectTimeout=30', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=4',
            '--', user + '@' + host, 'bash -se',
        ]
        # Preserve the readiness and rollback budget of the previous SSH action.
        subprocess.run(command, input=script, text=True, env=env, timeout=45 * 60, check=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit('SSH deployment timed out; inspect the target before retrying') from None
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'SSH deployment failed (exit {error.returncode})') from None
    except ValueError as error:
        raise SystemExit(str(error)) from None
    except (OSError, KeyError):
        raise SystemExit('SSH deployment failed; check the declared connection settings') from None
