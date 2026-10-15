#!/usr/bin/env python3
"""Resolve Vault credentials into private, immutable inputs for the IaC apply step."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
VAULT = runpy.run_path(str(ROOT / 'deploy/prod/cd/vault-settings.py'))
SLOTS = runpy.run_path(str(ROOT / 'deploy/prod/slots.py'))
demand = VAULT['demand']
NAME = VAULT['NAME']
CREATED_PATHS = []
REFERENCE_VERSIONS = VAULT['validate_versions'](VAULT['parse_json'](os.environ.get('VAULT_REFERENCE_VERSIONS', '{}')))


def managed_target(target):
    catalog = ROOT / 'deploy/prod/configuration.json'
    return catalog.is_file() and target in VAULT['parse_json'](catalog.read_text()).get('targets', {})


def runtime_values(target, mount):
    """Resolve the same effective variables used by the target container."""
    slot = SLOTS['load'](target)
    path = f'runtime/{target}'
    document = VAULT['read_document'](path, REFERENCE_VERSIONS.get(path, 0),
                                      allow_missing=not managed_target(target) and path not in REFERENCE_VERSIONS, mount=mount)
    if document is None:
        return dict(slot.get('runtime', {}))
    document = document['data']
    demand(isinstance(document, dict) and all(isinstance(key, str) and VAULT['ENV_NAME'].fullmatch(key)
           and isinstance(value, str) and '\0' not in value for key, value in document.items()),
           'Invalid synchronized runtime settings')
    if managed_target(target):
        catalog = VAULT['parse_json']((ROOT / 'deploy/prod/configuration.json').read_text())
        declared = set(catalog['targets'][target].get('variables', {}))
        declared.update(key for key, spec in catalog['shared'].items()
                        if spec['kind'] == 'variable' and target in spec['targets'])
        demand(set(document) == declared, 'Runtime settings must match the declared GitHub variables')
    return SLOTS['environment'](slot, document)


def settings_selection(raw, databases, storage, shared):
    if raw is None:
        return None
    choices = ({f'database:{name}' for name in databases} | {f'storage:{name}' for name in storage}
               | {f'search:{name}' for name, spec in databases.items() if 'search' in spec}
               | {f'shared:{name}' for name in shared} | {'monitoring'})
    selected = set(raw.split(','))
    demand(bool(selected) and selected <= choices, 'Settings apply requires declared infrastructure selectors')
    return selected


def write_private(path, content, binary=False):
    with path.open('xb' if binary else 'x') as stream:
        CREATED_PATHS.append(path)
        os.chmod(path, 0o600)
        stream.write(content)


def ssh_quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


def validate_host(host_id, entry):
    demand(NAME.fullmatch(host_id), 'Invalid IaC host identity')
    demand(isinstance(entry, dict) and set(entry) <= {'ssh', 'service', 'slot', 'source_repository'}, 'Invalid IaC host fields')
    demand(isinstance(entry.get('service'), str) and NAME.fullmatch(entry['service']), 'Invalid IaC service identity')
    if 'slot' in entry:
        demand(isinstance(entry['slot'], str) and NAME.fullmatch(entry['slot']), 'Invalid IaC slot reference')
    if 'source_repository' in entry:
        demand(isinstance(entry['source_repository'], str) and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', entry['source_repository']), 'Invalid source repository identity')
    ssh = entry.get('ssh', {})
    demand(isinstance(ssh, dict) and set(ssh) == {'host', 'port', 'user', 'key', 'known_hosts'}, 'IaC SSH fields must include verified known_hosts data')
    demand(isinstance(ssh['host'], str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', ssh['host']), 'Invalid SSH host name')
    demand(type(ssh['port']) is int and 1 <= ssh['port'] <= 65535, 'Invalid SSH port')
    demand(isinstance(ssh['user'], str) and re.fullmatch(r'[a-z_][a-z0-9_-]*', ssh['user']), 'Invalid SSH account')
    demand(isinstance(ssh['key'], str) and 'PRIVATE KEY-----' in ssh['key'] and '\0' not in ssh['key'], 'Missing SSH private key')
    demand(isinstance(ssh['known_hosts'], str) and ssh['known_hosts'].strip()
           and '\0' not in ssh['known_hosts'], 'Verified SSH host keys are required')
    # OpenSSH verifies the host and port with StrictHostKeyChecking enabled,
    # including its native hashed-host and alias formats.
    return ssh


def snapshot(target, mount, base=None, base_version=None):
    slot = SLOTS['load'](target)
    if base is None:
        result = VAULT['vault_request'](f'{mount}/data/deploy/{target}')['data']
        base, base_version = result['data'], result['metadata']['version']
        demand(set(base) == {'ssh'}, 'A slot deployment document must contain SSH credentials only')
        VAULT['validate_deployment'](base, target, slot)
    values = VAULT['resolved_snapshot'](target, slot, mount, REFERENCE_VERSIONS,
                                        require_variables=managed_target(target))
    versions = values.pop('secret_versions')
    VAULT['validate_deployment']({**values, 'ssh': {'host': 'unused', 'port': 22, 'user': 'unused', 'key': 'unused', 'fingerprint': 'SHA256:unused'}}, target, slot)
    slot_path = ROOT / 'deploy/prod/slots' / f'{target}.json'
    values.update(vault_version=base_version, secret_versions=versions,
                  slot_digest=hashlib.sha256(slot_path.read_bytes()).hexdigest())
    return values


def prepare(args):
    CREATED_PATHS.clear()
    demand(re.fullmatch(r'[0-9a-f]{40}', args.sha), 'The release SHA must be a full commit identity')
    demand(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() == args.sha,
           'Checkout must match the selected immutable release')
    demand(subprocess.run(['git', 'diff', '--quiet', args.sha, '--', 'deploy'], cwd=ROOT).returncode == 0,
           'Commit deployment source changes before preparing an immutable apply')
    demand(not subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '--', 'deploy'], cwd=ROOT).strip(),
           'Untracked deployment sources cannot enter an immutable apply')
    output = args.output
    demand(output.is_absolute() and '\n' not in str(output) and '\r' not in str(output) and '\0' not in str(output), 'Output must be an absolute private path')
    demand(not output.resolve().is_relative_to(ROOT), 'IaC credentials must remain outside the repository')
    if output.exists():
        demand(output.is_dir() and not output.is_symlink() and output.stat().st_uid == os.getuid()
               and not output.stat().st_mode & 0o077 and not any(output.iterdir()), 'Output must be an empty private directory owned by the invoking user')
    mount = os.environ.get('VAULT_KV_MOUNT', 'secret')
    demand(NAME.fullmatch(mount), 'Invalid Vault KV mount')
    result = VAULT['vault_request'](f'{mount}/data/iac/hosts')['data']
    hosts_document, host_version = result['data'], result['metadata']['version']
    demand(isinstance(hosts_document, dict) and set(hosts_document) == {'hosts'}
           and isinstance(hosts_document['hosts'], dict) and hosts_document['hosts'], 'Invalid IaC host inventory')
    hosts = hosts_document['hosts']
    databases = json.loads((ROOT / 'deploy/iac/databases/catalog.json').read_text())
    storage = json.loads((ROOT / 'deploy/iac/storage/catalog.json').read_text())
    shared = json.loads((ROOT / 'deploy/iac/shared/catalog.json').read_text())
    selection = settings_selection(args.settings_targets, databases, storage, shared)
    external_databases = set()
    for name, database in databases.items():
        if selection is not None and f'database:{name}' not in selection:
            continue
        runtime = runtime_values(name, mount)
        local_port = {'mysql': '3306', 'postgres': '5432', 'mongodb': '27017'}[database['engine']]
        if runtime.get('DB_HOST') != f'cowork-{name}-db' or runtime.get('DB_PORT') != local_port:
            external_databases.add(name)
            print(f'[iac] {name}: external database selected; its provider account must already exist; local database retained')
    if selection is not None:
        selection -= {f'database:{name}' for name in external_databases}

    def selected(kind, name):
        return (kind != 'database' or name not in external_databases) and (selection is None or f'{kind}:{name}' in selection)
    demand((set(databases) | set(storage) | set(shared)).issubset(hosts),
           'Each declared infrastructure service needs its VM in the IaC inventory')
    demand(len(hosts) <= 128, 'IaC host inventory exceeds the supported budget')
    expected_hosts = {path.stem.removeprefix('log-agent-') for path in (ROOT / 'deploy/prod/slots').glob('log-agent-*.json')}
    demand(set(hosts) == expected_hosts, 'IaC hosts must cover exactly the log-agent slots declared in Git')
    for host_id, entry in hosts.items():
        validate_host(host_id, entry)
        log_slot = SLOTS['load'](f'log-agent-{host_id}')
        demand(log_slot['service'] == 'log-agent' and log_slot['runtime']['LOG_HOST'] == host_id
               and log_slot['runtime']['LOG_SERVICE'] == entry['service'], 'Host log identity must match its Git slot')
        if 'slot' in entry:
            demand(SLOTS['load'](entry['slot'])['service'] == entry['service'], 'Host service must match its Git slot')
        direct_slot_path = ROOT / 'deploy/prod/slots' / f'{host_id}.json'
        if direct_slot_path.is_file() and SLOTS['load'](host_id)['kind'] in {'application', 'config'}:
            demand(entry.get('slot') == host_id, 'Application and Config hosts require their explicit firewall slot')
        if host_id in shared:
            demand(entry['service'] == host_id, 'Dedicated infrastructure hosts must match their declared service')
    monitoring_hosts = [host for host, entry in hosts.items() if entry.get('slot') == 'monitoring']
    demand(len(monitoring_hosts) == 1, 'Exactly one monitoring host is required')
    monitoring_host = monitoring_hosts[0]
    monitoring_settings = None
    github_secret_sources = {}
    if selection is None or 'monitoring' in selection:
        monitoring_settings = snapshot('monitoring', mount)
        demand('GRAFANA_ADMIN_PASSWORD' in monitoring_settings['runtime'], 'Monitoring requires Grafana administrator credentials')
        github_secret_sources = SLOTS['load']('monitoring').get('github_secrets', {})
        for destination, source in github_secret_sources.items():
            webhook = os.environ.get(source, '')
            demand(bool(webhook) and '\0' not in webhook, 'The declared GitHub monitoring webhook is required')
            monitoring_settings['runtime'][destination] = webhook
        gateway = runtime_values('gateway', mount)
        monitoring_settings.setdefault('variables', {})['PUBLIC_API_BASE_URL'] = gateway['PUBLIC_API_BASE_URL']
    secret_documents = {}

    def secrets(path):
        if path not in secret_documents:
            secret_documents[path] = VAULT['read_document'](path, REFERENCE_VERSIONS.get(path, 0), mount=mount)['data']
        return secret_documents[path]

    if selection is not None:
        selected_hosts = {item.split(':', 1)[1] for item in selection if ':' in item}
        if 'monitoring' in selection:
            selected_hosts.add(monitoring_host)
        hosts = {name: entry for name, entry in hosts.items() if name in selected_hosts}

    created = not output.exists()
    try:
        output.mkdir(mode=0o700, parents=True, exist_ok=True)
        for name in ('keys', 'settings'):
            (output / name).mkdir(mode=0o700)
            CREATED_PATHS.append(output / name)
        archive = output / 'source.tar.gz'
        with archive.open('xb') as stream:
            CREATED_PATHS.append(archive)
            os.chmod(archive, 0o600)
            subprocess.run(['git', 'archive', '--format=tar.gz', args.sha], cwd=ROOT, stdout=stream, check=True)
        known_hosts = output / 'known_hosts'
        write_private(known_hosts, '\n'.join(entry['ssh']['known_hosts'].rstrip() for entry in hosts.values()) + '\n')
        ssh_config = ['Host *', '  BatchMode yes', '  StrictHostKeyChecking yes', '  IdentitiesOnly yes',
                      f'  UserKnownHostsFile {ssh_quote(known_hosts)}', '  GlobalKnownHostsFile /dev/null', '  ConnectTimeout 15']
        inventory = {'all': {'vars': {'cowork_release_sha': args.sha, 'cowork_source_archive': str(archive),
                                     'cowork_image_owner': args.image_owner,
                                     'cowork_settings_only': selection is not None}, 'hosts': {}}}
        for host_id, entry in hosts.items():
            ssh = entry['ssh']
            key_path = output / 'keys' / host_id
            write_private(key_path, ssh['key'].rstrip() + '\n')
            ssh_config.extend([f'Host cowork-iac-{host_id}', f"  HostName {ssh['host']}", f"  Port {ssh['port']}",
                               f"  User {ssh['user']}", f'  IdentityFile {ssh_quote(key_path)}'])
            known_hosts_arg = shlex.quote(str(known_hosts))
            host = {'ansible_host': ssh['host'], 'ansible_port': ssh['port'], 'ansible_user': ssh['user'],
                    'ansible_ssh_private_key_file': str(key_path),
                    'ansible_ssh_common_args': f'-o BatchMode=yes -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes -o GlobalKnownHostsFile=/dev/null -o UserKnownHostsFile={known_hosts_arg}',
                    'cowork_units': []}
            if selection is None and (host_id in shared or ('slot' in entry and SLOTS['load'](entry['slot'])['kind'] in {'application', 'config'})):
                host['cowork_runtime_network'] = True
            if selection is None and 'slot' in entry and entry['service'] not in {'gateway', 'vault'}:
                host['cowork_firewall_target'] = entry['slot']
            if 'source_repository' in entry:
                host['cowork_source_repository'] = entry['source_repository']
            if host_id in databases and (selected('database', host_id) or selected('search', host_id)):
                app = secrets(f'slot-secrets/{host_id}')
                admin = secrets(f'iac/databases/{host_id}')
                runtime = runtime_values(host_id, mount)
                host['cowork_database_module'] = host_id
                if selected('database', host_id):
                    settings_file = output / 'settings' / f'database-{host_id}.json'
                    write_private(settings_file, json.dumps({
                        'DB_PASSWORD': app['DB_PASSWORD'],
                        'DB_ADMIN_PASSWORD': admin['DB_ADMIN_PASSWORD'],
                        'DB_MONITORING_PASSWORD': admin['DB_MONITORING_PASSWORD'],
                        'DB_NAME': runtime['DB_NAME'],
                        'DB_USERNAME': runtime['DB_USERNAME'],
                    }))
                    host['cowork_database_settings_file'] = str(settings_file)
                if 'search' in databases[host_id] and selected('search', host_id):
                    search_file = output / 'settings' / f'search-{host_id}.json'
                    write_private(search_file, json.dumps({
                        'ELASTICSEARCH_URL': app['ELASTICSEARCH_URL'],
                        'ELASTICSEARCH_ADMIN_PASSWORD': admin['ELASTICSEARCH_ADMIN_PASSWORD'],
                    }))
                    host['cowork_search_settings_file'] = str(search_file)
            if host_id in storage and selected('storage', host_id):
                app = secrets(f'slot-secrets/{host_id}')
                admin = secrets(f'iac/storage/{host_id}')
                runtime = runtime_values(host_id, mount)
                config_runtime = runtime_values('config', mount)
                settings_file = output / 'settings' / f'storage-{host_id}.json'
                write_private(settings_file, json.dumps({
                    **{key: app[key] for key in ('S3_ACCESS_KEY', 'S3_SECRET_KEY')},
                    **{key: admin[key] for key in ('S3_ADMIN_ACCESS_KEY', 'S3_ADMIN_SECRET_KEY')},
                    **{key: runtime[key] for key in ('S3_BUCKET', 'S3_REGION')},
                    'PUBLIC_WEB_ORIGINS': config_runtime['PUBLIC_WEB_ORIGINS'],
                }))
                host['cowork_storage_module'] = host_id
                host['cowork_storage_settings_file'] = str(settings_file)
            if host_id in shared and selected('shared', host_id):
                settings = {key: secrets(ref['path'])[ref['key']]
                            for key, ref in shared[host_id]['secrets'].items()}
                settings_file = output / 'settings' / f'shared-{host_id}.json'
                write_private(settings_file, json.dumps(settings))
                host['cowork_shared_service'] = host_id
                host['cowork_shared_settings_file'] = str(settings_file)
            if host_id == monitoring_host and monitoring_settings is not None:
                if selection is None:
                    host['cowork_disabled_services'] = ['caddy']
                settings_file = output / 'settings/monitoring.json'
                write_private(settings_file, json.dumps(monitoring_settings))
                host['cowork_units'].append({'service': 'monitoring', 'target': 'monitoring', 'settings_file': str(settings_file)})
            if selection is None:
                target = f'log-agent-{host_id}'
                settings_file = output / 'settings' / f'{target}.json'
                write_private(settings_file, json.dumps(snapshot(target, mount, base={}, base_version=host_version)))
                host['cowork_units'].append({'service': 'log-agent', 'target': target, 'settings_file': str(settings_file)})
                node_agent_target = f'node-agent-{host_id}'
                # Vault does not have a node-agent slot yet.
                if (ROOT / 'deploy/prod/slots' / f'{node_agent_target}.json').is_file():
                    settings_file = output / 'settings' / f'{node_agent_target}.json'
                    write_private(settings_file, json.dumps(snapshot(node_agent_target, mount, base={}, base_version=host_version)))
                    host['cowork_units'].append({'service': 'node-agent', 'target': node_agent_target, 'settings_file': str(settings_file)})
            inventory['all']['hosts'][host_id] = host
        write_private(output / 'inventory.json', json.dumps(inventory, indent=2) + '\n')
        write_private(output / 'ssh-config', '\n'.join(ssh_config) + '\n')
        write_private(output / 'metadata.json', json.dumps({'sha': args.sha, 'hosts_version': host_version,
                       'host_count': len(hosts),
                       'github_secret_sources': github_secret_sources}, indent=2) + '\n')
    except BaseException:
        # Never remove a concurrent user's files, even if the directory was initially empty.
        for child in reversed(CREATED_PATHS):
            try:
                if child.is_dir(): child.rmdir()
                else: child.unlink(missing_ok=True)
            except OSError:
                pass
        if created and output.exists():
            try:
                output.rmdir()
            except OSError:
                pass
        raise
    print(f'Prepared {len(hosts)} infrastructure hosts; credentials remain in private files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--image-owner', default='team-cowork')
    parser.add_argument('--settings-targets', help='Comma-separated resource selectors; omit for a full infrastructure apply')
    args = parser.parse_args()
    demand(re.fullmatch(r'[a-z0-9][a-z0-9-]*', args.image_owner), 'Invalid image owner')
    prepare(args)


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        # Schema/Vault helpers provide fixed messages and never include response bodies.
        print(f'[iac-prepare] {error}', file=sys.stderr)
        sys.exit(1)
    except (KeyError, OSError, subprocess.CalledProcessError) as error:
        # Never print exception objects from credentials or remote response bodies.
        print(f'[iac-prepare] Preparation failed ({type(error).__name__}); inspect the declared schema and private input availability', file=sys.stderr)
        sys.exit(1)
