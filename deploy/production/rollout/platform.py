"""Authenticated platform IO and readiness for the submitted Nomad jobs."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

import requests

from deploy.production.secrets.vault_client import client

ROOT = Path(__file__).resolve().parents[3]


class TransientPlatformError(RuntimeError):
    pass


def command(argv, *, env=None, capture=False, allowed=(0,), timeout=None, input=None):
    result = subprocess.run(argv, cwd=ROOT, env=env, input=input, text=True,
                            stdout=subprocess.PIPE if capture else None, check=False, timeout=timeout)
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
        self.leases = []
        self.owns_vault_token = os.environ.get('GITHUB_ACTIONS') == 'true'

    def close(self):
        if self.vault is None:
            return
        errors = []
        if self.owns_vault_token:
            try:
                self.vault.auth.token.revoke_self()
            except Exception as error:
                errors.append(type(error).__name__)
        else:
            # An operator's supplied token is not owned by this deployment.
            for lease in self.leases:
                try:
                    self.vault.sys.revoke_lease(lease)
                except Exception as error:
                    errors.append(type(error).__name__)
        self.vault = None
        self.leases.clear()
        for name in ('NOMAD_TOKEN', 'CONSUL_HTTP_TOKEN', 'VAULT_TOKEN'):
            self.env.pop(name, None)
        if errors:
            # Expired tokens cannot revoke themselves; cleanup must not prevent
            # fresh authentication or replace the original deployment failure.
            print('Credential revocation could not be confirmed (' + ', '.join(errors) +
                  '); the issued credentials retain their twenty-minute expiry', file=sys.stderr, flush=True)

    def credentials(self):
        if self.vault is not None and time.monotonic() - self.refreshed < 600:
            return
        self.close()
        self.vault = client('prod-iac')
        nomad_credentials = self.vault.read('nomad/creds/deployment')
        self.leases.append(nomad_credentials['lease_id'])
        nomad = nomad_credentials['data']['secret_id']
        consul_credentials = self.vault.read('consul/creds/deployment')
        self.leases.append(consul_credentials['lease_id'])
        consul = consul_credentials['data']['token']
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
        try:
            response = requests.request(method, address + '/v1/' + path, json=body,
                                        headers={header: token}, verify=str(self.ca), timeout=30,
                                        allow_redirects=False)
        except requests.exceptions.SSLError:
            raise
        except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as error:
            raise TransientPlatformError(f'Platform API {path.split("?")[0]} is unavailable') from error
        if missing and response.status_code == 404:
            return None
        if response.status_code >= 500:
            raise TransientPlatformError(f'Platform API {path.split("?")[0]} failed (HTTP {response.status_code})')
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
        # Nomad provider 2.6.1 keeps planned indices when only hcl2.vars change.
        command(prefix + ['apply', '-refresh-only', '-auto-approve', '-input=false',
                          '-lock-timeout=120s', '-var-file=' + str(variables)], env=self.env)
        output = json.loads(command(prefix + ['output', '-json'], env=self.env, capture=True).stdout)
        indices = output['job_modify_indices']['value']
        return {job_id: int(index) for job_id, index in indices.items()}

    def tofu_plan_jobs(self, stage, variables, plan_file):
        self.credentials()
        prefix = ['tofu', '-chdir=deploy/tofu/' + stage]
        command(prefix + ['init', '-input=false', '-lockfile=readonly',
                          '-backend-config=ca_file=' + str(self.ca)], env=self.env, capture=True)
        command(prefix + ['plan', '-input=false', '-lock-timeout=120s', '-out=' + str(plan_file),
                          '-var-file=' + str(variables)], env=self.env, capture=True)
        plan = json.loads(command(prefix + ['show', '-json', str(plan_file)], env=self.env, capture=True).stdout)
        changed = set()
        for resource in plan.get('resource_changes', []):
            if resource.get('type') != 'nomad_job' or resource.get('name') != 'this':
                continue
            actions = resource.get('change', {}).get('actions', [])
            if actions and actions != ['no-op']:
                job_id = resource.get('index')
                if not isinstance(job_id, str) or not re.fullmatch(r'cowork-[a-z0-9-]+', job_id):
                    raise RuntimeError('Cannot identify a changed Nomad job in the saved plan')
                changed.add(job_id)
        return changed

    def tofu_apply_plan(self, stage, variables, plan_file):
        self.credentials()
        prefix = ['tofu', '-chdir=deploy/tofu/' + stage]
        command(prefix + ['apply', '-input=false', '-lock-timeout=120s', str(plan_file)], env=self.env)
        command(prefix + ['apply', '-refresh-only', '-auto-approve', '-input=false',
                          '-lock-timeout=120s', '-var-file=' + str(variables)], env=self.env)
        output = json.loads(command(prefix + ['output', '-json'], env=self.env, capture=True).stdout)
        return {job_id: int(index) for job_id, index in output['job_modify_indices']['value'].items()}

    def active_allocation(self, job_id):
        allocations = self.api('job/' + job_id + '/allocations') or []
        active = [allocation for allocation in allocations if allocation['DesiredStatus'] == 'run'
                  and allocation['ClientStatus'] == 'running']
        if len(active) != 1:
            raise RuntimeError(job_id + ': expected exactly one active allocation')
        return active[0]

    def alloc_exec(self, allocation_id, task, argv, *, input=None):
        self.credentials()
        return command(['nomad', 'alloc', 'exec', '-i=' + ('true' if input is not None else 'false'),
                        '-t=false', '-task=' + task, allocation_id, *argv], env=self.env,
                       capture=True, input=input, timeout=30).stdout.strip()

    def register_previous_job(self, job_id, previous, *, canary=0):
        current = self.api('job/' + job_id)
        restored = json.loads(json.dumps(previous))
        for group in restored.get('TaskGroups') or []:
            update = group.get('Update') or {}
            update['Canary'] = canary
            update['AutoPromote'] = False
            group['Update'] = update
        result = self.api('jobs', method='POST', body={'Job': restored, 'EnforceIndex': True,
                          'JobModifyIndex': current['JobModifyIndex']})
        return result['JobModifyIndex']

    def wait_jobs(self, indices, specs, *, batch=False):
        remaining = dict(indices)
        deadline = time.monotonic() + 900
        last_report = 0
        while remaining:
            if time.monotonic() > deadline:
                raise RuntimeError('Nomad readiness deadline exceeded: ' + ', '.join(remaining))
            for job_id, expected_index in list(remaining.items()):
                try:
                    job = self.api('job/' + job_id)
                    if job['JobModifyIndex'] != expected_index:
                        raise RuntimeError(job_id + ': job changed while waiting; deployment was not accepted')
                    version = job['Version']
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
                        print(f'{job_id}: submitted job index {expected_index} is ready', flush=True)
                except TransientPlatformError:
                    continue
            if remaining:
                if time.monotonic() - last_report >= 30:
                    print('Waiting for Nomad: ' + ', '.join(remaining), flush=True)
                    last_report = time.monotonic()
                time.sleep(5)
