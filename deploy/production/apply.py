#!/usr/bin/env python3
"""Reconcile the production release and recover interrupted rollouts."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import tempfile
import time
import uuid

from deploy.nomad.render import render_jobs
from deploy.production.release import validate_manifest
from deploy.production.rollout.gateway import Gateway
from deploy.production.rollout.platform import Platform, ROOT
from deploy.production.rollout.services import Services

STAGES = ('foundation', 'data', 'apps', 'edge')


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
    validate_manifest(manifest)
    if os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Production reconciliation is restricted to main')
    platform = Platform(state)
    gateway = Gateway(platform)
    services = Services(platform, gateway)
    mount = os.environ.get('VAULT_KV_MOUNT', 'secret')
    with closing(platform), tempfile.TemporaryDirectory(prefix='cowork-release-', dir=state) as temp:
        directory = Path(temp)
        business_services = {'cowork-' + name for name in manifest['applications'] if name != 'gateway'}
        if args.recover_state:
            if args.recover_state not in business_services:
                raise ValueError('Recover only a declared business application service')
            if args.recover_state == 'cowork-authorization':
                services.warn_webhook_secrets(manifest, mount)
            services.recover_state(args.recover_state)
            return
        previous_gateway = gateway.active_or_absent()
        previous_port = gateway.host_port(previous_gateway) if previous_gateway else None
        if previous_port is not None and previous_port not in (21000, 21001):
            raise RuntimeError('Gateway uses an unexpected host ingress port')
        current_target_port = previous_port or 21000
        specs, jobs = render_jobs(manifest, mount, current_target_port)
        base_variables = {'platform_ca_file': str(platform.ca), 'vault_mount': mount}
        variables = {}
        for stage in STAGES:
            variables[stage] = directory / (stage + '.tfvars.json')
            values = base_variables if stage == 'foundation' else {**base_variables, 'jobs': jobs[stage]}
            variables[stage].write_text(json.dumps(values))
        edge_variables = variables['edge']
        if args.check:
            for stage in STAGES:
                platform.tofu(stage, variables[stage], check=True)
            return
        services.warn_webhook_secrets(manifest, mount)
        if previous_gateway:
            gateway.snapshot(previous_gateway)
        prior_states = services.deployment_states()
        services.normalize_stale_states(prior_states, manifest, gateway_has_probe=bool(previous_gateway))
        platform.ansible('site.yml')
        if previous_gateway:
            try:
                gateway.verify(previous_port, directory)
            except RuntimeError:
                job = platform.api('job/cowork-gateway')
                platform.wait_jobs({'cowork-gateway': job['JobModifyIndex']}, {'cowork-gateway': job})
                gateway.snapshot(previous_gateway)
                gateway.switch(previous_port, directory)
                gateway.verify(previous_port, directory)
        else:
            platform.ansible('gateway-offline.yml')
        platform.tofu('foundation', variables['foundation'])
        platform.wait_jobs(platform.tofu('data', variables['data']), specs)
        migration = specs['cowork-user-migrations']
        previous = platform.api('job/cowork-user-migrations', missing=True)
        migration['Meta'] = migration.get('Meta') or {}
        migration['Meta']['release_run'] = str(time.time_ns())
        registered = platform.api('jobs', method='POST', body={'Job': migration, 'EnforceIndex': True,
                                  'JobModifyIndex': previous['JobModifyIndex'] if previous else 0})
        platform.wait_jobs({'cowork-user-migrations': registered['JobModifyIndex']}, specs, batch=True)
        apps_plan = directory / 'apps.tfplan'
        edge_plan = directory / 'edge.tfplan'
        changed_apps = platform.tofu_plan_jobs('apps', variables['apps'], apps_plan)
        changed_edge = platform.tofu_plan_jobs('edge', edge_variables, edge_plan)
        if changed_edge - {'cowork-gateway'}:
            raise RuntimeError('Unexpected changed edge jobs')
        if previous_gateway and changed_edge:
            current_target_port = 21001 if previous_port == 21000 else 21000
            edge_variables = directory / 'edge-alternate.tfvars.json'
            alternate_specs, alternate_jobs = render_jobs(manifest, mount, current_target_port)
            specs['cowork-gateway'] = alternate_specs['cowork-gateway']
            edge_variables.write_text(json.dumps({**base_variables, 'jobs': alternate_jobs['edge']}))
            edge_plan = directory / 'edge-alternate.tfplan'
            changed_edge = platform.tofu_plan_jobs('edge', edge_variables, edge_plan)
            if changed_edge != {'cowork-gateway'}:
                raise RuntimeError('Gateway alternate-port plan did not change exactly one job')
        run_id = (os.environ.get('GITHUB_RUN_ID') or manifest['source_sha']) + ':' + uuid.uuid4().hex
        services.apply(variables['apps'], apps_plan, changed_apps, specs, run_id,
                       gateway_available=bool(previous_gateway),
                       flag_services=changed_apps if previous_gateway else business_services)
        if previous_gateway is None:
            gateway.apply_cold(edge_variables, edge_plan, specs, directory, current_target_port)
        elif changed_edge:
            gateway.apply(edge_variables, edge_plan, specs, directory, current_target_port)
        else:
            platform.tofu_apply_plan('edge', edge_variables, edge_plan)
        services.recover_existing_states(manifest)
        print('The entire declared production release is ready', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit(f'Deployment failed ({type(error).__name__}); sensitive response bodies withheld') from None
