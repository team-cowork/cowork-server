#!/usr/bin/env python3
"""Restore per-service ingress on dedicated Redis, Kafka and LiveKit VMs."""
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

STATE = Path('/etc/cowork/shared-firewall')
PREFIX = 'COWORK_SHARED_'
MARK = 'cowork-shared-service'
PRIVATE = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))


def private(value):
    address = ipaddress.ip_network(value, strict=True)
    if address.version != 4 or not any(address.subnet_of(parent) for parent in PRIVATE):
        raise ValueError('Shared services require private IPv4 addresses')
    return str(address)


def port(value):
    if type(value) is not int or not 1 <= value <= 65535:
        raise ValueError('Invalid service port')
    return value


def policy(value, stored=False):
    keys = {'network', 'bind_ip', 'bindings', 'internal_ports'} | ({'bridge', 'subnet'} if stored else set())
    if set(value) != keys or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,62}', value['network']):
        raise ValueError('Invalid shared service firewall policy')
    private(str(ipaddress.IPv4Address(value['bind_ip'])))
    endpoints, hosts = set(), set()
    for entry in value['bindings']:
        if set(entry) != {'host_port', 'container_port', 'protocol', 'peers'} or entry['protocol'] not in {'tcp', 'udp'}:
            raise ValueError('Invalid service binding')
        endpoint = (entry['protocol'], port(entry['container_port']))
        host = (entry['protocol'], port(entry['host_port']))
        if endpoint in endpoints or host in hosts or not entry['peers']:
            raise ValueError('Service bindings must be distinct and declare peers')
        endpoints.add(endpoint)
        hosts.add(host)
        for peer in entry['peers']:
            if peer != '0.0.0.0/0':
                private(peer)
    for entry in value['internal_ports']:
        if set(entry) != {'port', 'protocol'} or entry['protocol'] not in {'tcp', 'udp'}:
            raise ValueError('Invalid internal service port')
        endpoint = (entry['protocol'], port(entry['port']))
        if endpoint in endpoints:
            raise ValueError('Internal and published ports must be distinct')
        endpoints.add(endpoint)
    if not endpoints or len(endpoints) > 16:
        raise ValueError('Shared service port list is invalid')
    if stored:
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', value['bridge']):
            raise ValueError('Invalid shared service bridge')
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
        raise ValueError('Shared services require a single IPv4 bridge')
    bridge = current.get('Options', {}).get('com.docker.network.bridge.name') or 'br-' + current['Id'][:12]
    return policy({**value, 'bridge': bridge, 'subnet': entries[0]['Subnet']}, stored=True)


def restore(value):
    entries = value['bindings'] + [{'container_port': p['port'],
                                    'protocol': p['protocol'], 'peers': []} for p in value['internal_ports']]
    chains = {f'{e["protocol"]}_{e["container_port"]}': e for e in entries}
    for tool in ('iptables', 'ip6tables'):
        previous = run(tool, '-w', '10', '-S').splitlines()
        parsed = [shlex.split(line) for line in previous]
        rules = ['*filter']
        if tool == 'iptables' and ['-N', 'DOCKER-USER'] not in parsed:
            rules.append(':DOCKER-USER - [0:0]')
        for suffix in chains:
            rules.extend([f':{PREFIX}{suffix} - [0:0]', f'-F {PREFIX}{suffix}'])
        old = {p[1] for p in parsed if p[:1] == ['-N'] and p[1].startswith(PREFIX)}
        for line, parts in zip(previous, parsed):
            if '-j' not in parts or not parts[parts.index('-j') + 1].startswith(PREFIX):
                continue
            if parts[:1] != ['-A'] or parts[1] not in {'INPUT', 'DOCKER-USER', 'FORWARD'} or MARK not in parts:
                raise ValueError('Unknown rule references the shared service firewall')
            rules.append('-D' + line[2:])
        for chain in sorted(old - {PREFIX + suffix for suffix in chains}):
            rules.extend([f'-F {chain}', f'-X {chain}'])
        for suffix, entry in chains.items():
            chain = PREFIX + suffix
            if tool == 'iptables':
                rules.append(f'-A {chain} -i {value["bridge"]} -s {value["subnet"]} -j RETURN')
                for peer in entry['peers']:
                    rules.append(f'-A {chain} -s {peer} -j RETURN')
            rules.append(f'-A {chain} -j DROP')
            if tool != 'iptables':
                continue
            marker = f'-m comment --comment {MARK}'
            direction = '-m conntrack --ctdir ORIGINAL'
            forward = f'-I DOCKER-USER 1 -o {value["bridge"]} -p {entry["protocol"]} --dport {entry["container_port"]} {direction}'
            if 'host_port' in entry:
                endpoint = f'--ctorigdst {value["bind_ip"]} --ctorigdstport {entry["host_port"]}'
                rules.append(f'{forward} {endpoint} {marker} -j {chain}')
                rules.append(f'-I INPUT 1 ! -i lo -d {value["bind_ip"]} -p {entry["protocol"]} --dport {entry["host_port"]} {direction} {marker} -j {chain}')
            else:
                # Internal listeners must not claim another app's published container port.
                rules.append(f'{forward} ! --ctstate DNAT {marker} -j {chain}')
        # The runtime bridge is IPv4-only; old IPv6 hooks are removed above.
        rules.append('COMMIT')
        run(tool + '-restore', '--wait', '10', '--noflush', content='\n'.join(rules) + '\n')


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {'validate', 'apply', 'restore'}:
        raise ValueError('Expected validate, apply or restore')
    operation = sys.argv[1]
    if operation == 'validate':
        policy(json.load(sys.stdin))
        print('Shared service firewall policy is valid')
        return
    if os.geteuid() != 0:
        raise ValueError('Shared service firewall changes require root')
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
    print('Shared service firewall applied')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, IndexError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'[shared-firewall] {error}', file=sys.stderr)
        sys.exit(1)
