#!/usr/bin/env python3
"""Reconcile native platform definitions, then wait for the requested Nomad versions."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

import requests

from vault_client import client

ROOT = Path(__file__).resolve().parents[2]
STAGES = ('foundation', 'data', 'apps', 'edge')


def command(argv, *, env=None, capture=False, allowed=(0,)):
    result = subprocess.run(argv, cwd=ROOT, env=env, text=True,
                            stdout=subprocess.PIPE if capture else None, check=False)
    if result.returncode not in allowed:
        raise RuntimeError(f'{Path(argv[0]).name} failed with exit status {result.returncode}')
    return result


class Platform:
    def __init__(self, state):
        self.state = state
        self.ca = state / 'pki/ca.crt'
        self.env = dict(os.environ, NOMAD_ADDR='https://10.0.0.111:4646',
                        NOMAD_CACERT=str(self.ca), CONSUL_HTTP_ADDR='https://10.0.0.111:8501',
                        CONSUL_CACERT=str(self.ca), TF_IN_AUTOMATION='1',
                        TF_VAR_state_passphrase=(state / 'tofu-state.passphrase').read_text().strip(),
                        ANSIBLE_CONFIG=str(ROOT / 'deploy/ansible/ansible.cfg'))
        self.refreshed = 0
        self.vault = None

    def credentials(self):
        if self.vault is not None and time.monotonic() - self.refreshed < 600:
            return
        self.vault = client('prod-iac')
        nomad = self.vault.read('nomad/creds/deployment')['data']['secret_id']
        consul = self.vault.read('consul/creds/deployment')['data']['token']
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            for token in (nomad, consul):
                print('::add-mask::' + token.replace('%', '%25'), flush=True)
        self.env.update(NOMAD_TOKEN=nomad, CONSUL_HTTP_TOKEN=consul, VAULT_TOKEN=self.vault.token)
        self.refreshed = time.monotonic()

    def api(self, path, *, method='GET', body=None, missing=False, consul=False):
        self.credentials()
        address = self.env['CONSUL_HTTP_ADDR' if consul else 'NOMAD_ADDR']
        header = 'X-Consul-Token' if consul else 'X-Nomad-Token'
        token = self.env['CONSUL_HTTP_TOKEN' if consul else 'NOMAD_TOKEN']
        response = requests.request(method, address + '/v1/' + path, json=body,
                                    headers={header: token}, verify=str(self.ca), timeout=30,
                                    allow_redirects=False)
        if missing and response.status_code == 404:
            return None
        if not 200 <= response.status_code < 300:
            raise RuntimeError(f'Platform API {path.split("?")[0]} failed (HTTP {response.status_code})')
        return response.json() if response.content else None

    def ansible(self, playbook, extra=None):
        argv = ['ansible-playbook', '-i', 'deploy/ansible/inventory/production.yml',
                'deploy/ansible/' + playbook]
        if extra:
            argv += ['--extra-vars', '@' + str(extra)]
        command(argv, env=self.env)

    def tofu(self, stage, variables, check=False):
        self.credentials()
        prefix = ['tofu', '-chdir=deploy/tofu/' + stage]
        command(prefix + ['init', '-input=false', '-lockfile=readonly',
                          '-backend-config=ca_file=' + str(self.ca)], env=self.env)
        args = (['plan', '-detailed-exitcode'] if check else ['apply', '-auto-approve'])
        result = command(prefix + args + ['-input=false', '-lock-timeout=120s',
                                         '-var-file=' + str(variables)], env=self.env,
                         allowed=(0, 2) if check else (0,))
        if check:
            print(f'{stage}: ' + ('drift detected' if result.returncode == 2 else 'matches Git'), flush=True)
            return {}
        output = json.loads(command(prefix + ['output', '-json'], env=self.env, capture=True).stdout)
        versions = output.get('job_versions', {}).get('value', {})
        indices = output.get('job_modify_indices', {}).get('value', {})
        if set(versions) != set(indices):
            raise ValueError(stage + ': job versions and modification indices must cover the same jobs')
        return {job_id: {'version': int(version), 'modify_index': int(indices[job_id])}
                for job_id, version in versions.items()}

    def wait_jobs(self, versions, specs, *, batch=False):
        remaining = dict(versions)
        deadline = time.monotonic() + 900
        last_report = 0
        while remaining:
            if time.monotonic() > deadline:
                raise RuntimeError('Nomad readiness deadline exceeded: ' + ', '.join(remaining))
            for job_id, expected in list(remaining.items()):
                version = expected['version']
                job = self.api('job/' + job_id)
                if job['Version'] != version or job['JobModifyIndex'] != expected['modify_index']:
                    raise RuntimeError(job_id + ': job version changed while waiting; deployment was not accepted')
                allocations = self.api('job/' + job_id + '/allocations') or []
                candidates = [a for a in allocations if a['JobVersion'] == int(version)
                              and a['DesiredStatus'] == 'run' and a['ClientStatus'] in ('running', 'complete')]
                groups = specs[job_id]['TaskGroups']
                ready = True
                ready_allocations = {}
                for group in groups:
                    matching = [a for a in candidates if a['TaskGroup'] == group['Name']]
                    good = []
                    for allocation in matching:
                        details = self.api('allocation/' + allocation['ID'])
                        states = details.get('TaskStates') or {}
                        actual_group = next(g for g in details['Job']['TaskGroups'] if g['Name'] == group['Name'])
                        required = [t for t in actual_group['Tasks']
                                    if (t.get('Lifecycle') or {}).get('Hook') != 'poststop'
                                    and not (batch and (t.get('Lifecycle') or {}).get('Sidecar'))]
                        ok = True
                        for task in required:
                            task_state = states.get(task['Name'], {})
                            lifecycle = task.get('Lifecycle') or {}
                            needs_exit = batch or (lifecycle.get('Hook') in ('prestart', 'poststart')
                                                   and not lifecycle.get('Sidecar'))
                            if needs_exit:
                                events = task_state.get('Events') or []
                                termination = next((e for e in reversed(events) if e['Type'] == 'Terminated'), {})
                                exit_code = (termination.get('Details') or {}).get('exit_code', termination.get('ExitCode', -1))
                                ok &= (task_state.get('State') == 'dead' and not task_state.get('Failed')
                                       and str(exit_code) == '0')
                            else:
                                ok &= task_state.get('State') == 'running' and not task_state.get('Failed')
                        if ok:
                            good.append(allocation)
                    ready_allocations[group['Name']] = {a['ID'] for a in good}
                    if len(good) < group.get('Count', 1):
                        ready = False
                if ready and not batch:
                    for group in groups:
                        passing = set(ready_allocations[group['Name']])
                        services = list(group.get('Services') or [])
                        services += [service for task in group['Tasks'] for service in task.get('Services') or []]
                        for service in services:
                            entries = self.api('health/service/' + service['Name'] + '?passing=true', consul=True)
                            passing &= {aid for aid in passing if any(
                                re.search(r'(?:^|-)' + re.escape(aid) + r'(?:-|$)', entry['Service']['ID'])
                                for entry in entries)}
                        if len(passing) < group.get('Count', 1):
                            ready = False
                if ready:
                    del remaining[job_id]
                    print(f'{job_id}: requested version {version} is ready', flush=True)
            if remaining:
                if time.monotonic() - last_report >= 30:
                    print('Waiting for Nomad: ' + ', '.join(remaining), flush=True)
                    last_report = time.monotonic()
                time.sleep(5)


def preflight_secrets(platform, manifest, mount):
    platform.credentials()
    references = [('apps/' + name, spec['secret_version'])
                  for name, spec in manifest['applications'].items()]
    references += [('data/' + name, version) for name, version in manifest['data_secret_versions'].items()]
    references += [('apps/' + name, version) for name, version in manifest['platform_secret_versions'].items()]
    for path, version in references:
        result = platform.vault.secrets.kv.v2.read_secret_version(
            path='nomad/' + path, version=version, mount_point=mount, raise_on_deleted_version=True)['data']
        if result['data'] is None or result['metadata']['destroyed'] or result['metadata']['deletion_time']:
            raise ValueError('A pinned Vault version is unavailable: ' + path)
    print('Every pinned secret version is available', flush=True)


def release_images(specs):
    images = {}
    for job in specs.values():
        placement = next((c['RTarget'] for c in job.get('Constraints') or []
                          if c['LTarget'] == '${meta.cowork_vm}'), None)
        if not placement:
            raise ValueError('Every job must declare a fixed VM placement')
        selected = images.setdefault(placement, set())
        for group in job['TaskGroups']:
            for task in group['Tasks']:
                selected.add(task['Config']['image'])
            for service in group.get('Services') or []:
                sidecar = (service.get('Connect') or {}).get('SidecarTask')
                if sidecar:
                    selected.add(sidecar['Config']['image'])
    if any(not re.fullmatch(r'.+@sha256:[0-9a-f]{64}', image) for values in images.values() for image in values):
        raise ValueError('Release contains an unpinned image')
    pause = json.loads((ROOT / 'deploy/production/versions.json').read_text())['pause']['image']
    if not re.fullmatch(r'.+@sha256:[0-9a-f]{64}', pause):
        raise ValueError('Pin the Nomad network namespace image by digest')
    return {host: sorted(values | {pause}) for host, values in images.items()}


def preflight_capacity(platform, specs):
    required = {}
    for job in specs.values():
        host = next(c['RTarget'] for c in job['Constraints'] if c['LTarget'] == '${meta.cowork_vm}')
        for group in job['TaskGroups']:
            # Reserve the startup peak, including ephemeral initialization and Envoy tasks.
            memory = sum(int(task['Resources']['MemoryMB']) for task in group['Tasks']
                         if (task.get('Lifecycle') or {}).get('Hook') != 'poststop')
            for service in group.get('Services') or []:
                sidecar = (service.get('Connect') or {}).get('SidecarTask')
                if sidecar:
                    memory += int(sidecar['Resources']['MemoryMB'])
            required[host] = required.get(host, 0) + memory * group.get('Count', 1)
    available = {}
    for node in platform.api('nodes'):
        if node['Status'] != 'ready' or node.get('SchedulingEligibility') != 'eligible':
            continue
        details = platform.api('node/' + node['ID'])
        host = (details.get('Meta') or {}).get('cowork_vm')
        if host not in required:
            continue
        if host in available:
            raise ValueError('Multiple Nomad clients claim fixed VM placement: ' + host)
        total = details['NodeResources']['Memory']['MemoryMB']
        reserved = (details.get('ReservedResources') or {}).get('Memory', {}).get('MemoryMB', 0)
        available[host] = int(total) - int(reserved)
    missing = set(required) - set(available)
    if missing:
        raise ValueError('Nomad clients unavailable: ' + ', '.join(sorted(missing)))
    insufficient = [f'{host}: need {memory} MiB, allocatable {available[host]} MiB'
                    for host, memory in required.items() if memory > available[host]]
    if insufficient:
        raise ValueError('Release exceeds VM memory reservations: ' + '; '.join(insufficient))
    print('Every fixed VM has capacity for the declared startup memory reservations', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    state = Path(os.environ['COWORK_PLATFORM_STATE_DIR']).resolve()
    if state.is_relative_to(ROOT) or not state.is_dir():
        raise ValueError('Use the private operator or runner platform state directory')
    manifest = json.loads(args.manifest.read_text())
    if manifest.get('schema_version') != 1 or not re.fullmatch(r'[0-9a-f]{40}', manifest['source_sha']):
        raise ValueError('A complete committed release manifest is required')
    if os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Production reconciliation is restricted to main')
    platform = Platform(state)
    mount = os.environ.get('VAULT_KV_MOUNT', 'secret')
    with tempfile.TemporaryDirectory(prefix='cowork-release-', dir=state) as temp:
        directory = Path(temp)
        rendered = directory / 'jobs.json'
        command(['python3', 'deploy/validate.py', '--manifest', str(args.manifest.resolve()),
                 '--render-json', str(rendered)])
        specs = json.loads(rendered.read_text())
        variables = directory / 'release.tfvars.json'
        variables.write_text(json.dumps({'manifest': manifest, 'platform_ca_file': str(platform.ca),
                                         'vault_mount': mount}))
        if args.check:
            for stage in STAGES:
                platform.tofu(stage, variables, check=True)
            return
        platform.ansible('site.yml')
        preflight_secrets(platform, manifest, mount)
        preflight_capacity(platform, specs)
        platform.tofu('foundation', variables)
        extra = directory / 'preflight.json'
        extra.write_text(json.dumps({'release_images': release_images(specs)}))
        platform.ansible('preflight.yml', extra)
        extra.write_text(json.dumps({'cowork_cutover': True, 'cowork_release_preflight_passed': True,
                                     'cowork_release_sha': manifest['source_sha']}))
        platform.ansible('cutover.yml', extra)
        platform.wait_jobs(platform.tofu('data', variables), specs)
        migration = specs['cowork-user-migrations']
        previous = platform.api('job/cowork-user-migrations', missing=True)
        migration.setdefault('Meta', {})['release_run'] = str(time.time_ns())
        registered = platform.api('jobs', method='POST', body={'Job': migration, 'EnforceIndex': True,
                                  'JobModifyIndex': previous['JobModifyIndex'] if previous else 0})
        version = previous['Version'] + 1 if previous else 0
        platform.wait_jobs({'cowork-user-migrations': {
            'version': version, 'modify_index': registered['JobModifyIndex'],
        }}, specs, batch=True)
        for stage in ('apps', 'edge'):
            platform.wait_jobs(platform.tofu(stage, variables), specs)
        print('The entire declared production release is ready', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit(f'Deployment failed ({type(error).__name__}); sensitive response bodies withheld') from None
