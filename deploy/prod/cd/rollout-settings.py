#!/usr/bin/env python3
"""Build rollout matrices from settings metadata and actual deployment history."""
import argparse
import json
import os
from pathlib import Path
import runpy
from types import SimpleNamespace

from deployment_state import successful_deployment

ROOT = Path(__file__).resolve().parents[3]
SETTINGS = runpy.run_path(str(Path(__file__).with_name('github-settings.py')))


def emit(name, value):
    SETTINGS['emit'](name, value)


def save(path, value):
    SETTINGS['save_summary'](path, value)


def matrix(units):
    return {'include': units}


def check_attempt(args):
    expected = os.environ.get('SETTINGS_ATTEMPT', '')
    actual = os.environ.get('GITHUB_RUN_ATTEMPT', '')
    if not actual.isdecimal() or int(actual) < 1 or expected != actual:
        raise SystemExit('Rerun all jobs or start a new workflow to apply settings; '
                         'previous attempt metadata cannot be reused after recovery')


def initial(args):
    requested = json.loads(os.environ['DEPLOY_MATRIX'])['include']
    catalog = SETTINGS['load_catalog'](ROOT / 'deploy/prod/configuration.json')
    selected = {unit['target'] for unit in requested} & catalog['targets'].keys()
    if os.environ.get('INFRASTRUCTURE') == 'true':
        selected.update(catalog['targets'])
    check = os.environ.get('CHECK_ONLY') == 'true'
    expanded = set(selected)
    if selected and not check:
        variables, secrets = SETTINGS['inputs']()
        summary = args.directory / 'shared-plan.json'
        SETTINGS['plan'](SimpleNamespace(target=','.join(sorted(selected)), output=summary),
                         catalog, os.environ.get('VAULT_KV_MOUNT', 'secret'), variables, secrets)
        expanded = set(json.loads(summary.read_text())['targets'])
    units = {unit['target']: dict(unit) for unit in requested}
    for target in expanded:
        if target != 'monitoring':
            units.setdefault(target, {'service': target, 'target': target})
    # A client account change also requires recreating Config. Save its prior image
    # before any mutation, even when the later sync leaves that account unchanged.
    if expanded - {'config'}:
        units.setdefault('config', {'service': 'config', 'target': 'config'})
    source = os.environ['RELEASE_SHA']
    requested_targets = {unit['target'] for unit in requested}
    images = json.loads((ROOT / 'deploy/images/catalog.json').read_text())
    for target, unit in units.items():
        deployment = successful_deployment(unit['service'], target)
        prior = deployment['image_sha'] if deployment else ''
        requested_image = os.environ.get('IMAGE_SHA', '') if target in requested_targets else ''
        automatic = os.environ.get('AUTOMATIC') == 'true' and target in requested_targets
        unit.update(previous_image=prior,
                    previous_source=deployment['source_sha'] if deployment else '',
                    image_sha=requested_image or (source if automatic else prior or source))
        unit['build'] = unit['service'] in images and (automatic or not prior) and not requested_image
    attempt = os.environ['GITHUB_RUN_ATTEMPT']
    plan = {'requested': requested, 'targets': sorted(expanded), 'units': units,
            'source_sha': source, 'attempt': attempt}
    save(args.directory / 'rollout-plan.json', plan)
    emit('attempt', attempt)
    emit('sync_matrix', matrix([{'target': target} for target in sorted(expanded)] if not check else []))


def finalize(args):
    plan = json.loads((args.directory / 'rollout-plan.json').read_text())
    targets = {unit['target'] for unit in plan['requested']}
    resources, versions = set(), {}
    for path in args.directory.rglob('summary-*.json'):
        summary = json.loads(path.read_text())
        targets.update(summary['targets'])
        resources.update(summary['resources'])
        for key, version in summary['reference_versions'].items():
            versions[key] = max(versions.get(key, 0), version)
    units = [plan['units'][target] for target in sorted(targets) if target in plan['units']]
    images = json.loads((ROOT / 'deploy/images/catalog.json').read_text())
    image_matrix = matrix([
        {'service': unit['service'], 'image_sha': unit['image_sha'],
         'context': images[unit['service']]['prod'], 'dockerfile': f"cowork-{unit['service']}/Dockerfile.prod"}
        for unit in units if unit['build']
    ])
    emit('matrix', matrix(units))
    emit('image_matrix', image_matrix)
    emit('resources', ','.join(sorted(resources)))
    emit('reference_versions', versions)
    for wave in ('vault', 'config', 'applications'):
        emit(wave, matrix([unit for unit in units if
             (unit['service'] if unit['service'] in {'vault', 'config'} else 'applications') == wave]))


def recovery(args):
    plan = json.loads((args.directory / 'rollout-plan.json').read_text())
    journals = list(args.directory.rglob('journal-*.json'))
    targets = {unit['target'] for unit in plan['requested']}
    for path in journals:
        targets.update(json.loads(path.read_text())['affected_targets'])
    units = [dict(unit, image_sha=unit['previous_image'], source_sha=unit['previous_source'])
             for target, unit in plan['units'].items()
             if journals and target in targets and unit['previous_image'] and unit['service'] != 'vault']
    for wave in ('config', 'applications'):
        emit(wave, matrix([unit for unit in units if (unit['service'] == 'config') == (wave == 'config')]))


def recovery_scope(args):
    journals = [json.loads(path.read_text()) for path in args.directory.rglob('journal-*.json')]
    if not journals:
        print('No settings journal is available. Vault restoration was not performed; '
              'if publication started, inspect Vault before retrying.')
    emit('has_journals', bool(journals))
    emit('has_writes', any(journal.get('writes') for journal in journals))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('initial', 'finalize', 'recovery', 'recovery-scope', 'check-attempt'))
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    if args.action != 'check-attempt' and args.directory is None:
        parser.error('--directory is required for rollout metadata operations')
    globals()[args.action.replace('-', '_')](args)
