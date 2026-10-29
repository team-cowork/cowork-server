#!/usr/bin/env python3
"""Reconcile native platform definitions, then wait for the submitted Nomad jobs."""
import argparse
from contextlib import closing
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid

import requests

from vault_client import client

ROOT = Path(__file__).resolve().parents[2]
STAGES = ('foundation', 'data', 'apps', 'edge')
STATE_KEY = 'cowork:deployment:services'
SET_STATE = """local current=redis.call('HGET',KEYS[1],ARGV[1]);
if current then local ok,value=pcall(cjson.decode,current);
  if not ok or (value.runId~=ARGV[2] and value.phase~='recovering') then
    return redis.error_reply('ERR deployment state owned by another run') end end;
return redis.call('HSET',KEYS[1],ARGV[1],ARGV[3])""".replace('\n', ' ')
CLEAR_STATE = """local current=redis.call('HGET',KEYS[1],ARGV[1]);
if not current then return 0 end;
local ok,value=pcall(cjson.decode,current);
if not ok or value.runId~=ARGV[2] then return redis.error_reply('ERR deployment state owned by another run') end;
return redis.call('HDEL',KEYS[1],ARGV[1])""".replace('\n', ' ')


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
        self.authorization_secret_present = True

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

    def warn_webhook_secrets(self, manifest, mount):
        self.credentials()
        secrets = {}
        for name in ('gateway', 'authorization'):
            version = manifest['applications'][name]['secret_version']
            result = self.vault.secrets.kv.v2.read_secret_version(
                path='nomad/apps/' + name, mount_point=mount, version=version,
                raise_on_deleted_version=True)
            secrets[name] = result['data']['data']
        signature_keys = [secrets[name].get('DATAGSM_WEBHOOK_SECRET', '')
                          for name in ('gateway', 'authorization')]
        self.authorization_secret_present = isinstance(signature_keys[1], str) and bool(signature_keys[1])
        if not all(isinstance(key, str) and key for key in signature_keys):
            print('CRITICAL: Pinned Gateway or Authorization DataGSM webhook secret is missing; '
                  'webhook delivery may be unavailable', file=sys.stderr, flush=True)
        elif not hmac.compare_digest(*(key.encode('utf-8') for key in signature_keys)):
            print('CRITICAL: Pinned Gateway and Authorization DataGSM webhook secrets differ; '
                  'queued webhooks may be rejected', file=sys.stderr, flush=True)

    def active_allocation(self, job_id):
        allocations = self.api('job/' + job_id + '/allocations') or []
        active = [allocation for allocation in allocations if allocation['DesiredStatus'] == 'run'
                  and allocation['ClientStatus'] == 'running']
        if len(active) != 1:
            raise RuntimeError(job_id + ': expected exactly one active allocation')
        return active[0]

    def release_gateway_allocation(self):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            job = self.api('job/cowork-gateway', missing=True)
            if job is None:
                return None
            allocations = self.api('job/cowork-gateway/allocations') or []
            active = [allocation for allocation in allocations if allocation['DesiredStatus'] == 'run'
                      and allocation['ClientStatus'] == 'running']
            if len(active) == 1 and not job.get('Stop'):
                return active[0]
            if len(active) > 1:
                raise RuntimeError('Gateway has multiple running allocations; inspect the interrupted canary '
                                   'and NGINX target before retrying CD')
            pending = [allocation for allocation in allocations if allocation['DesiredStatus'] == 'run'
                       and allocation['ClientStatus'] in ('pending', 'running')]
            if job.get('Stop') and not pending:
                return None
            time.sleep(3)
        raise RuntimeError('Gateway job exists but has no healthy allocation; inspect Nomad scheduling '
                           'before changing business services')

    def alloc_exec(self, allocation_id, task, argv, *, input=None):
        self.credentials()
        return command(['nomad', 'alloc', 'exec', '-i=' + ('true' if input is not None else 'false'),
                        '-t=false', '-task=' + task, allocation_id, *argv], env=self.env,
                       capture=True, input=input, timeout=30).stdout.strip()

    def redis(self, *args):
        allocation = self.active_allocation('cowork-redis')
        return self.alloc_exec(allocation['ID'], 'redis', ['redis-cli', '--raw', *args])

    def deployment_states(self):
        rows = self.redis('HGETALL', STATE_KEY).splitlines()
        if len(rows) % 2:
            raise RuntimeError('Redis deployment state hash is malformed')
        return dict(zip(rows[::2], rows[1::2]))

    def normalize_stale_states(self, states, manifest, *, gateway_has_probe):
        declared = {'cowork-' + name for name in manifest['applications'] if name != 'gateway'}
        for service, raw in sorted(states.items()):
            if service not in declared:
                raise RuntimeError('Unexpected service in Redis deployment state: ' + service)
            try:
                value = json.loads(raw)
                run_id, phase = value['runId'], value['phase']
                if not isinstance(run_id, str) or not run_id or phase not in ('deploying', 'recovering'):
                    raise ValueError('invalid deployment state')
            except (ValueError, KeyError, TypeError):
                raise RuntimeError('Deployment state is malformed for ' + service) from None
            if phase == 'deploying':
                confirmed = self.state_set(service, run_id, 'recovering')
                if gateway_has_probe:
                    self.wait_gateway_state(service, confirmed)

    def redis_state_change(self, script, service, run_id, value=None):
        # Both commands run on one connection: WAITAOF must cover the preceding EVAL write.
        arguments = [STATE_KEY, service, run_id] + ([value] if value is not None else [])
        if any("'" in argument or '\n' in argument for argument in arguments):
            raise ValueError('Unsafe deployment state argument')
        line = 'EVAL "' + script + '" 1 ' + ' '.join("'" + argument + "'" for argument in arguments)
        allocation = self.active_allocation('cowork-redis')
        output = self.alloc_exec(allocation['ID'], 'redis', ['redis-cli', '--raw'],
                                 input=line + '\nWAITAOF 1 0 2000\n')
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        if lines and lines[0] == 'ERR deployment state owned by another run':
            raise RuntimeError('Redis deployment state is owned by another run')
        if not lines or lines[0] not in ('0', '1'):
            raise RuntimeError('Redis deployment state script failed')
        if lines[1:] != ['1', '0']:
            raise RuntimeError('Redis deployment state write was not confirmed on AOF')

    def state_set(self, service, run_id, phase):
        if phase not in ('deploying', 'recovering'):
            raise ValueError('Unknown deployment phase')
        value = json.dumps({'runId': run_id, 'phase': phase}, separators=(',', ':'))
        self.redis_state_change(SET_STATE, service, run_id, value)
        return value

    def state_clear(self, service, run_id):
        self.redis_state_change(CLEAR_STATE, service, run_id)

    def gateway_snapshot(self, allocation=None):
        allocation = allocation or self.active_allocation('cowork-gateway')
        output = self.alloc_exec(allocation['ID'], 'app',
                                 ['wget', '-qO-', 'http://127.0.0.1:8080/internal/deployment-state'])
        snapshot = json.loads(output)
        if snapshot.get('initialized') is not True or not isinstance(snapshot.get('services'), dict):
            raise RuntimeError('Gateway has not synchronized deployment state')
        return snapshot

    def wait_gateway_state(self, service, value=None, allocation=None):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                actual = self.gateway_snapshot(allocation)['services'].get(service)
                if actual == (json.loads(value) if value is not None else None):
                    return
            except (RuntimeError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired):
                pass
            time.sleep(2)
        raise RuntimeError('Gateway did not confirm deployment state for ' + service)

    def wait_gateway_drain(self, services):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            snapshot = self.gateway_snapshot()
            inflight = snapshot.get('inflight')
            if isinstance(inflight, dict) and all(inflight.get(service, 0) == 0 for service in services):
                return
            time.sleep(2)
        raise RuntimeError('Gateway still has in-flight HTTP requests for planned services')

    def wait_gateway_upstreams(self, services, allocation=None):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                selected = allocation or self.active_allocation('cowork-gateway')
                output = self.alloc_exec(selected['ID'], 'app',
                                         ['wget', '-qO-', 'http://127.0.0.1:8080/api/health'])
                statuses = json.loads(output)['data']
                if isinstance(statuses, dict) and all(statuses.get(service) == 'UP' for service in services):
                    return
            except (RuntimeError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
                pass
            time.sleep(5)
        raise RuntimeError('Gateway cannot reach every changed service upstream: ' + ', '.join(sorted(services)))

    def wait_authorization_consumer(self):
        if not self.authorization_secret_present:
            return
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if self.authorization_stream_connected() == 1:
                return
            time.sleep(5)
        raise RuntimeError('Authorization webhook stream consumer did not connect to Redis')

    def authorization_stream_connected(self):
        try:
            allocation = self.active_allocation('cowork-authorization')
            metrics = self.alloc_exec(allocation['ID'], 'app',
                                      ['wget', '-qO-', 'http://127.0.0.1:8081/metrics'])
            match = re.search(r'^cowork_authorization_webhook_stream_connected\s+([01])$',
                              metrics, re.MULTILINE)
            return int(match.group(1)) if match else None
        except (RuntimeError, subprocess.TimeoutExpired):
            return None

    def gateway_host_port(self, allocation):
        details = self.api('allocation/' + allocation['ID'])
        shared = (details.get('AllocatedResources') or {}).get('Shared') or {}
        for port in shared.get('Ports') or []:
            if port.get('Label') == 'http':
                return int(port['Value'])
        for network in (details.get('Resources') or {}).get('Networks') or []:
            for port in (network.get('DynamicPorts') or []) + (network.get('ReservedPorts') or []):
                if port.get('Label') == 'http':
                    return int(port['Value'])
        raise RuntimeError('Gateway allocation does not expose its host HTTP port')

    def gateway_candidate(self, previous_id, expected_index, expected_port):
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            try:
                job = self.api('job/cowork-gateway')
                if job['JobModifyIndex'] != expected_index:
                    raise RuntimeError('Gateway job changed during candidate readiness')
                allocations = self.api('job/cowork-gateway/allocations') or []
                candidates = [allocation for allocation in allocations
                              if allocation['ID'] != previous_id and allocation['JobVersion'] == job['Version']
                              and allocation['DesiredStatus'] == 'run' and allocation['ClientStatus'] == 'running']
                entries = self.api('health/service/cowork-gateway?passing=true', consul=True) or []
                for allocation in candidates:
                    details = self.api('allocation/' + allocation['ID'])
                    states = details.get('TaskStates') or {}
                    tasks = details['Job']['TaskGroups'][0]['Tasks']
                    if not all(states.get(task['Name'], {}).get('State') == 'running' for task in tasks):
                        continue
                    if not any(re.search(r'(?:^|-)' + re.escape(allocation['ID']) + r'(?:-|$)',
                                         entry['Service']['ID']) for entry in entries):
                        continue
                    try:
                        self.gateway_snapshot(allocation)
                        port = self.gateway_host_port(allocation)
                        if port == expected_port:
                            return allocation, port
                    except (RuntimeError, ValueError, subprocess.TimeoutExpired):
                        pass
            except TransientPlatformError:
                pass
            time.sleep(5)
        raise RuntimeError('New Gateway candidate did not become ready')

    def gateway_switch(self, port, directory):
        extra = directory / 'gateway-switch.json'
        extra.write_text(json.dumps({'gateway_target_port': port}))
        self.ansible('gateway-switch.yml', extra)

    def gateway_verify(self, port, directory):
        extra = directory / 'gateway-verify-vars.json'
        extra.write_text(json.dumps({'gateway_target_port': port}))
        self.ansible('gateway-verify.yml', extra)

    def wait_gateway_promotable(self, expected_index):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            job = self.api('job/cowork-gateway')
            if job['JobModifyIndex'] != expected_index:
                raise RuntimeError('Gateway job changed while waiting for a healthy canary')
            deployment = self.api('job/cowork-gateway/deployment')
            if deployment and deployment['Status'] == 'running':
                group = deployment['TaskGroups']['app']
                if group['DesiredCanaries'] == 1 and group['HealthyAllocs'] >= 1 and not group['Promoted']:
                    return
            time.sleep(5)
        raise RuntimeError('Gateway canary did not become promotable')

    def gateway_promote(self):
        deployment = self.api('job/cowork-gateway/deployment')
        if not deployment or deployment['Status'] != 'running':
            raise RuntimeError('Gateway has no promotable deployment')
        group = deployment['TaskGroups']['app']
        if group['DesiredCanaries'] != 1 or group['HealthyAllocs'] < 1 or group['Promoted']:
            raise RuntimeError('Gateway canary is not healthy and awaiting promotion')
        self.api('deployment/promote/' + deployment['ID'], method='POST',
                 body={'DeploymentID': deployment['ID'], 'All': True})

    def wait_gateway_promotion(self, previous_id, index, specs):
        self.wait_jobs({'cowork-gateway': index}, specs)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            deployment = self.api('job/cowork-gateway/deployment')
            allocations = self.api('job/cowork-gateway/allocations') or []
            old = next((allocation for allocation in allocations if allocation['ID'] == previous_id), None)
            if deployment and deployment['Status'] == 'successful' and (not old or old['DesiredStatus'] == 'stop'):
                return
            time.sleep(5)
        raise RuntimeError('Gateway promotion did not retire the previous allocation')

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

    def rollback_gateway(self, previous_job, previous_allocation, directory):
        previous_port = self.gateway_host_port(previous_allocation)
        old = self.api('allocation/' + previous_allocation['ID'], missing=True)
        if old and old['DesiredStatus'] == 'run' and old['ClientStatus'] == 'running':
            self.gateway_snapshot(old)
            # Keep the old allocation alive until the proxy has drained its new workers.
            self.gateway_switch(previous_port, directory)
            index = self.register_previous_job('cowork-gateway', previous_job)
            self.wait_jobs({'cowork-gateway': index}, {'cowork-gateway': previous_job})
            return

        # Promotion may already have stopped the old allocation. Recreate it as a
        # canary while the new allocation continues serving, then move the proxy.
        current = self.active_allocation('cowork-gateway')
        index = self.register_previous_job('cowork-gateway', previous_job, canary=1)
        candidate, port = self.gateway_candidate(current['ID'], index, previous_port)
        self.wait_gateway_promotable(index)
        self.gateway_switch(port, directory)
        self.gateway_promote()
        self.wait_gateway_promotion(current['ID'], index, {'cowork-gateway': previous_job})
        self.gateway_snapshot(candidate)

    def apply_gateway_cold(self, variables, plan_file, specs, directory):
        target_port = json.loads(variables.read_text())['gateway_host_port']
        if target_port != 21000:
            raise RuntimeError('Gateway cold start must use host port 21000')
        indices = self.tofu_apply_plan('edge', variables, plan_file)
        job = self.api('job/cowork-gateway')
        if job.get('Stop'):
            raise RuntimeError('Gateway job remains stopped after edge apply; it cannot serve traffic')
        candidate, port = self.gateway_candidate(None, indices['cowork-gateway'], target_port)
        self.wait_jobs({'cowork-gateway': indices['cowork-gateway']}, specs)
        self.gateway_switch(port, directory)
        self.gateway_verify(port, directory)
        self.gateway_snapshot(candidate)
        print('Gateway traffic switched to verified allocation ' + candidate['ID'], flush=True)

    def apply_gateway(self, variables, plan_file, specs, directory):
        previous_job = self.api('job/cowork-gateway')
        previous_allocation = self.active_allocation('cowork-gateway')
        previous_port = self.gateway_host_port(previous_allocation)
        target_port = json.loads(variables.read_text())['gateway_host_port']
        if target_port not in (21000, 21001) or target_port == previous_port:
            raise RuntimeError('Gateway planned host port does not alternate from the active allocation')
        try:
            indices = self.tofu_apply_plan('edge', variables, plan_file)
            candidate, port = self.gateway_candidate(previous_allocation['ID'], indices['cowork-gateway'], target_port)
            self.wait_gateway_promotable(indices['cowork-gateway'])
            self.gateway_switch(port, directory)
            self.gateway_promote()
            self.wait_gateway_promotion(previous_allocation['ID'], indices['cowork-gateway'], specs)
            self.gateway_verify(port, directory)
            self.gateway_snapshot(candidate)
            print('Gateway traffic switched to verified allocation ' + candidate['ID'], flush=True)
        except Exception:
            try:
                current_job = self.api('job/cowork-gateway', missing=True)
            except Exception:
                current_job = None
            if current_job is None or current_job['JobModifyIndex'] != previous_job['JobModifyIndex']:
                try:
                    self.rollback_gateway(previous_job, previous_allocation, directory)
                except Exception as rollback_error:
                    raise RuntimeError('Gateway switch and automatic rollback both failed (' +
                                       type(rollback_error).__name__ + ')') from None
            raise

    def apply_business_apps(self, variables, plan_file, changed, specs, run_id,
                            *, gateway_available, flag_services):
        previous = {job_id: self.api('job/' + job_id) for job_id in changed} if gateway_available else {}
        flagged = set()
        started = False
        try:
            for job_id in sorted(flag_services):
                value = self.state_set(job_id, run_id, 'deploying')
                flagged.add(job_id)
                if gateway_available:
                    self.wait_gateway_state(job_id, value)
            if gateway_available and flagged:
                self.wait_gateway_drain(flagged)
            started = True
            indices = self.tofu_apply_plan('apps', variables, plan_file)
            self.wait_jobs(indices, specs)
            if gateway_available and flagged:
                self.wait_gateway_upstreams(flagged)
            if not gateway_available or 'cowork-authorization' in flagged:
                self.wait_authorization_consumer()
            for job_id in sorted(flagged):
                if gateway_available:
                    self.state_clear(job_id, run_id)
                    self.wait_gateway_state(job_id)
                else:
                    self.state_set(job_id, run_id, 'recovering')
        except Exception as error:
            if not flagged:
                raise
            try:
                for job_id in sorted(flagged):
                    value = self.state_set(job_id, run_id, 'recovering')
                    if gateway_available:
                        self.wait_gateway_state(job_id, value)
                if gateway_available:
                    self.wait_gateway_drain(flagged)
            except Exception as barrier_error:
                if not gateway_available:
                    raise RuntimeError('Business deployment failed while preserving recovery flags; '
                                       'current allocations were not replaced') from barrier_error
                raise RuntimeError('Business rollback blocked: Gateway recovery state or request drain '
                                   'was not confirmed; current allocations were not replaced') from barrier_error
            if not gateway_available:
                raise RuntimeError('Gateway is unavailable; business jobs and recovery flags remain '
                                   'for the next CD run') from error
            if started:
                try:
                    restored = {job_id: self.register_previous_job(job_id, previous[job_id]) for job_id in sorted(flagged)}
                    self.wait_jobs(restored, previous)
                except Exception as error:
                    raise RuntimeError('Business deployment and automatic rollback both failed (' +
                                       type(error).__name__ + '); recovery flags remain') from None
            else:
                self.wait_jobs({job_id: previous[job_id]['JobModifyIndex'] for job_id in flagged}, previous)
            self.wait_gateway_upstreams(flagged)
            for job_id in sorted(flagged):
                if job_id == 'cowork-authorization':
                    self.wait_authorization_consumer()
                self.state_clear(job_id, run_id)
                self.wait_gateway_state(job_id)
            raise

    def recover_state(self, service):
        raw = self.redis('HGET', STATE_KEY, service)
        if not raw:
            print(service + ': no deployment state remains', flush=True)
            return
        try:
            state = json.loads(raw)
            run_id = state['runId']
            if not isinstance(run_id, str) or not run_id:
                raise ValueError('invalid run ID')
        except (ValueError, KeyError, TypeError):
            raise RuntimeError('Deployment state is malformed; refusing automatic recovery') from None
        job = self.api('job/' + service)
        self.wait_jobs({service: job['JobModifyIndex']}, {service: job})
        self.wait_gateway_upstreams({service})
        if service == 'cowork-authorization':
            self.wait_authorization_consumer()
        self.state_clear(service, run_id)
        self.wait_gateway_state(service)
        print(service + ': healthy service confirmed and stale state cleared', flush=True)

    def recover_existing_states(self, manifest):
        declared = {'cowork-' + name for name in manifest['applications'] if name != 'gateway'}
        for service, raw in sorted(self.deployment_states().items()):
            if service not in declared:
                raise RuntimeError('Unexpected service in Redis deployment state: ' + service)
            try:
                phase = json.loads(raw)['phase']
            except (ValueError, KeyError, TypeError):
                raise RuntimeError('Deployment state is malformed for ' + service) from None
            if phase != 'recovering':
                raise RuntimeError('Active deployment state requires operator review: ' + service)
            self.recover_state(service)

    def wait_jobs(self, indices, specs, *, batch=False):
        remaining = dict(indices)
        deadline = time.monotonic() + 900
        last_report = 0
        while remaining:
            if time.monotonic() > deadline:
                raise RuntimeError('Nomad readiness deadline exceeded: ' + ', '.join(remaining))
            for job_id, expected_index in list(remaining.items()):
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
            if remaining:
                if time.monotonic() - last_report >= 30:
                    print('Waiting for Nomad: ' + ', '.join(remaining), flush=True)
                    last_report = time.monotonic()
                time.sleep(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--recover-state', metavar='COWORK-SERVICE')
    args = parser.parse_args()
    if args.check and args.recover_state:
        parser.error('--check and --recover-state are mutually exclusive')
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
    with closing(platform), tempfile.TemporaryDirectory(prefix='cowork-release-', dir=state) as temp:
        directory = Path(temp)
        rendered = directory / 'jobs.json'
        command(['python3', 'deploy/validate.py', '--manifest', str(args.manifest.resolve()),
                 '--render-json', str(rendered), '--render-only'])
        specs = json.loads(rendered.read_text())
        business_services = {'cowork-' + name for name in manifest['applications'] if name != 'gateway'}
        if args.recover_state:
            if args.recover_state not in business_services:
                raise ValueError('Recover only a declared business application service')
            if args.recover_state == 'cowork-authorization':
                platform.warn_webhook_secrets(manifest, mount)
            platform.recover_state(args.recover_state)
            return
        variables = directory / 'release.tfvars.json'
        base_variables = {'manifest': manifest, 'platform_ca_file': str(platform.ca), 'vault_mount': mount}
        variables.write_text(json.dumps(base_variables))
        previous_gateway = platform.release_gateway_allocation()
        previous_port = platform.gateway_host_port(previous_gateway) if previous_gateway else None
        if previous_port is not None and previous_port not in (21000, 21001):
            raise RuntimeError('Gateway uses an unexpected host ingress port')
        current_target_port = previous_port or 21000
        edge_variables = directory / 'edge.tfvars.json'
        edge_variables.write_text(json.dumps({**base_variables, 'gateway_host_port': current_target_port}))
        if args.check:
            for stage in STAGES:
                platform.tofu(stage, edge_variables if stage == 'edge' else variables, check=True)
            return
        platform.warn_webhook_secrets(manifest, mount)
        if previous_gateway:
            platform.gateway_snapshot(previous_gateway)
        prior_states = platform.deployment_states()
        platform.normalize_stale_states(prior_states, manifest, gateway_has_probe=bool(previous_gateway))
        platform.ansible('site.yml')
        if previous_gateway:
            try:
                platform.gateway_verify(previous_port, directory)
            except RuntimeError:
                job = platform.api('job/cowork-gateway')
                platform.wait_jobs({'cowork-gateway': job['JobModifyIndex']}, {'cowork-gateway': job})
                platform.gateway_snapshot(previous_gateway)
                platform.gateway_switch(previous_port, directory)
                platform.gateway_verify(previous_port, directory)
        else:
            platform.ansible('gateway-offline.yml')
        platform.tofu('foundation', variables)
        platform.wait_jobs(platform.tofu('data', variables), specs)
        migration = specs['cowork-user-migrations']
        previous = platform.api('job/cowork-user-migrations', missing=True)
        migration['Meta'] = migration.get('Meta') or {}
        migration['Meta']['release_run'] = str(time.time_ns())
        registered = platform.api('jobs', method='POST', body={'Job': migration, 'EnforceIndex': True,
                                  'JobModifyIndex': previous['JobModifyIndex'] if previous else 0})
        platform.wait_jobs({'cowork-user-migrations': registered['JobModifyIndex']}, specs, batch=True)
        apps_plan = directory / 'apps.tfplan'
        edge_plan = directory / 'edge.tfplan'
        changed_apps = platform.tofu_plan_jobs('apps', variables, apps_plan)
        changed_edge = platform.tofu_plan_jobs('edge', edge_variables, edge_plan)
        if changed_edge - {'cowork-gateway'}:
            raise RuntimeError('Unexpected changed edge jobs')
        if previous_gateway and changed_edge:
            current_target_port = 21001 if previous_port == 21000 else 21000
            edge_variables = directory / 'edge-alternate.tfvars.json'
            edge_variables.write_text(json.dumps({**base_variables, 'gateway_host_port': current_target_port}))
            edge_plan = directory / 'edge-alternate.tfplan'
            changed_edge = platform.tofu_plan_jobs('edge', edge_variables, edge_plan)
            if changed_edge != {'cowork-gateway'}:
                raise RuntimeError('Gateway alternate-port plan did not change exactly one job')
        run_id = (os.environ.get('GITHUB_RUN_ID') or manifest['source_sha']) + ':' + uuid.uuid4().hex
        platform.apply_business_apps(variables, apps_plan, changed_apps, specs, run_id,
                                     gateway_available=bool(previous_gateway),
                                     flag_services=changed_apps if previous_gateway else business_services)
        if previous_gateway is None:
            platform.apply_gateway_cold(edge_variables, edge_plan, specs, directory)
        elif changed_edge:
            platform.apply_gateway(edge_variables, edge_plan, specs, directory)
        else:
            platform.tofu_apply_plan('edge', edge_variables, edge_plan)
        platform.recover_existing_states(manifest)
        print('The entire declared production release is ready', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit(f'Deployment failed ({type(error).__name__}); sensitive response bodies withheld') from None
