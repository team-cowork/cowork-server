#!/usr/bin/env python3
"""Synchronize declared GitHub inputs to versioned Vault settings."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
VAULT = runpy.run_path(str(Path(__file__).with_name('vault-settings.py')))
SLOTS = runpy.run_path(str(ROOT / 'deploy/prod/slots.py'))
demand = VAULT['demand']
read_document = VAULT['read_document']
request = VAULT['vault_request']
parse_json = VAULT['parse_json']


def load_catalog(path):
    catalog = parse_json(path.read_text())
    demand(isinstance(catalog, dict) and catalog.get('schema_version') == 1,
           'Unsupported GitHub settings catalog')
    demand(set(catalog) == {'schema_version', 'shared', 'targets'}, 'Invalid settings catalog sections')
    for target, config in catalog['targets'].items():
        demand(VAULT['NAME'].fullmatch(target), 'Invalid settings target')
        demand(isinstance(config, dict) and set(config) <= {'variables', 'secrets', 'resources'},
               'Invalid target input sections')
        for kind, entries in (('variable', config.get('variables', {})), ('secret', config.get('secrets', {}))):
            demand(isinstance(entries, dict), 'Input declarations must be objects')
            for name, spec in entries.items():
                validate_spec(name, spec, kind, False, catalog['targets'])
        demand(not set(config.get('variables', {})).intersection(config.get('secrets', {})),
               'A target input cannot be both a variable and secret')
    for name, spec in catalog['shared'].items():
        validate_spec(name, spec, spec.get('kind'), True, catalog['targets'])
        for target in spec['targets']:
            config = catalog['targets'][target]
            demand(name not in config.get('variables', {}) and name not in config.get('secrets', {}),
                   'Shared inputs cannot be shadowed by target declarations')
    return catalog


def validate_spec(name, spec, kind, shared, targets):
    demand(SLOTS['environment_key'](name), 'Invalid configured input name')
    demand(not name.startswith('GITHUB_'), 'GitHub input names cannot start with GITHUB_')
    demand(kind in {'variable', 'secret'}, 'Invalid configured input kind')
    allowed = {'writes', 'resources', 'optional', 'immutable', 'environment'}
    if shared:
        allowed |= {'kind', 'targets'}
    demand(isinstance(spec, dict) and set(spec) <= allowed, 'Invalid input declaration fields')
    if kind == 'variable':
        demand(not SLOTS['SECRET_NAME'].search(name), 'Secret names cannot be declared as variables')
    if shared:
        demand(isinstance(spec.get('targets'), list) and spec['targets']
               and len(set(spec['targets'])) == len(spec['targets'])
               and set(spec['targets']).issubset(targets), 'Shared inputs require declared target names')
    for flag in ('optional', 'immutable', 'environment'):
        demand(flag not in spec or type(spec[flag]) is bool, 'Input flags must be boolean')
    for resource in spec.get('resources', []):
        demand(isinstance(resource, str) and (resource == 'monitoring' or
               any(resource.startswith(prefix + ':') and VAULT['NAME'].fullmatch(resource.split(':', 1)[1])
                   for prefix in ('database', 'storage', 'search', 'shared'))), 'Invalid resource selector')
    writes = spec.get('writes', [])
    demand(isinstance(writes, list) and (kind != 'secret' or writes), 'Secret inputs require Vault writes')
    for ref in writes:
        demand(isinstance(ref, dict) and set(ref) <= {'path', 'key', 'target'}
               and {'path', 'key'} <= set(ref), 'Invalid input Vault write')
        demand(isinstance(ref['path'], str) and all(VAULT['NAME'].fullmatch(part)
               for part in ref['path'].replace('{profile}', 'prod').split('/')), 'Invalid input Vault path')
        demand(isinstance(ref['key'], str) and ref['key'] and '\0' not in ref['key'], 'Invalid Vault property key')
        demand('target' not in ref or ref['target'] in targets, 'Invalid Vault write target')
        demand(not shared or '{profile}' not in ref['path'] or 'target' in ref,
               'Profile-dependent shared writes require their target')


def inputs():
    variables = parse_json(os.environ.get('GH_VARIABLES_JSON', '{}'))
    secrets = parse_json(os.environ.get('GH_SECRETS_JSON', '{}'))
    for values in (variables, secrets):
        demand(isinstance(values, dict) and all(isinstance(key, str) and isinstance(value, str)
               and '\0' not in value for key, value in values.items()), 'GitHub input contexts must contain strings without NUL')
    # GitHub masks stored secrets too; register selected values again before any downstream work.
    return variables, secrets


def value_for(name, spec, kind, variables, secrets):
    source = secrets if kind == 'secret' else variables
    value = source.get(name, '')
    demand(bool(value) or spec.get('optional', False), f'Missing required GitHub {kind}: {name}')
    if kind == 'secret':
        VAULT['mask'](value)
    return value


def target_inputs(catalog, target):
    config = catalog['targets'][target]
    result = [(name, spec, 'variable') for name, spec in config.get('variables', {}).items()]
    result += [(name, spec, 'secret') for name, spec in config.get('secrets', {}).items()]
    result += [(name, spec, spec['kind']) for name, spec in catalog['shared'].items() if target in spec['targets']]
    return result


def write_refs(spec, target, profile):
    return [dict(ref, path=ref['path'].replace('{profile}', profile))
            for ref in spec.get('writes', []) if ref.get('target', target) == target]


def current(path, mount):
    document = read_document(path, allow_missing=True, mount=mount)
    if document is not None:
        return document['data'], document['metadata']['version'], document['metadata']['version']
    # A deleted KV version still owns a CAS version. Do not mistake it for a new path.
    metadata = request(f'{mount}/metadata/{path}', allow_missing=True)
    version = metadata['data']['current_version'] if metadata is not None else 0
    return {}, 0, version


def save_journal(path, journal):
    demand(path.is_absolute() and not path.resolve().is_relative_to(ROOT), 'Settings journal must remain outside the repository')
    demand(not path.is_symlink(), 'Settings journal cannot be a symbolic link')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.exists():
        demand(path.is_file() and path.stat().st_uid == os.getuid() and path.stat().st_mode & 0o077 == 0,
               'Settings journal must be private and owned by the deployment user')
    descriptor, temporary = tempfile.mkstemp(prefix='.settings-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(journal, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def emit(name, value):
    rendered = json.dumps(value, separators=(',', ':')) if isinstance(value, (dict, list, bool)) else str(value)
    if os.environ.get('GITHUB_OUTPUT'):
        VAULT['output'](name, rendered)
    print(f'{name}={rendered}')


def save_summary(path, summary):
    if path is not None:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with path.open('x') as stream:
            os.chmod(path, 0o600)
            json.dump(summary, stream)


def profile_for(target, mount):
    document = read_document(f'runtime/{target}', allow_missing=True, mount=mount)
    if document is not None:
        return document['data'].get('APP_CONFIG_PROFILE', 'prod')
    return SLOTS['load'](target)['runtime'].get('APP_CONFIG_PROFILE', 'prod')


def plan(args, catalog, mount, variables, secrets):
    selected = set(catalog['targets']) if args.target == 'all' else {target for target in args.target.split(',') if target}
    demand(selected.issubset(catalog['targets']), 'Unknown settings target selection')
    if not selected:
        emit('affected_targets', [])
        emit('resource_targets', [])
        emit('matrix', {'include': []})
        emit('targets_csv', '')
        save_summary(args.output, {'targets': [], 'resources': []})
        return
    affected = set(selected)
    resources = set()
    for name, spec in catalog['shared'].items():
        value = value_for(name, spec, spec['kind'], variables, secrets)
        changed = False
        for target in spec['targets']:
            profile = profile_for(target, mount)
            refs = write_refs(spec, target, profile)
            if spec['kind'] == 'variable':
                refs.append({'path': f'runtime/{target}', 'key': name})
            for ref in refs:
                data, _, _ = current(ref['path'], mount)
                if data.get(ref['key']) != value:
                    demand(not spec.get('immutable') or ref['key'] not in data,
                           f'{name} requires a separate data or external credential migration')
                    changed = True
        if changed:
            affected.update(spec['targets'])
            resources.update(spec.get('resources', []))
    emit('affected_targets', sorted(affected))
    emit('resource_targets', sorted(resources))
    emit('matrix', {'include': [{'target': target} for target in sorted(affected)]})
    emit('targets_csv', ','.join(sorted(affected)))
    save_summary(args.output, {'targets': sorted(affected), 'resources': sorted(resources)})


def sync(args, catalog, mount, variables, secrets):
    target = args.target
    demand(target in catalog['targets'], 'Unknown settings target')
    demand(args.journal is not None, 'A private journal path is required for configuration sync')
    demand(not args.journal.exists(), 'Use a fresh journal path for each settings sync')
    selected = [(name, spec, kind, value_for(name, spec, kind, variables, secrets))
                for name, spec, kind in target_inputs(catalog, target)]
    runtime = {name: value for name, _, kind, value in selected if kind == 'variable'}
    SLOTS['validate_variables'](runtime)
    profile = runtime.get('APP_CONFIG_PROFILE', 'prod')
    documents = {}
    effects = {}

    def document(path):
        if path not in documents:
            before, before_version, cas_version = current(path, mount)
            documents[path] = {'before': before, 'data': dict(before),
                               'before_version': before_version, 'expected_version': cas_version}
        return documents[path]

    def patch(path, key, value, spec, name):
        doc = document(path)
        if doc['data'].get(key) != value:
            demand(not spec.get('immutable') or key not in doc['before'],
                   f'{name} requires a separate data or external credential migration')
            effects.setdefault(path, set()).update(spec.get('resources', []))
        doc['data'][key] = value

    for name, spec, kind, value in selected:
        if spec.get('immutable'):
            for ref in spec.get('writes', []):
                if ref.get('target', target) != target or '{profile}' not in ref['path']:
                    continue
                previous_profile = profile_for(target, mount)
                previous, _, _ = current(ref['path'].replace('{profile}', previous_profile), mount)
                demand(ref['key'] not in previous or previous[ref['key']] == value,
                       f'{name} requires a separate data or external credential migration')
        for ref in write_refs(spec, target, profile):
            patch(ref['path'], ref['key'], value, spec, name)
    runtime_path = f'runtime/{target}'
    # Replace ordinary inputs, so deleting a declared optional input clears its old override.
    document(runtime_path)['data'] = runtime
    for name, spec, kind, value in selected:
        if kind == 'variable' and document(runtime_path)['before'].get(name) != value:
            effects.setdefault(runtime_path, set()).update(spec.get('resources', []))
    derive_config_account(target, runtime, documents, document, effects)
    reference_versions = {}
    slot = SLOTS['load'](target)
    for section in slot.get('secrets', {}).values():
        for ref in section.values():
            if ref['path'] in documents:
                demand(ref['key'] in documents[ref['path']]['data'], 'A declared Vault secret reference is missing')
            else:
                referenced = read_document(ref['path'], mount=mount)
                demand(ref['key'] in referenced['data'], 'A declared Vault secret reference is missing')
                reference_versions[ref['path']] = referenced['metadata']['version']
    changed_paths = [path for path, doc in documents.items()
                     if doc['data'] != doc['before'] or doc['before_version'] == 0]
    resources = set().union(*(effects.get(path, set()) for path in changed_paths)) if changed_paths else set()
    if changed_paths:
        resources.update(catalog['targets'][target].get('resources', []))
    affected = {target} if changed_paths else set()
    if 'control-plane/prod/server' in changed_paths:
        affected.add('config')
    journal = {'schema_version': 1, 'target': target, 'mount': mount, 'writes': [],
               'affected_targets': sorted(affected), 'resource_targets': sorted(resources)}
    save_journal(args.journal, journal)
    versions = reference_versions
    # Runtime is last: a consumer never observes its new variables before matching secrets.
    ordered_paths = sorted(documents, key=lambda path: (path == runtime_path, path))
    for path in ordered_paths:
        doc = documents[path]
        if path not in changed_paths:
            versions[path] = doc['before_version']
            continue
        entry = {'path': path, 'before_version': doc['before_version'],
                 'expected_version': doc['expected_version'], 'state': 'pending'}
        journal['writes'].append(entry)
        save_journal(args.journal, journal)
        try:
            result = request(f'{mount}/data/{path}', {'options': {'cas': doc['expected_version']}, 'data': doc['data']})
            version = result['data']['version']
        except Exception:
            # A lost HTTP response can follow a completed write. Record it only if both
            # version and full content prove that this CAS operation took effect.
            actual = read_document(path, allow_missing=True, mount=mount)
            if actual is not None and actual['metadata']['version'] == doc['expected_version'] + 1 and actual['data'] == doc['data']:
                entry.update(state='written', written_version=actual['metadata']['version'])
                save_journal(args.journal, journal)
            raise
        entry.update(state='written', written_version=version)
        versions[path] = version
        save_journal(args.journal, journal)
    journal['reference_versions'] = versions
    journal['state'] = 'complete'
    save_journal(args.journal, journal)
    emit('config_changed', bool(changed_paths))
    emit('affected_targets', sorted(affected))
    emit('resource_targets', sorted(resources))
    emit('reference_versions', versions)
    save_summary(args.output, {'target': target, 'targets': sorted(affected), 'resources': sorted(resources),
                              'reference_versions': versions, 'config_changed': bool(changed_paths)})


def derive_config_account(target, runtime, documents, document, effects):
    if target in {'config', 'vault', 'redis', 'kafka', 'livekit'}:
        return
    username = runtime.get('CONFIG_CLIENT_USERNAME')
    if username is None:
        return
    path = f'control-plane/prod/{target}' if target != 'monitoring' else 'slot-secrets/monitoring'
    credentials = document(path)['data']
    demand(credentials.get('CONFIG_CLIENT_USERNAME') == username and credentials.get('CONFIG_CLIENT_PASSWORD'),
           'Config client credentials must have matching declared Vault writes')
    demand(re.fullmatch(r'[a-z][a-z0-9-]{2,63}', username), 'Invalid Config client username')
    server = document('control-plane/prod/server')
    accounts = parse_json(server['data'].get('CONFIG_SERVER_ACCOUNTS_JSON', '[]'))
    demand(isinstance(accounts, list) and all(isinstance(account, dict) for account in accounts),
           'Invalid existing Config account list')
    application = 'monitoring' if target == 'monitoring' else f'cowork-{target}'
    accounts = [account for account in accounts if account.get('application') != application]
    accounts.append({'username': username,
                     'passwordHash': hashlib.sha256(credentials['CONFIG_CLIENT_PASSWORD'].encode()).hexdigest(),
                     'application': application, 'profile': runtime.get('APP_CONFIG_PROFILE', 'prod')})
    demand(len({account.get('username') for account in accounts}) == len(accounts), 'Config client usernames must be unique')
    accounts.sort(key=lambda account: (account.get('application', ''), account.get('profile', '')))
    server['data']['CONFIG_SERVER_ACCOUNTS_JSON'] = json.dumps(accounts, separators=(',', ':'))


def rollback(args, mount):
    demand(args.journal is not None and args.journal.exists() and not args.journal.is_symlink(), 'A settings journal is required')
    paths = sorted(args.journal.rglob('*.json')) if args.journal.is_dir() else [args.journal]
    journals = []
    grouped = {}
    targets, resources = set(), set()
    for path in paths:
        demand(path.is_file() and not path.is_symlink(), 'Rollback journals must be regular files')
        journal = parse_json(path.read_text())
        if 'writes' not in journal:
            continue
        demand(journal.get('schema_version') == 1 and journal.get('mount') == mount, 'Invalid settings rollback journal')
        journals.append((path, journal))
        targets.update(journal['affected_targets'])
        resources.update(journal['resource_targets'])
        for entry in journal['writes']:
            demand(isinstance(entry, dict) and entry.get('state') in {'pending', 'written', 'restored'},
                   'Invalid journal write state')
            vault_path = entry['path']
            demand(all(VAULT['NAME'].fullmatch(part) for part in vault_path.split('/')), 'Invalid journal document path')
            grouped.setdefault(vault_path, []).append(entry)
    demand(journals, 'No settings journals were found')
    restored_versions = {}
    for path, entries in grouped.items():
        entries.sort(key=lambda entry: entry['expected_version'])
        written = [entry for entry in entries if entry['state'] in {'written', 'restored'} and 'written_version' in entry]
        # Validate the complete known CAS chain before replacing its final version.
        for index, entry in enumerate(written):
            demand(type(entry.get('written_version')) is int
                   and entry['written_version'] == entry['expected_version'] + 1,
                   'Invalid written version in settings journal')
            if index:
                demand(entry['expected_version'] == written[index - 1]['written_version'],
                       'A settings document changed outside this run; rollback requires inspection')
        for entry in entries:
            if entry['state'] == 'pending':
                _, _, version = current(path, mount)
                known = written[-1]['written_version'] if written else entry['expected_version']
                demand(version == known and entry['expected_version'] == known,
                       'An interrupted write has an uncertain result; inspect its Vault version before rollback')
        if not written:
            for entry in entries:
                entry['state'] = 'restored'
            continue
        if all(entry['state'] == 'restored' for entry in written):
            restored = written[-1].get('restored_version', 0)
            if restored:
                restored_versions[path] = restored
            continue
        demand(all(entry['state'] == 'written' for entry in written), 'Incomplete settings rollback requires inspection')
        first, last = written[0], written[-1]
        actual = read_document(path, allow_missing=True, mount=mount)
        demand(actual is not None and actual['metadata']['version'] == last['written_version'],
               'A settings document changed after this run; rollback will not overwrite it')
        if first['before_version']:
            before = read_document(path, first['before_version'], mount=mount)['data']
            result = request(f'{mount}/data/{path}', {'options': {'cas': last['written_version']}, 'data': before})
            restored = result['data']['version']
            restored_versions[path] = restored
        else:
            # Delete only this run's last version; never delete newer values or metadata.
            request(f'{mount}/delete/{path}', {'versions': [last['written_version']]})
            restored = 0
        for entry in entries:
            entry.update(state='restored', restored_version=restored)
        for journal_path, journal in journals:
            save_journal(journal_path, journal)
    for path, journal in journals:
        journal['state'] = 'restored'
        save_journal(path, journal)
    emit('restored', True)
    emit('affected_targets', sorted(targets))
    emit('resource_targets', sorted(resources))
    emit('reference_versions', restored_versions)
    save_summary(args.output, {'targets': sorted(targets), 'resources': sorted(resources),
                              'reference_versions': restored_versions, 'restored': True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('validate', 'plan', 'sync', 'rollback'))
    parser.add_argument('--target', '--targets', default='all')
    parser.add_argument('--catalog', type=Path, default=ROOT / 'deploy/prod/configuration.json')
    parser.add_argument('--journal', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    mount = os.environ.get('VAULT_KV_MOUNT', 'secret')
    demand(VAULT['NAME'].fullmatch(mount), 'Invalid Vault KV mount')
    if args.action == 'rollback':
        rollback(args, mount)
        return
    catalog = load_catalog(args.catalog)
    if args.action == 'validate':
        print(f'Validated settings declarations for {len(catalog["targets"])} targets')
        return
    variables, secrets = inputs()
    if args.action == 'plan':
        plan(args, catalog, mount, variables, secrets)
    else:
        sync(args, catalog, mount, variables, secrets)


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        print(f'[github-settings] {error}', file=sys.stderr)
        sys.exit(1)
    except (KeyError, OSError, TypeError):
        print('[github-settings] Settings operation failed; inspect declared inputs and Vault availability', file=sys.stderr)
        sys.exit(1)
