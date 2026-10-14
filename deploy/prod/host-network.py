#!/usr/bin/env python3
"""Reconcile an immutable bridge after verifying the host's external-route MTU."""
import json
from pathlib import Path
import runpy
import subprocess
import sys

SLOTS = runpy.run_path(str(Path(__file__).with_name('slots.py')))
demand = SLOTS['demand']


def json_command(arguments):
    return json.loads(subprocess.check_output(arguments, text=True))


def verify_egress(mtu):
    # A routing-table lookup sends no traffic and selects the actual external route.
    routes = json_command(['ip', '-j', '-4', 'route', 'get', '1.1.1.1'])
    demand(isinstance(routes, list) and routes, 'No usable external IPv4 route')
    for route in routes:
        interface = route.get('dev')
        demand(isinstance(interface, str) and interface, 'External route has no egress interface')
        links = json_command(['ip', '-j', 'link', 'show', 'dev', interface])
        demand(len(links) == 1 and type(links[0].get('mtu')) is int, 'Cannot read the egress interface MTU')
        effective = links[0]['mtu']
        route_metrics = route.get('metrics', [])
        if isinstance(route_metrics, dict):
            route_metrics = [route_metrics]
        demand(isinstance(route_metrics, list), 'Invalid external route metrics')
        for metrics in route_metrics:
            if isinstance(metrics, dict) and 'mtu' in metrics:
                effective = min(effective, int(metrics['mtu']))
        demand(effective >= mtu, f'Egress interface {interface} MTU {effective} is below the required bridge MTU {mtu}')


def verify_network(value, desired):
    demand(isinstance(value, list) and len(value) == 1, 'Docker returned an invalid network document')
    network = value[0]
    demand(network.get('Name') == desired['name'] and network.get('Driver') == 'bridge'
           and network.get('Scope') == 'local' and network.get('Internal') is False,
           'Existing application network has an incompatible driver, scope or egress policy')
    demand(network.get('Options') == {'com.docker.network.driver.mtu': str(desired['mtu'])},
           'Existing application network driver options do not match Git; choose a new versioned name')


def main():
    desired = SLOTS['host_network']()
    verify_egress(desired['mtu'])
    existing = subprocess.check_output(['docker', 'network', 'ls', '--format', '{{.Name}}'], text=True).splitlines()
    created = desired['name'] not in existing
    if created:
        subprocess.run(['docker', 'network', 'create', '--driver', 'bridge', '--opt',
                        f"com.docker.network.driver.mtu={desired['mtu']}", desired['name']],
                       check=True, stdout=subprocess.DEVNULL)
    verify_network(json_command(['docker', 'network', 'inspect', desired['name']]), desired)
    print(json.dumps({'name': desired['name'], 'mtu': desired['mtu'], 'created': created}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'[host-network] {error}', file=sys.stderr)
        sys.exit(1)
