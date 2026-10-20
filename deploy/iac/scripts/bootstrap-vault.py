#!/usr/bin/env python3
"""Apply the initial GitHub-to-Vault trust with an explicitly supplied admin token."""
import json
import os
from pathlib import Path
import ssl
import urllib.error
import urllib.request
from urllib.parse import urlsplit


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Vault bootstrap redirects are not allowed')


def main():
    address = os.environ['VAULT_ADDR'].rstrip('/')
    endpoint = urlsplit(address)
    if (endpoint.scheme != 'https' or not endpoint.hostname or endpoint.username is not None
            or endpoint.password is not None or endpoint.path or endpoint.query or endpoint.fragment):
        raise ValueError('Vault bootstrap requires a canonical HTTPS address without credentials')
    token = os.environ['VAULT_TOKEN']
    if not token or any(character in token for character in '\r\n\0'):
        raise ValueError('Vault bootstrap requires an administrator token')
    context = ssl.create_default_context()
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))

    def request(path, value=None, *, method=None, allow_missing=False):
        req = urllib.request.Request(address + '/v1/' + path,
            data=json.dumps(value).encode() if value is not None else None,
            method=method,
            headers={'X-Vault-Token': token, 'Content-Type':
                     'application/merge-patch+json' if method == 'PATCH' else 'application/json'})
        try:
            with opener.open(req, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else {}
        except urllib.error.HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            raise RuntimeError(f'Vault bootstrap failed: {path} (HTTP {error.code})') from None

    iac = Path(__file__).resolve().parents[1]
    spec = json.loads((iac / 'policy/vault-auth.json').read_text())
    policy = spec['policies']['cowork-iac-read']
    for category in ('databases', 'storage'):
        for module in json.loads((iac / category / 'catalog.json').read_text()):
            policy[f'secret/data/iac/{category}/{module}'] = ['read']
            policy['secret/data/slot-secrets/' + module] = ['read']
    shared = json.loads((iac / 'shared/catalog.json').read_text())
    for service in shared.values():
        for reference in service['secrets'].values():
            policy['secret/data/' + reference['path']] = ['read']
    # Git declares each workload's secret dependencies. OIDC roles receive only
    # their own exact paths; the IaC runner cannot change policies.
    slots = Path(__file__).resolve().parents[2] / 'prod/slots'
    catalog = json.loads((slots.parent / 'configuration.json').read_text())
    # Every client may update this derived document during one serialized sync.
    # Retain its pre-run version until the combined rollback can restore it.
    backend_versions = request('secret/config')['data'].get('max_versions', 0)
    account_metadata_path = 'secret/metadata/control-plane/prod/server'
    account_metadata = request(account_metadata_path, allow_missing=True)
    account_versions = account_metadata['data'].get('max_versions', 0) if account_metadata else 0
    if any(type(value) is not int or value < 0 for value in (backend_versions, account_versions)):
        raise ValueError('Invalid Vault secret version retention')
    # A key's zero value inherits the backend; backend zero means ten versions.
    retained_versions = account_versions or backend_versions or 10
    required_versions = len(catalog['targets']) + 1
    if retained_versions < required_versions:
        request(account_metadata_path, {'max_versions': required_versions},
                method='PATCH' if account_metadata else 'POST')
    managed_paths = set()
    roles = {}

    def input_paths(target):
        config = catalog['targets'][target]
        entries = list(config.get('variables', {}).values()) + list(config.get('secrets', {}).values())
        entries += [value for value in catalog['shared'].values() if target in value['targets']]
        paths = {'runtime/' + target}
        for entry in entries:
            for reference in entry.get('writes', []):
                if reference.get('target', target) == target:
                    paths.update(reference['path'].replace('{profile}', profile) for profile in ('local', 'prod'))
        if target != 'config':
            paths.add('control-plane/prod/server')
        return paths

    def settings_policy(paths, writable):
        result = {}
        for name in sorted(paths):
            result['secret/data/' + name] = ['read', 'create', 'update'] if name in writable else ['read']
            result['secret/metadata/' + name] = ['read']
            if name in writable:
                result['secret/delete/' + name] = ['update']
        return result

    def settings_role(name, environment=None):
        role = dict(spec['role'], token_policies=[name])
        role['bound_subject'] = ('repo:team-cowork/cowork-server:environment:' + environment
                                 if environment else 'repo:team-cowork/cowork-server:ref:refs/heads/main')
        role['bound_claims'] = {**role['bound_claims'],
            'workflow_ref': 'team-cowork/cowork-server/.github/workflows/cowork-prod-cd.yml@refs/heads/main'}
        return role

    for target in catalog['targets']:
        writable = input_paths(target)
        managed_paths.update(writable)
        readable = writable | {'deploy/' + target}
        slot = json.loads((slots / (target + '.json')).read_text())
        refs = [ref for values in slot.get('secrets', {}).values() for ref in values.values()]
        readable.update(ref['path'] for ref in refs if 'approle' not in ref)
        name = 'prod-settings-' + target
        spec['policies'][name] = settings_policy(readable, writable)
        # Deployments issue the slot's AppRole secret ID and retire the superseded ones.
        for role in sorted({f"auth/{ref['mount']}/role/{ref['approle']}" for ref in refs if 'approle' in ref}):
            spec['policies'][name].update({
                role + '/role-id': ['read'],
                role + '/secret-id': ['update', 'list'],
                role + '/secret-id-accessor/destroy': ['update']})
        roles[name] = settings_role(name, 'Prod-CD(' + target + ')')
        # IaC consumes applied GitHub inputs through Vault, not another job's environment.
        for path in readable:
            policy['secret/data/' + path] = ['read']
    spec['policies']['prod-settings-plan'] = settings_policy(managed_paths, set())
    roles['prod-settings-plan'] = settings_role('prod-settings-plan')
    spec['policies']['prod-settings-recovery'] = settings_policy(managed_paths, managed_paths)
    roles['prod-settings-recovery'] = settings_role('prod-settings-recovery', 'Prod-IaC')
    # Vault can authenticate with OIDC while healthy. Offline recovery supplies
    # the same slot's secrets from GitHub without contacting Vault.
    vault_slot = json.loads((slots / 'vault.json').read_text())
    vault_paths = {'deploy/vault'} | {ref['path'] for refs in vault_slot.get('secrets', {}).values()
                                    for ref in refs.values()}
    spec['policies']['prod-deploy-vault'] = {
        'secret/data/' + path: ['read'] for path in sorted(vault_paths)}
    roles['prod-deploy-vault'] = settings_role('prod-deploy-vault', 'Prod-CD(vault)')
    mount = spec['mount']
    methods = request('sys/auth')['data']
    if mount + '/' not in methods:
        request('sys/auth/' + mount, {'type': 'jwt', 'description': 'GitHub production IaC'})
    elif methods[mount + '/']['type'] != 'jwt':
        raise ValueError('The declared auth mount has a different type')
    for name, paths in spec['policies'].items():
        policy = '\n'.join(f'path {json.dumps(path)} {{ capabilities = {json.dumps(capabilities)} }}'
                           for path, capabilities in paths.items())
        request('sys/policies/acl/' + name, {'policy': policy})
    # The public endpoint is audited through the container log, which the host log agent ships.
    devices = request('sys/audit')['data']
    if 'file/' not in devices:
        request('sys/audit/file', {'type': 'file', 'options': {'file_path': 'stdout'}})
    elif devices['file/']['type'] != 'file' or devices['file/']['options'].get('file_path') != 'stdout':
        raise ValueError('The declared audit device has a different destination')
    request('auth/' + mount + '/config', spec['config'])
    request('auth/' + mount + '/role/' + spec['role_name'], spec['role'])
    for name, role in roles.items():
        request('auth/' + mount + '/role/' + name, role)
    # A production Config backend may serve either declared client profile.
    config_policy = {}
    for target in catalog['targets']:
        if target in {'config', 'monitoring'}:
            continue
        for suffix in ('', '/local', '/prod'):
            config_policy['secret/data/cowork-' + target + suffix] = ['read']
    config_policy.update({'auth/token/lookup-self': ['read'], 'auth/token/renew-self': ['update'],
                          'auth/token/revoke-self': ['update']})
    request('sys/policies/acl/cowork-config-prod', {'policy': '\n'.join(
        f'path {json.dumps(path)} {{ capabilities = {json.dumps(capabilities)} }}'
        for path, capabilities in config_policy.items())})
    # Config logs in directly over the private TLS listener, so Vault sees its own address.
    config_slot = json.loads((slots / 'config.json').read_text())
    approle = next(ref for ref in config_slot['secrets']['runtime'].values() if 'approle' in ref)
    config_address = [config_slot['container']['advertise_address'] + '/32']
    request(f"auth/{approle['mount']}/role/{approle['approle']}", {
        'token_policies': ['cowork-config-prod'], 'token_no_default_policy': True, 'token_type': 'service',
        'token_ttl': 3600, 'token_max_ttl': 86400, 'token_explicit_max_ttl': 86400, 'token_num_uses': 0,
        'bind_secret_id': True, 'secret_id_ttl': 0, 'secret_id_num_uses': 0,
        'secret_id_bound_cidrs': config_address, 'token_bound_cidrs': config_address})
    print('Applied scoped GitHub deployment, settings, IaC and Config workload policies')


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        raise SystemExit(str(error)) from None
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit('Vault bootstrap failed; inspect the declared endpoint, credentials and policies') from None
