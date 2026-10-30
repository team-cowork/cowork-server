"""Gateway candidate readiness, ingress switching and rollback."""
import json
import re
import subprocess
import time

from .platform import TransientPlatformError


class Gateway:
    def __init__(self, platform):
        self.platform = platform

    def active_or_absent(self):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                job = self.platform.api('job/cowork-gateway', missing=True)
                if job is None:
                    return None
                allocations = self.platform.api('job/cowork-gateway/allocations') or []
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
            except TransientPlatformError:
                pass
            time.sleep(3)
        raise RuntimeError('Gateway job exists but has no healthy allocation; inspect Nomad scheduling '
                           'before changing business services')

    def snapshot(self, allocation=None):
        allocation = allocation or self.platform.active_allocation('cowork-gateway')
        output = self.platform.alloc_exec(allocation['ID'], 'app',
                                 ['wget', '-qO-', 'http://127.0.0.1:8080/internal/deployment-state'])
        snapshot = json.loads(output)
        if snapshot.get('initialized') is not True or not isinstance(snapshot.get('services'), dict):
            raise RuntimeError('Gateway has not synchronized deployment state')
        return snapshot

    def wait_state(self, service, value=None, allocation=None):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                actual = self.snapshot(allocation)['services'].get(service)
                if actual == (json.loads(value) if value is not None else None):
                    return
            except (RuntimeError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired):
                pass
            time.sleep(2)
        raise RuntimeError('Gateway did not confirm deployment state for ' + service)

    def wait_drain(self, services):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                snapshot = self.snapshot()
                inflight = snapshot.get('inflight')
                if isinstance(inflight, dict) and all(inflight.get(service, 0) == 0 for service in services):
                    return
            except TransientPlatformError:
                pass
            time.sleep(2)
        raise RuntimeError('Gateway still has in-flight HTTP requests for planned services')

    def wait_upstreams(self, services, allocation=None):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                selected = allocation or self.platform.active_allocation('cowork-gateway')
                output = self.platform.alloc_exec(selected['ID'], 'app',
                                         ['wget', '-qO-', 'http://127.0.0.1:8080/api/health'])
                statuses = json.loads(output)['data']
                if isinstance(statuses, dict) and all(statuses.get(service) == 'UP' for service in services):
                    return
            except (RuntimeError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
                pass
            time.sleep(5)
        raise RuntimeError('Gateway cannot reach every changed service upstream: ' + ', '.join(sorted(services)))

    def host_port(self, allocation):
        details = self.platform.api('allocation/' + allocation['ID'])
        shared = (details.get('AllocatedResources') or {}).get('Shared') or {}
        for port in shared.get('Ports') or []:
            if port.get('Label') == 'http':
                return int(port['Value'])
        for network in (details.get('Resources') or {}).get('Networks') or []:
            for port in (network.get('DynamicPorts') or []) + (network.get('ReservedPorts') or []):
                if port.get('Label') == 'http':
                    return int(port['Value'])
        raise RuntimeError('Gateway allocation does not expose its host HTTP port')

    def candidate(self, previous_id, expected_index, expected_port):
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            try:
                job = self.platform.api('job/cowork-gateway')
                if job['JobModifyIndex'] != expected_index:
                    raise RuntimeError('Gateway job changed during candidate readiness')
                allocations = self.platform.api('job/cowork-gateway/allocations') or []
                candidates = [allocation for allocation in allocations
                              if allocation['ID'] != previous_id and allocation['JobVersion'] == job['Version']
                              and allocation['DesiredStatus'] == 'run' and allocation['ClientStatus'] == 'running']
                entries = self.platform.api('health/service/cowork-gateway?passing=true', consul=True) or []
                for allocation in candidates:
                    details = self.platform.api('allocation/' + allocation['ID'])
                    states = details.get('TaskStates') or {}
                    tasks = details['Job']['TaskGroups'][0]['Tasks']
                    if not all(states.get(task['Name'], {}).get('State') == 'running' for task in tasks):
                        continue
                    if not any(re.search(r'(?:^|-)' + re.escape(allocation['ID']) + r'(?:-|$)',
                                         entry['Service']['ID']) for entry in entries):
                        continue
                    try:
                        self.snapshot(allocation)
                        port = self.host_port(allocation)
                        if port == expected_port:
                            return allocation, port
                    except (RuntimeError, ValueError, subprocess.TimeoutExpired):
                        pass
            except TransientPlatformError:
                pass
            time.sleep(5)
        raise RuntimeError('New Gateway candidate did not become ready')

    def switch(self, port, directory):
        extra = directory / 'gateway-switch.json'
        extra.write_text(json.dumps({'gateway_target_port': port}))
        self.platform.ansible('gateway-switch.yml', extra)

    def verify(self, port, directory):
        extra = directory / 'gateway-verify-vars.json'
        extra.write_text(json.dumps({'gateway_target_port': port}))
        self.platform.ansible('gateway-verify.yml', extra)

    def wait_promotable(self, expected_index):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                job = self.platform.api('job/cowork-gateway')
                if job['JobModifyIndex'] != expected_index:
                    raise RuntimeError('Gateway job changed while waiting for a healthy canary')
                deployment = self.platform.api('job/cowork-gateway/deployment')
                if deployment and deployment['Status'] == 'running':
                    group = deployment['TaskGroups']['app']
                    if group['DesiredCanaries'] == 1 and group['HealthyAllocs'] >= 1 and not group['Promoted']:
                        return
            except TransientPlatformError:
                pass
            time.sleep(5)
        raise RuntimeError('Gateway canary did not become promotable')

    def promote(self):
        deployment = self.platform.api('job/cowork-gateway/deployment')
        if not deployment or deployment['Status'] != 'running':
            raise RuntimeError('Gateway has no promotable deployment')
        group = deployment['TaskGroups']['app']
        if group['DesiredCanaries'] != 1 or group['HealthyAllocs'] < 1 or group['Promoted']:
            raise RuntimeError('Gateway canary is not healthy and awaiting promotion')
        self.platform.api('deployment/promote/' + deployment['ID'], method='POST',
                 body={'DeploymentID': deployment['ID'], 'All': True})

    def wait_promotion(self, previous_id, index, specs):
        self.platform.wait_jobs({'cowork-gateway': index}, specs)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                deployment = self.platform.api('job/cowork-gateway/deployment')
                allocations = self.platform.api('job/cowork-gateway/allocations') or []
                old = next((allocation for allocation in allocations if allocation['ID'] == previous_id), None)
                if deployment and deployment['Status'] == 'successful' and (not old or old['DesiredStatus'] == 'stop'):
                    return
            except TransientPlatformError:
                pass
            time.sleep(5)
        raise RuntimeError('Gateway promotion did not retire the previous allocation')

    def rollback(self, previous_job, previous_allocation, directory):
        previous_port = self.host_port(previous_allocation)
        old = self.platform.api('allocation/' + previous_allocation['ID'], missing=True)
        if old and old['DesiredStatus'] == 'run' and old['ClientStatus'] == 'running':
            self.snapshot(old)
            # Keep the old allocation alive until the proxy has drained its new workers.
            self.switch(previous_port, directory)
            index = self.platform.register_previous_job('cowork-gateway', previous_job)
            self.platform.wait_jobs({'cowork-gateway': index}, {'cowork-gateway': previous_job})
            return

        # Promotion may already have stopped the old allocation. Recreate it as a
        # canary while the new allocation continues serving, then move the proxy.
        current = self.platform.active_allocation('cowork-gateway')
        index = self.platform.register_previous_job('cowork-gateway', previous_job, canary=1)
        candidate, port = self.candidate(current['ID'], index, previous_port)
        self.wait_promotable(index)
        self.switch(port, directory)
        self.promote()
        self.wait_promotion(current['ID'], index, {'cowork-gateway': previous_job})
        self.snapshot(candidate)

    def apply_cold(self, variables, plan_file, specs, directory, target_port):
        if target_port != 21000:
            raise RuntimeError('Gateway cold start must use host port 21000')
        indices = self.platform.tofu_apply_plan('edge', variables, plan_file)
        job = self.platform.api('job/cowork-gateway')
        if job.get('Stop'):
            raise RuntimeError('Gateway job remains stopped after edge apply; it cannot serve traffic')
        candidate, port = self.candidate(None, indices['cowork-gateway'], target_port)
        self.platform.wait_jobs({'cowork-gateway': indices['cowork-gateway']}, specs)
        self.switch(port, directory)
        self.verify(port, directory)
        self.snapshot(candidate)
        print('Gateway traffic switched to verified allocation ' + candidate['ID'], flush=True)

    def apply(self, variables, plan_file, specs, directory, target_port):
        previous_job = self.platform.api('job/cowork-gateway')
        previous_allocation = self.platform.active_allocation('cowork-gateway')
        previous_port = self.host_port(previous_allocation)
        if target_port not in (21000, 21001) or target_port == previous_port:
            raise RuntimeError('Gateway planned host port does not alternate from the active allocation')
        try:
            indices = self.platform.tofu_apply_plan('edge', variables, plan_file)
            candidate, port = self.candidate(previous_allocation['ID'], indices['cowork-gateway'], target_port)
            self.wait_promotable(indices['cowork-gateway'])
            self.switch(port, directory)
            self.promote()
            self.wait_promotion(previous_allocation['ID'], indices['cowork-gateway'], specs)
            self.verify(port, directory)
            self.snapshot(candidate)
            print('Gateway traffic switched to verified allocation ' + candidate['ID'], flush=True)
        except Exception:
            try:
                current_job = self.platform.api('job/cowork-gateway', missing=True)
            except Exception:
                current_job = None
            if current_job is None or current_job['JobModifyIndex'] != previous_job['JobModifyIndex']:
                try:
                    self.rollback(previous_job, previous_allocation, directory)
                except Exception as rollback_error:
                    raise RuntimeError('Gateway switch and automatic rollback both failed (' +
                                       type(rollback_error).__name__ + ')') from None
            raise
