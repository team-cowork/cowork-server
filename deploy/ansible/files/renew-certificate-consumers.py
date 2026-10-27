#!/usr/bin/env python3
"""Load installed certificates into running host services without starting workloads."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import socket
import ssl
import subprocess
import time


def command(argv, *, check=True):
    return subprocess.run(argv, text=True, capture_output=True, check=check)


def container(*filters):
    argv = ['docker', 'ps', '--quiet']
    for value in filters:
        argv += ['--filter', value]
    ids = command(argv).stdout.split()
    if len(ids) > 1 or any(re.fullmatch(r'[0-9a-f]{12,64}', value) is None for value in ids):
        raise RuntimeError('Expected at most one running certificate consumer')
    return ids[0] if ids else None


def nomad_container(job, task):
    return container('label=com.hashicorp.nomad.namespace=default',
                     'label=com.hashicorp.nomad.job_name=' + job,
                     'label=com.hashicorp.nomad.task_name=' + task)


def serves_certificate(host, port, expected):
    context = ssl.create_default_context(cafile='/etc/cowork/ca/ca.crt')
    context.load_cert_chain('/etc/cowork/tls/host.crt', '/etc/cowork/tls/host.key')
    try:
        with socket.create_connection((host, port), timeout=3) as connection:
            with context.wrap_socket(connection, server_hostname=host) as session:
                return session.getpeercert(binary_form=True) == expected
    except (OSError, ssl.SSLError):
        return False


def reload_listener(name, host, port, expected, argv):
    if serves_certificate(host, port, expected):
        return False
    command(argv)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if serves_certificate(host, port, expected):
            return True
        time.sleep(2)
    raise RuntimeError(name + ' did not serve the installed certificate after reload')


def reload_alloy():
    running = container('name=^/cowork-platform-alloy$')
    if not running:
        return False
    digest = hashlib.sha256()
    for name in ('ca.crt', 'client.crt', 'client.key'):
        digest.update((Path('/etc/alloy/tls') / name).read_bytes())
    fingerprint = digest.hexdigest()
    marker = Path('/var/lib/cowork/tls-renewal/alloy.sha256')
    if marker.exists() and marker.read_text().strip() == fingerprint:
        return False
    command(['docker', 'restart', running])
    if command(['docker', 'inspect', '--format', '{{.State.Running}}', running]).stdout.strip() != 'true':
        raise RuntimeError('Alloy failed to start after loading its client certificate')
    marker.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    marker.write_text(fingerprint + '\n')
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, type=ipaddress.ip_address)
    parser.add_argument('--role', required=True)
    args = parser.parse_args()
    host = str(args.host)
    expected = ssl.PEM_cert_to_DER_cert(Path('/etc/cowork/tls/host.crt').read_text())
    reloaded = []
    for name, port in (('consul', 8501), ('nomad', 4646)):
        if command(['systemctl', 'is-active', '--quiet', name], check=False).returncode == 0:
            if reload_listener(name, host, port, expected, ['systemctl', 'reload', name]):
                reloaded.append(name)
    consumers = []
    if args.role == 'vault':
        consumers.append(('vault', 8210, container('name=^/cowork-vault-prod$'), 'HUP'))
    if args.role == 'monitoring':
        consumers.append(('monitoring-ingress', 3100,
                          nomad_container('cowork-monitoring', 'grafana-proxy'), 'HUP'))
    if args.role == 'kafka':
        # Nomad owns the replacement after the broker exits under its restart policy.
        consumers.append(('kafka', 9094, nomad_container('cowork-kafka', 'broker'), 'TERM'))
    for name, port, running, signal in consumers:
        if running and reload_listener(name, host, port, expected,
                                       ['docker', 'kill', '--signal', signal, running]):
            reloaded.append(name)
    if reload_alloy():
        reloaded.append('alloy')
    print(json.dumps({'reloaded': reloaded}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'Certificate consumer reconciliation failed: {error}') from None
