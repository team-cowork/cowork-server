#!/usr/bin/env python3
"""Keep module databases bridge-local and expose exporters only to monitoring."""
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


STATE = Path('/etc/cowork/module-database-firewall')
PREFIX = 'COWORK_MODULE_DB_'
MARK = 'cowork-module-database'
PRIVATE = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))


def private(value):
    address = ipaddress.ip_network(value, strict=True)
    if address.version != 4 or not any(address.subnet_of(parent) for parent in PRIVATE):
        raise ValueError('Module database addresses must be private IPv4')
    return str(address)


def policy(value, stored=False):
    keys = {'network', 'bind_ip', 'monitoring_ip', 'database_port', 'exporter_port'}
    if stored:
        keys |= {'bridge', 'subnet'}
    if set(value) - {'local_ports'} != keys or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}', value['network']):
        raise ValueError('Invalid module database firewall policy')
    for key in ('bind_ip', 'monitoring_ip'):
        private(str(ipaddress.IPv4Address(value[key])))
    if (value['database_port'], value['exporter_port']) not in {(3306, 9104), (5432, 9187), (27017, 9216)}:
        raise ValueError('Invalid database and exporter ports')
    if value.get('local_ports', []) not in ([], [9200, 9300]):
        raise ValueError('Additional module-local ports must match the search service')
    if stored:
        if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,15}', value['bridge']):
            raise ValueError('Invalid module Docker bridge')
        private(value['subnet'])
    return value


def run(*args, content=None):
    result = subprocess.run(args, input=content, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f'Firewall command failed: {args[0]}')
    return result.stdout


def network(value):
    current = json.loads(run('docker', 'network', 'inspect', value['network']))[0]
    entries = current['IPAM']['Config']
    if current['Driver'] != 'bridge' or current.get('EnableIPv6') or len(entries) != 1:
        raise ValueError('Module databases require a single IPv4 bridge network')
    bridge = current.get('Options', {}).get('com.docker.network.bridge.name') or 'br-' + current['Id'][:12]
    return policy({**value, 'bridge': bridge, 'subnet': entries[0]['Subnet']}, stored=True)


def restore(value):
    # MySQL X Protocol is disabled at startup and blocked here as well.
    db_ports = [value['database_port']] + ([33060] if value['database_port'] == 3306 else [])
    ports = db_ports + value.get('local_ports', []) + [value['exporter_port']]
    chains = {port: PREFIX + str(port) for port in ports}
    for tool in ('iptables', 'ip6tables'):
        previous = run(tool, '-w', '10', '-S').splitlines()
        parsed = [shlex.split(line) for line in previous]
        rules = ['*filter']
        if tool == 'iptables' and ['-N', 'DOCKER-USER'] not in parsed:
            rules.append(':DOCKER-USER - [0:0]')
        for chain in chains.values():
            rules.extend([f':{chain} - [0:0]', f'-F {chain}'])
        old = {parts[1] for parts in parsed if parts[:1] == ['-N'] and parts[1].startswith(PREFIX)}
        for line, parts in zip(previous, parsed):
            if '-j' not in parts or not parts[parts.index('-j') + 1].startswith(PREFIX):
                continue
            if parts[:1] != ['-A'] or parts[1] not in {'INPUT', 'DOCKER-USER', 'FORWARD'} or MARK not in parts:
                raise ValueError('Unknown rule references the module database firewall')
            rules.append('-D' + line[2:])
        for chain in sorted(old - set(chains.values())):
            rules.extend([f'-F {chain}', f'-X {chain}'])
        for port, chain in chains.items():
            if tool == 'iptables':
                # A remote routed source cannot impersonate the local bridge interface.
                rules.append(f'-A {chain} -i {value["bridge"]} -s {value["subnet"]} -j RETURN')
                if port == value['exporter_port']:
                    rules.append(f'-A {chain} -s {value["monitoring_ip"]}/32 -j RETURN')
            rules.append(f'-A {chain} -j DROP')
            marker = f'-m comment --comment {MARK}'
            direction = '-m conntrack --ctdir ORIGINAL'
            rules.append(f'-I INPUT 1 ! -i lo -p tcp --dport {port} {direction} {marker} -j {chain}')
            forward = 'DOCKER-USER' if tool == 'iptables' else 'FORWARD'
            for interface in ('br+', 'docker+'):
                rules.append(f'-I {forward} 1 -o {interface} -p tcp --dport {port} {direction} {marker} -j {chain}')
        rules.append('COMMIT')
        run(tool + '-restore', '--wait', '10', '--noflush', content='\n'.join(rules) + '\n')


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {'validate', 'apply', 'restore'}:
        raise ValueError('Expected validate, apply or restore')
    operation = sys.argv[1]
    if operation == 'validate':
        policy(json.load(sys.stdin))
        print('Module database firewall policy is valid')
        return
    if os.geteuid() != 0:
        raise ValueError('Module database firewall changes require root')
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(STATE, 0o700)
    with (STATE / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        destination = STATE / 'policy.json'
        value = network(policy(json.load(sys.stdin))) if operation == 'apply' else policy(json.loads(destination.read_text()), stored=True)
        restore(value)
        if operation == 'apply':
            temporary = STATE / 'policy.new'
            temporary.write_text(json.dumps(value, indent=2) + '\n')
            os.chmod(temporary, 0o600)
            temporary.replace(destination)
    print('Module database firewall applied')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, IndexError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'[module-database-firewall] {error}', file=sys.stderr)
        sys.exit(1)
