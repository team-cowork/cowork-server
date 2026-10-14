#!/usr/bin/env python3
"""Apply existing VM and service configuration with private, short-lived inputs."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import signal
import subprocess
import sys
from urllib.parse import urlencode

ROOT = Path(__file__).resolve().parents[2]
IAC = ROOT / 'deploy/iac'


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def stop_process(process, timeout=60):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def execute(arguments, env):
    process = subprocess.Popen([str(value) for value in arguments], cwd=ROOT, env=env, start_new_session=True)
    try:
        result = process.wait()
    except BaseException:
        stop_process(process)
        raise
    if result:
        raise RuntimeError('Infrastructure command failed; inspect the preceding non-secret diagnostics')


def github_api(endpoint, env, payload=None):
    repository = env.get('GITHUB_REPOSITORY', '')
    demand(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    command = ['gh', 'api', '--method', 'GET' if payload is None else 'POST',
               f'repos/{repository}/{endpoint}']
    if payload is not None:
        command.extend(['--input', '-'])
    result = subprocess.run(command, input=None if payload is None else json.dumps(payload),
                            env=env, capture_output=True, text=True)
    demand(result.returncode == 0, 'GitHub IaC deployment history could not be read or saved')
    return json.loads(result.stdout)


def deployment_pages(endpoint, env, query=None):
    page = 1
    while True:
        values = github_api(endpoint + '?' + urlencode({**(query or {}), 'per_page': 100, 'page': page}), env)
        demand(isinstance(values, list), 'Invalid GitHub deployment history response')
        yield from values
        if len(values) < 100:
            return
        page += 1


def infrastructure_source(path):
    value = Path(path)
    if value.suffix.lower() in {'.md', '.rst', '.adoc'} or 'docs' in value.parts:
        return False
    if path == '.github/workflows/cowork-prod-cd.yml':
        return True
    if path.startswith(('deploy/iac/', 'deploy/prod/slots/', 'deploy/prod/monitoring/',
                        'deploy/prod/log-agent/', 'deploy/prod/node-agent/', 'deploy/prod/lib/', 'deploy/prod/cd/',
                        'deploy/prod/network/', 'deploy/prod/config/', 'deploy/config/monitoring/',
                        'deploy/prod/storage/', 'deploy/config/seaweedfs/', 'deploy/images/elasticsearch/')):
        return True
    if path in {'deploy/prod/inventory.json', 'deploy/prod/configuration.json', 'deploy/prod/network/host-network.json', 'deploy/prod/services/monitoring.sh',
                'deploy/prod/services/log-agent.sh', 'deploy/prod/services/node-agent.sh', 'deploy/prod/services/config.sh', 'deploy/compose/stack.yaml'}:
        return True
    return value.parent == Path('deploy/prod') and value.suffix in {'.py', '.sh', '.service'}


def automatic_apply_needed(sha, env):
    for deployment in deployment_pages('deployments', env,
                                       {'environment': 'Prod-IaC', 'task': 'cowork-infrastructure'}):
        identifier = deployment.get('id')
        demand(isinstance(identifier, int) and identifier > 0, 'Invalid IaC deployment identifier')
        if not any(status.get('state') == 'success'
                   for status in deployment_pages(f'deployments/{identifier}/statuses', env)):
            continue
        baseline = deployment.get('sha', '')
        demand(isinstance(baseline, str) and re.fullmatch(r'[0-9a-f]{40}', baseline),
               'Invalid successful IaC deployment SHA')
        ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', baseline, sha],
                                  cwd=ROOT, env=env, capture_output=True)
        if ancestor.returncode != 0:
            newer = subprocess.run(['git', 'merge-base', '--is-ancestor', sha, baseline],
                                   cwd=ROOT, env=env, capture_output=True)
            if newer.returncode == 0:
                print('[iac] A newer source is already applied; skip this stale automatic run')
                return False
            print('[iac] The previous source baseline is unavailable; apply the selected infrastructure source')
            return True
        changes = subprocess.run(['git', 'diff', '--name-only', '--no-renames', '-z', baseline, sha, '--'],
                                 cwd=ROOT, env=env, capture_output=True, check=True)
        paths = [os.fsdecode(path) for path in changes.stdout.split(b'\0') if path]
        changed = any(infrastructure_source(path) for path in paths)
        print(f'[iac] Compared infrastructure sources with successful deployment {baseline}: '
              + ('changes require apply' if changed else 'no changes require apply'))
        return changed
    print('[iac] No successful infrastructure deployment exists; initial apply is required')
    return True


def result_output(value):
    demand(value in {'checked', 'skipped', 'applied'}, 'Invalid IaC result')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'result={value}\n')


def vault_identity(env):
    authentication = runpy.run_path(str(ROOT / 'deploy/prod/cd/vault-auth.py'))
    return authentication['authenticate'](env, 'prod-iac')


def record_success(sha, host_count, env):
    if not env.get('GITHUB_RUN_ID'):
        print('[iac] Applied locally; no GitHub Actions deployment record was created')
        return
    repository = env['GITHUB_REPOSITORY']
    deployment = github_api('deployments', env, {
        'ref': sha, 'task': 'cowork-infrastructure', 'environment': 'Prod-IaC',
        'auto_merge': False, 'required_contexts': [], 'production_environment': True,
        'payload': {'host_count': host_count, 'scope': 'hosts-persistent-services-and-monitoring'},
        'description': 'Host, persistent service and monitoring configuration applied',
    })
    github_api(f'deployments/{deployment["id"]}/statuses', env, {
        'state': 'success', 'auto_inactive': False,
        'description': 'Host, persistent service and monitoring configuration applied',
        'log_url': f'{env.get("GITHUB_SERVER_URL", "https://github.com")}/{repository}/actions/runs/{env["GITHUB_RUN_ID"]}',
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--work-directory', type=Path, required=True)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--automatic', action='store_true')
    parser.add_argument('--settings-targets', help='Apply only these comma-separated infrastructure selectors')
    args = parser.parse_args()
    demand(re.fullmatch(r'[0-9a-f]{40}', args.sha), 'A full release commit is required')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    demand(head == args.sha, 'Checkout must match the exact release SHA')
    demand(args.settings_targets is None or not args.automatic,
           'A settings apply cannot request a full automatic apply')
    work = args.work_directory
    demand(work.is_absolute() and not work.is_symlink() and not work.exists() and not work.resolve().is_relative_to(ROOT),
           'The private work directory must be a new absolute path outside the checkout')
    os.umask(0o077)
    work.mkdir(mode=0o700, parents=True)
    env = {**os.environ, 'ANSIBLE_CONFIG': str(IAC / 'ansible/ansible.cfg'), 'ANSIBLE_HOST_KEY_CHECKING': 'True'}
    try:
        if args.automatic:
            if not args.check_only and not automatic_apply_needed(args.sha, env):
                print('[iac] Automatic apply skipped. Existing infrastructure was not changed; drift was not inspected.')
                result_output('skipped')
                return
        execute([sys.executable, ROOT / 'deploy/prod/slots.py', 'validate'], env)
        for source in ('databases/runtime.py', 'databases/search.py', 'shared/runtime.py', 'storage/runtime.py'):
            execute([sys.executable, IAC / source, 'check'], env)
        execute(['ansible-playbook', '-i', IAC / 'inventory.example.yml', IAC / 'ansible/site.yml', '--syntax-check'], env)
        if args.check_only:
            print('[iac] Static validation passed. No Vault read, SSH connection or deployment occurred.')
            result_output('checked')
            return
        demand(bool(env.get('VAULT_ADDR')), 'VAULT_ADDR is required for an infrastructure apply')
        env = vault_identity(env)
        inputs = work / 'inputs'
        command = [sys.executable, IAC / 'scripts/prepare.py', '--sha', args.sha, '--output', inputs]
        if args.settings_targets is not None:
            command.extend(('--settings-targets', args.settings_targets))
        execute(command, env)
        metadata = json.loads((inputs / 'metadata.json').read_text())
        if metadata['host_count']:
            execute([IAC / 'apply.sh', 'hosts'], {**env, 'IAC_INVENTORY': str(inputs / 'inventory.json')})
        if args.settings_targets is not None:
            print(f'[iac] Applied selected settings on {metadata["host_count"]} hosts; persistent data retained')
            result_output('applied')
            return
        record_success(args.sha, metadata['host_count'], env)
        print(f'[iac] Applied {metadata["host_count"]} hosts and provisioned monitoring configuration')
        result_output('applied')
    finally:
        shutil.rmtree(work)


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except KeyboardInterrupt:
        print('[iac] Interrupted; inspected child cleanup before removing private inputs', file=sys.stderr)
        raise SystemExit(130)
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.CalledProcessError) as error:
        # Do not print credentials, response bodies or command argument dumps.
        message = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
        print(f'[iac] {message}', file=sys.stderr)
        raise SystemExit(1)
