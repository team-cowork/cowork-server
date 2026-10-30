"""Business application rollout and persistent request barriers."""
import hmac
import json
import re
import subprocess
import sys
import time

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


class Services:
    def __init__(self, platform, gateway):
        self.platform = platform
        self.gateway = gateway
        self.authorization_secret_present = True

    def warn_webhook_secrets(self, manifest, mount):
        self.platform.credentials()
        secrets = {}
        for name in ('gateway', 'authorization'):
            version = manifest['applications'][name]['secret_version']
            result = self.platform.vault.secrets.kv.v2.read_secret_version(
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

    def redis(self, *args):
        allocation = self.platform.active_allocation('cowork-redis')
        return self.platform.alloc_exec(allocation['ID'], 'redis', ['redis-cli', '--raw', *args])

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
                    self.gateway.wait_state(service, confirmed)

    def redis_state_change(self, script, service, run_id, value=None):
        # Both commands run on one connection: WAITAOF must cover the preceding EVAL write.
        arguments = [STATE_KEY, service, run_id] + ([value] if value is not None else [])
        if any("'" in argument or '\n' in argument for argument in arguments):
            raise ValueError('Unsafe deployment state argument')
        line = 'EVAL "' + script + '" 1 ' + ' '.join("'" + argument + "'" for argument in arguments)
        allocation = self.platform.active_allocation('cowork-redis')
        output = self.platform.alloc_exec(allocation['ID'], 'redis', ['redis-cli', '--raw'],
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
            allocation = self.platform.active_allocation('cowork-authorization')
            metrics = self.platform.alloc_exec(allocation['ID'], 'app',
                                      ['wget', '-qO-', 'http://127.0.0.1:8081/metrics'])
            match = re.search(r'^cowork_authorization_webhook_stream_connected\s+([01])$',
                              metrics, re.MULTILINE)
            return int(match.group(1)) if match else None
        except (RuntimeError, subprocess.TimeoutExpired):
            return None

    def apply(self, variables, plan_file, changed, specs, run_id,
                            *, gateway_available, flag_services):
        previous = {job_id: self.platform.api('job/' + job_id) for job_id in changed} if gateway_available else {}
        flagged = set()
        started = False
        try:
            for job_id in sorted(flag_services):
                value = self.state_set(job_id, run_id, 'deploying')
                flagged.add(job_id)
                if gateway_available:
                    self.gateway.wait_state(job_id, value)
            if gateway_available and flagged:
                self.gateway.wait_drain(flagged)
            started = True
            indices = self.platform.tofu_apply_plan('apps', variables, plan_file)
            self.platform.wait_jobs(indices, specs)
            if gateway_available and flagged:
                self.gateway.wait_upstreams(flagged)
            if not gateway_available or 'cowork-authorization' in flagged:
                self.wait_authorization_consumer()
            for job_id in sorted(flagged):
                if gateway_available:
                    self.state_clear(job_id, run_id)
                    self.gateway.wait_state(job_id)
                else:
                    self.state_set(job_id, run_id, 'recovering')
        except Exception as error:
            if not flagged:
                raise
            try:
                for job_id in sorted(flagged):
                    value = self.state_set(job_id, run_id, 'recovering')
                    if gateway_available:
                        self.gateway.wait_state(job_id, value)
                if gateway_available:
                    self.gateway.wait_drain(flagged)
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
                    restored = {job_id: self.platform.register_previous_job(job_id, previous[job_id]) for job_id in sorted(flagged)}
                    self.platform.wait_jobs(restored, previous)
                except Exception as error:
                    raise RuntimeError('Business deployment and automatic rollback both failed (' +
                                       type(error).__name__ + '); recovery flags remain') from None
            else:
                self.platform.wait_jobs({job_id: previous[job_id]['JobModifyIndex'] for job_id in flagged}, previous)
            self.gateway.wait_upstreams(flagged)
            for job_id in sorted(flagged):
                if job_id == 'cowork-authorization':
                    self.wait_authorization_consumer()
                self.state_clear(job_id, run_id)
                self.gateway.wait_state(job_id)
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
        job = self.platform.api('job/' + service)
        self.platform.wait_jobs({service: job['JobModifyIndex']}, {service: job})
        self.gateway.wait_upstreams({service})
        if service == 'cowork-authorization':
            self.wait_authorization_consumer()
        self.state_clear(service, run_id)
        self.gateway.wait_state(service)
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
