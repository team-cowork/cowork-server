#!/usr/bin/env python3
"""Apply the initial GitHub-to-Vault trust with an explicitly supplied admin token."""
import json
import os
from pathlib import Path
import ssl
import urllib.error
import urllib.request


def main():
    address = os.environ['VAULT_ADDR'].rstrip('/')
    if not address.startswith('https://'):
        raise ValueError('Vault bootstrap requires authenticated HTTPS')
    token = os.environ['VAULT_TOKEN']
    context = ssl.create_default_context()

    def request(path, value=None):
        req = urllib.request.Request(address + '/v1/' + path,
            data=json.dumps(value).encode() if value is not None else None,
            headers={'X-Vault-Token': token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, context=context, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else {}
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'Vault bootstrap failed: {path} (HTTP {error.code})') from None

    spec = json.loads(Path(__file__).with_name('vault-auth.json').read_text())
    # Git declares each slot's secret dependencies. Runtime and update tokens
    # receive only their own exact paths; the IaC runner cannot change policies.
    slots = Path(__file__).resolve().parents[1] / 'prod/slots'
    for path in sorted(slots.glob('*.json')):
        slot = json.loads(path.read_text())
        if slot['kind'] not in {'application', 'config'}:
            continue
        target = path.stem
        references = {reference['path'] for section in slot.get('secrets', {}).values()
                      for reference in section.values()}
        spec['policies']['cowork-deploy-' + target] = {
            'secret/data/' + name: ['read'] for name in sorted(references | {'deploy/' + target})}
        writable = {'deploy/' + target}
        if 'slot-secrets/' + target in references:
            writable.add('slot-secrets/' + target)
        if slot['kind'] == 'application':
            writable.update({'cowork-' + target, 'cowork-' + target + '/prod'})
        spec['policies']['cowork-update-' + target] = {
            'secret/data/' + name: ['create', 'update'] for name in sorted(writable)}
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
    request('auth/' + mount + '/config', spec['config'])
    request('auth/' + mount + '/role/' + spec['role_name'], spec['role'])
    print('Applied GitHub OIDC trust and the scoped IaC read policy')


if __name__ == '__main__':
    main()
