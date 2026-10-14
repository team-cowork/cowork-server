#!/usr/bin/env python3
"""Apply existing VM configuration and Grafana state with private, short-lived inputs."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

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
        # state.py gets time to persist a partial apply before its group is stopped.
        stop_process(process, timeout=90)
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
    if path in {'deploy/prod/inventory.json', 'deploy/prod/network/host-network.json', 'deploy/prod/services/monitoring.sh',
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
    if env.get('GITHUB_ACTIONS') != 'true':
        demand(bool(env.get('VAULT_TOKEN')), 'Local apply requires an explicit VAULT_TOKEN')
        return env
    address = urlsplit(env['VAULT_ADDR'])
    demand(address.scheme == 'https' and address.hostname and not address.username and not address.password
           and not address.query and not address.fragment, 'Vault OIDC requires its canonical HTTPS address')
    request_url = urlsplit(env.get('ACTIONS_ID_TOKEN_REQUEST_URL', ''))
    demand(request_url.scheme == 'https' and request_url.hostname and not request_url.username
           and not request_url.password and not request_url.fragment
           and bool(env.get('ACTIONS_ID_TOKEN_REQUEST_TOKEN')), 'GitHub OIDC request credentials are unavailable')
    audience = env['VAULT_ADDR'].rstrip('/')
    query = [(key, value) for key, value in parse_qsl(request_url.query, keep_blank_values=True) if key != 'audience']
    query.append(('audience', audience))
    url = urlunsplit(request_url._replace(query=urlencode(query)))

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError('Authentication redirects are not allowed')

    opener = urllib.request.build_opener(NoRedirect())
    try:
        request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + env['ACTIONS_ID_TOKEN_REQUEST_TOKEN']})
        with opener.open(request, timeout=30) as response:
            jwt = json.load(response)['value']
        request = urllib.request.Request(audience + '/v1/auth/github-actions/login',
                                         data=json.dumps({'role': 'prod-iac', 'jwt': jwt}).encode(),
                                         headers={'Content-Type': 'application/json'})
        with opener.open(request, timeout=30) as response:
            token = json.load(response)['auth']['client_token']
        demand(isinstance(token, str) and token, 'Vault did not issue an IaC token')
    except (OSError, ValueError, KeyError, urllib.error.URLError):
        raise RuntimeError('GitHub OIDC authentication to Vault failed; response bodies were withheld') from None
    print('::add-mask::' + token.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'), flush=True)
    result = {**env, 'VAULT_TOKEN': token}
    result.pop('ACTIONS_ID_TOKEN_REQUEST_TOKEN', None)
    result.pop('ACTIONS_ID_TOKEN_REQUEST_URL', None)
    print('[iac] Authenticated to Vault with the GitHub workload identity')
    return result


def grafana_environment(path):
    info = path.lstat()
    demand(path.is_file() and not path.is_symlink() and info.st_uid == os.getuid() and not info.st_mode & 0o077,
           'Grafana input must be a private file owned by the invoking user')
    content = path.read_bytes()
    demand(len(content) <= 65536 and content.endswith(b'\0'), 'Invalid Grafana environment input')
    values = {}
    for item in content[:-1].split(b'\0'):
        key, separator, value = item.decode().partition('=')
        demand(separator and key in {'GRAFANA_URL', 'GRAFANA_AUTH'} and key not in values and value,
               'Invalid Grafana environment key')
        values[key] = value
    demand(set(values) == {'GRAFANA_URL', 'GRAFANA_AUTH'} and values['GRAFANA_URL'] == 'http://127.0.0.1:43001',
           'Grafana must use the declared loopback SSH tunnel')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        for value in (values['GRAFANA_AUTH'], values['GRAFANA_AUTH'].partition(':')[2]):
            if value:
                print('::add-mask::' + value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'), flush=True)
    return values


def wait_for_grafana(tunnel):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(30):
        demand(tunnel.poll() is None, 'The monitoring SSH tunnel terminated')
        try:
            with opener.open('http://127.0.0.1:43001/api/health', timeout=3) as response:
                if response.status == 200 and json.load(response).get('database') == 'ok':
                    return
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(2)
    raise RuntimeError('Grafana did not become ready through the SSH tunnel')


def record_success(sha, host_count, env):
    if not env.get('GITHUB_RUN_ID'):
        print('[iac] Applied locally; no GitHub Actions deployment record was created')
        return
    repository = env['GITHUB_REPOSITORY']
    deployment = github_api('deployments', env, {
        'ref': sha, 'task': 'cowork-infrastructure', 'environment': 'Prod-IaC',
        'auto_merge': False, 'required_contexts': [], 'production_environment': True,
        'payload': {'host_count': host_count, 'scope': 'hosts-persistent-services-and-monitoring'},
        'description': 'Host and persistent service configuration applied; Grafana state persisted',
    })
    github_api(f'deployments/{deployment["id"]}/statuses', env, {
        'state': 'success', 'auto_inactive': False,
        'description': 'Host and persistent service configuration applied; Grafana state persisted',
        'log_url': f'{env.get("GITHUB_SERVER_URL", "https://github.com")}/{repository}/actions/runs/{env["GITHUB_RUN_ID"]}',
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--work-directory', type=Path, required=True)
    parser.add_argument('--recovery-file', type=Path)
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--automatic', action='store_true')
    parser.add_argument('--initialize-state', action='store_true')
    args = parser.parse_args()
    demand(re.fullmatch(r'[0-9a-f]{40}', args.sha), 'A full release commit is required')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    demand(head == args.sha, 'Checkout must match the exact release SHA')
    demand(not args.initialize_state or not (args.automatic or args.check_only),
           'State initialization is only available to an explicit manual apply')
    work = args.work_directory
    demand(work.is_absolute() and not work.is_symlink() and not work.exists() and not work.resolve().is_relative_to(ROOT),
           'The private work directory must be a new absolute path outside the checkout')
    if not args.check_only:
        demand(args.recovery_file is not None and args.recovery_file.is_absolute()
               and not args.recovery_file.resolve().is_relative_to(ROOT)
               and not args.recovery_file.resolve().is_relative_to(work.resolve()),
               'Encrypted recovery output must be outside the checkout and disposable work directory')
    os.umask(0o077)
    work.mkdir(mode=0o700, parents=True)
    env = {**os.environ, 'ANSIBLE_CONFIG': str(IAC / 'ansible/ansible.cfg'), 'ANSIBLE_HOST_KEY_CHECKING': 'True'}
    tunnel = None
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
        validation_env = {**env, 'TF_DATA_DIR': str(work / 'terraform-validation')}
        execute(['terraform', f'-chdir={IAC / "terraform"}', 'fmt', '-check'], validation_env)
        execute(['terraform', f'-chdir={IAC / "terraform"}', 'init', '-backend=false', '-input=false', '-lockfile=readonly'], validation_env)
        execute(['terraform', f'-chdir={IAC / "terraform"}', 'validate'], validation_env)
        if args.check_only:
            print('[iac] Static validation passed. No Vault read, SSH connection, host apply, Terraform plan or deployment occurred.')
            result_output('checked')
            return
        for key in ('VAULT_ADDR', 'DISCORD_MONITORING_ALERT_WEBHOOK', 'IAC_STATE_ENCRYPTION_KEY', 'GITHUB_REPOSITORY'):
            demand(bool(env.get(key)), f'{key} is required for an infrastructure apply')
        env = vault_identity(env)
        inputs = work / 'inputs'
        execute([sys.executable, IAC / 'scripts/prepare.py', '--sha', args.sha, '--output', inputs], env)
        execute([IAC / 'apply.sh', 'hosts'], {**env, 'IAC_INVENTORY': str(inputs / 'inventory.json')})
        metadata = json.loads((inputs / 'metadata.json').read_text())
        alias = metadata['monitoring_ssh_alias']
        demand(re.fullmatch(r'cowork-iac-[a-z][a-z0-9-]*', alias), 'Invalid monitoring SSH alias')
        tunnel = subprocess.Popen([
            'ssh', '-F', str(inputs / 'ssh-config'), '-N', '-T',
            '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
            '-L', '127.0.0.1:43001:127.0.0.1:3001', alias,
        ], env=env, start_new_session=True)
        wait_for_grafana(tunnel)
        # state.py's temporary checkout and plaintext state are covered by this run's cleanup.
        grafana_env = {**env, **grafana_environment(inputs / 'grafana.env0'), 'TMPDIR': str(work)}
        command = [sys.executable, IAC / 'scripts/state.py', '--recovery-file', args.recovery_file]
        if args.initialize_state:
            command.append('--initialize')
        command.extend(['--', IAC / 'apply.sh', 'grafana', 'apply'])
        execute(command, grafana_env)
        record_success(args.sha, metadata['host_count'], env)
        print(f'[iac] Applied {metadata["host_count"]} hosts and persisted Grafana state')
        result_output('applied')
    finally:
        if tunnel is not None:
            stop_process(tunnel, timeout=10)
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
