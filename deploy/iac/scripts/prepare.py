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
    values = {'runtime': {}, 'application': {}}
    versions = {}
    references = {}
    for section in ('runtime', 'application'):
        for key, ref in slot.get('secrets', {}).get(section, {}).items():
            if ref['path'] not in references:
                references[ref['path']] = VAULT['vault_request'](f"{mount}/data/{ref['path']}")['data']
            document = references[ref['path']]
            values[section][key] = document['data'][ref['key']]
            versions[ref['path']] = document['metadata']['version']
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
        if entry['service'] == 'db':
            demand((ROOT / 'deploy/iac/policy/datastore-policy.json').is_file(), 'DB infrastructure requires its reviewed Git firewall policy')
    monitoring_hosts = [host for host, entry in hosts.items() if entry.get('slot') == 'monitoring']
    demand(len(monitoring_hosts) == 1, 'Exactly one monitoring host is required')
    monitoring_host = monitoring_hosts[0]
    monitoring_settings = snapshot('monitoring', mount)
    demand('GRAFANA_ADMIN_PASSWORD' in monitoring_settings['runtime'], 'Monitoring requires Grafana bootstrap credentials')
    github_secret_sources = SLOTS['load']('monitoring').get('github_secrets', {})
    demand(github_secret_sources == {'DISCORD_MONITORING_ALERT_WEBHOOK': 'DISCORD_MONITORING_ALERT_WEBHOOK'},
           'Monitoring must declare its GitHub monitoring webhook source')
    webhook = os.environ.get('DISCORD_MONITORING_ALERT_WEBHOOK', '')
    demand(bool(webhook) and '\0' not in webhook, 'The declared GitHub monitoring webhook is required')
    monitoring_settings['runtime']['DISCORD_MONITORING_ALERT_WEBHOOK'] = webhook
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
                                     'cowork_image_owner': args.image_owner}, 'hosts': {}}}
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
            if 'slot' in entry and SLOTS['load'](entry['slot'])['kind'] in {'application', 'config'}:
                host['cowork_runtime_network'] = True
            if 'slot' in entry and entry['service'] not in {'gateway', 'vault', 'datastores', 'storage'}:
                host['cowork_firewall_target'] = entry['slot']
            if 'source_repository' in entry:
                host['cowork_source_repository'] = entry['source_repository']
            if entry['service'] == 'db':
                host['cowork_datastore_firewall'] = True
            if host_id == monitoring_host:
                host['cowork_disabled_services'] = ['caddy']
                settings_file = output / 'settings/monitoring.json'
                write_private(settings_file, json.dumps(monitoring_settings))
                host['cowork_units'].append({'service': 'monitoring', 'target': 'monitoring', 'settings_file': str(settings_file)})
            target = f'log-agent-{host_id}'
            settings_file = output / 'settings' / f'{target}.json'
            write_private(settings_file, json.dumps(snapshot(target, mount, base={}, base_version=host_version)))
            host['cowork_units'].append({'service': 'log-agent', 'target': target, 'settings_file': str(settings_file)})
            inventory['all']['hosts'][host_id] = host
        write_private(output / 'inventory.json', json.dumps(inventory, indent=2) + '\n')
        write_private(output / 'ssh-config', '\n'.join(ssh_config) + '\n')
        grafana = {'GRAFANA_URL': 'http://127.0.0.1:43001',
                   'GRAFANA_AUTH': 'admin:' + monitoring_settings['runtime']['GRAFANA_ADMIN_PASSWORD']}
        write_private(output / 'grafana.env0', b''.join(f'{key}={value}\0'.encode() for key, value in grafana.items()), binary=True)
        write_private(output / 'metadata.json', json.dumps({'sha': args.sha, 'hosts_version': host_version,
                       'monitoring_ssh_alias': f'cowork-iac-{monitoring_host}', 'grafana_local_port': 43001,
                       'grafana_remote_port': 3001, 'host_count': len(hosts),
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
    print(f'Prepared {len(hosts)} hosts and {len(hosts) + 1} monitoring units; credentials remain in private files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--image-owner', default='team-cowork')
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
