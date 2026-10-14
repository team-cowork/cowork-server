#!/usr/bin/env python3
"""Apply module-owned Elasticsearch without touching application or database containers."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
from urllib.parse import unquote, urlsplit


SOURCE = Path(__file__).resolve().parent
DEPLOY = SOURCE.parents[1]
RUNTIME = runpy.run_path(str(SOURCE / 'runtime.py'))
run, write = RUNTIME['run'], RUNTIME['write']


def image():
    dockerfile = DEPLOY / 'images/elasticsearch/Dockerfile'
    content = dockerfile.read_text()
    if not re.fullmatch(r'FROM docker\.elastic\.co/elasticsearch/elasticsearch:[0-9][A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}', content.splitlines()[0]):
        raise ValueError('Elasticsearch requires its versioned official image digest')
    return dockerfile, 'cowork-elasticsearch:prod-' + hashlib.sha256(content.encode()).hexdigest()


def credentials(module, value, secrets):
    if set(secrets) != {'ELASTICSEARCH_URL', 'ELASTICSEARCH_ADMIN_PASSWORD'}:
        raise ValueError('Search apply requires the application URL and administrator password')
    if not all(isinstance(v, str) and v and not any(c in v for c in '\r\n\0') for v in secrets.values()):
        raise ValueError('Search credentials must be nonempty single-line strings')
    node = urlsplit(secrets['ELASTICSEARCH_URL'])
    if (node.scheme != 'http' or node.hostname != f'cowork-{module}-search' or node.port != 9200 or
            unquote(node.username or '') != value['username'] or not node.password or
            node.path not in ('', '/') or node.query or node.fragment):
        raise ValueError('The search URL must address its module-local authenticated container')
    password = unquote(node.password)
    if any(c in password for c in '\r\n\0') or password == secrets['ELASTICSEARCH_ADMIN_PASSWORD']:
        raise ValueError('Search application and administrator credentials must differ')
    return password


def request(container, password, path, method='GET', value=None, username='elastic'):
    authorization = base64.b64encode(f'{username}:{password}'.encode()).decode()
    config = [f'url = "http://127.0.0.1:9200{path}"', f'request = "{method}"',
              f'header = "Authorization: Basic {authorization}"', 'header = "Content-Type: application/json"']
    if value is not None:
        config.append('data = ' + json.dumps(json.dumps(value)))
    output = run('docker', 'exec', '-i', container, 'curl', '--silent', '--show-error', '--fail',
                 '--max-time', '45', '--config', '-', content='\n'.join(config) + '\n', timeout=60)
    return json.loads(output)


def reconcile(container, value, app_password, current_admin, next_admin):
    role = value['username'] + '_search'
    request(container, current_admin, f'/_security/role/{role}', 'PUT', {
        'cluster': [], 'indices': [{'names': value['indices'], 'privileges': ['manage', 'read', 'write']}],
        'applications': [], 'run_as': [],
    })
    request(container, current_admin, f'/_security/user/{value["username"]}', 'PUT', {
        'password': app_password, 'roles': [role], 'enabled': True,
    })
    request(container, current_admin, '/_security/user/elastic/_password', 'POST', {'password': next_admin})


def apply(module, value, directory):
    if os.geteuid() != 0:
        raise ValueError('Search infrastructure apply requires root')
    os.umask(0o077)
    secrets = json.load(sys.stdin)
    app_password = credentials(module, value, secrets)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        slots = runpy.run_path(str(DEPLOY / 'prod/slots.py'))
        slot = slots['load'](module, DEPLOY / 'prod/slots')
        network = slots['host_network'](DEPLOY / 'prod')['name']
        policy = json.loads(Path('/etc/cowork/module-database-firewall/policy.json').read_text())
        if (policy['network'] != network or policy['bind_ip'] != slot['container']['advertise_address'] or
                policy.get('local_ports') != [9200, 9300]):
            raise ValueError('Install the module search firewall before starting Elasticsearch')
        run('systemctl', 'is-enabled', '--quiet', 'cowork-module-database-firewall.service')
        run('/usr/bin/python3', '-I', '/usr/local/libexec/cowork-module-database-firewall.py', 'restore')
        previous = json.loads((directory / 'credentials.json').read_text()) if (directory / 'credentials.json').exists() else {}
        dockerfile, reference = image()
        run('docker', 'build', '--file', str(dockerfile), '--tag', reference, str(dockerfile.parent))
        name = f'cowork-{module}-search'
        volume = name + '-data'
        run('docker', 'volume', 'create', volume)
        # Elasticsearch starts as UID 1000. Only the volume root needs initial ownership.
        run('docker', 'run', '--rm', '--user', '0', '--network', 'none', '--entrypoint', '/bin/chown',
            '--mount', f'type=volume,source={volume},target=/usr/share/elasticsearch/data',
            reference, '1000:0', '/usr/share/elasticsearch/data')
        RUNTIME['environment'](directory / 'search.env', {
            'ELASTIC_PASSWORD': secrets['ELASTICSEARCH_ADMIN_PASSWORD'],
            'ES_JAVA_OPTS': f'-Xms{value["heap"]} -Xmx{value["heap"]}',
            'discovery.type': 'single-node', 'cluster.name': name,
            'xpack.security.enabled': 'true', 'xpack.security.autoconfiguration.enabled': 'false',
            'xpack.security.http.ssl.enabled': 'false', 'xpack.security.transport.ssl.enabled': 'false',
        })
        compose = {'name': name, 'services': {'search': {
            'image': reference, 'container_name': name, 'restart': 'unless-stopped',
            'env_file': [{'path': './search.env', 'format': 'raw'}], 'networks': ['runtime'],
            'mem_limit': value['memory'], 'cpus': value['cpus'], 'pids_limit': 512,
            'security_opt': ['no-new-privileges:true'], 'cap_drop': ['ALL'], 'stop_grace_period': '60s',
            'ulimits': {'nofile': {'soft': 65535, 'hard': 65535}},
            'volumes': [{'type': 'volume', 'source': 'data', 'target': '/usr/share/elasticsearch/data', 'volume': {'nocopy': True}}],
            'logging': {'driver': 'json-file', 'options': {'max-size': '20m', 'max-file': '3'}},
            # Authentication must be active; this also works during an administrator password change.
            'healthcheck': {'test': ['CMD-SHELL', 'test "$$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:9200/)" = 401'],
                            'interval': '5s', 'timeout': '5s', 'retries': 48, 'start_period': '30s'},
        }}, 'volumes': {'data': {'external': True, 'name': volume}},
                   'networks': {'runtime': {'external': True, 'name': network}}}
        write(directory / 'compose.json', json.dumps(compose, indent=2) + '\n')
        command = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(directory / 'compose.json')]
        run(*command, 'config', '--quiet')
        run(*command, 'up', '-d', '--wait', '--wait-timeout', '300')
        admins = [secrets['ELASTICSEARCH_ADMIN_PASSWORD']]
        old = previous.get('ELASTICSEARCH_ADMIN_PASSWORD')
        if old and old not in admins:
            admins.append(old)
        for index, admin in enumerate(admins):
            try:
                reconcile(name, value, app_password, admin, secrets['ELASTICSEARCH_ADMIN_PASSWORD'])
                break
            except RuntimeError:
                if index == len(admins) - 1:
                    raise
        write(directory / 'credentials.json', json.dumps(secrets) + '\n')
        request(name, app_password, '/_security/_authenticate', username=value['username'])
        print(f'{module}: authenticated search account applied; durable volume retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'apply'))
    parser.add_argument('module', nargs='?')
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    values = {module: item['search'] for module, item in RUNTIME['catalog']().items() if 'search' in item}
    image()
    if args.operation == 'check':
        print(f'Validated {len(values)} module search definitions; no runtime changes')
        return
    if args.module not in values:
        raise ValueError('Search apply requires a configured module')
    directory = args.directory or Path('/root/cowork-iac/search') / args.module
    apply(args.module, values[args.module], directory.resolve())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        # URL and remote diagnostics can contain credentials.
        print('[module-search] Search apply failed; inspect the module search container locally', file=sys.stderr)
        sys.exit(1)
