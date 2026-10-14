#!/usr/bin/env python3
"""Reconcile module-owned SeaweedFS without touching application or database containers."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
from urllib.parse import urlsplit

SOURCE = Path(__file__).resolve().parent
DEPLOY = SOURCE.parents[1]
PROD = DEPLOY / 'prod'
SECRET_KEYS = {'S3_ACCESS_KEY', 'S3_SECRET_KEY', 'S3_ADMIN_ACCESS_KEY', 'S3_ADMIN_SECRET_KEY'}
DATA_DIRECTORIES = {'seaweedfs': 'data', 'seaweedfs-admin': 'admin-data', 'seaweedfs-lifecycle': 'lifecycle-data'}


def catalog():
    values = json.loads((SOURCE / 'catalog.json').read_text())
    for module, item in values.items():
        if module not in {'team', 'user', 'chat'}:
            raise ValueError('Unknown storage owner')
        if not item['prefixes'] or any(not re.fullmatch(r'[a-z][a-z-]+', prefix) for prefix in item['prefixes']):
            raise ValueError('Invalid object storage prefixes')
        if not set(item['public_prefixes']) <= set(item['prefixes']) or item['temporary_prefix'] not in item['prefixes']:
            raise ValueError('Storage policies must use the owner prefixes')
        if item['public_prefixes'] and (module != 'team' or item['public_prefixes'] != ['team-icons']):
            raise ValueError('Only completed Team icons can be public')
        if not re.fullmatch(r'[1-9][0-9]*[mg]', item['memory']) or not 0 < item['cpus'] <= 16:
            raise ValueError('Invalid storage resource limits')
    return values


def credentials(value):
    if set(value) != SECRET_KEYS or any(not isinstance(secret, str) or not re.fullmatch(r'[A-Za-z0-9_+=/.-]{20,128}', secret)
                                       or secret.startswith(('local-', 'seaweedfs', 'REPLACE_')) for secret in value.values()):
        raise ValueError('Storage requires four generated application and administrator credential values')
    if len(set(value.values())) != len(SECRET_KEYS):
        raise ValueError('Storage application and administrator credentials must differ')
    return value


def write(path, content, mode=0o600, uid=0):
    temporary = path.with_name(path.name + '.new')
    with temporary.open('wb') as output:
        os.fchmod(output.fileno(), mode)
        os.fchown(output.fileno(), uid, uid)
        output.write(content if isinstance(content, bytes) else content.encode())
    temporary.replace(path)


def run(*args, content=None):
    result = subprocess.run(args, input=content, text=True, capture_output=True, timeout=600)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed; inspect the module storage containers locally')
    return result.stdout


def documents(module, specification, bucket, secrets):
    identity = 'cowork-' + module
    iam = {'identities': [
        {'name': identity + '-storage-admin', 'credentials': [{'accessKey': secrets['S3_ADMIN_ACCESS_KEY'], 'secretKey': secrets['S3_ADMIN_SECRET_KEY']}], 'actions': ['Admin']},
        {'name': identity, 'credentials': [{'accessKey': secrets['S3_ACCESS_KEY'], 'secretKey': secrets['S3_SECRET_KEY']}],
         'actions': [f'{action}:{bucket}/{prefix}/*' for prefix in specification['prefixes'] for action in ('Read', 'Write')]},
    ]}
    policy = {'Version': '2012-10-17', 'Statement': [{
        'Sid': 'DenyServiceAclChanges', 'Effect': 'Deny',
        'Principal': {'AWS': [f'arn:aws:iam::000000000000:user/{identity}']},
        'Action': ['s3:PutObjectAcl', 's3:PutBucketAcl'],
        'Resource': [f'arn:aws:s3:::{bucket}', f'arn:aws:s3:::{bucket}/*'],
    }]}
    if specification['public_prefixes']:
        iam['identities'].append({'name': 'anonymous', 'actions': [f'Read:{bucket}/{prefix}/*' for prefix in specification['public_prefixes']]})
        policy['Statement'].append({'Sid': 'PublicTeamIconRead', 'Effect': 'Allow', 'Principal': '*',
                                    'Action': 's3:GetObject', 'Resource': [f'arn:aws:s3:::{bucket}/{prefix}/*' for prefix in specification['public_prefixes']]})
    lifecycle = {'Rules': [{'ID': 'ExpireTemporaryUploads', 'Status': 'Enabled',
                            'Filter': {'Prefix': specification['temporary_prefix'] + '/'}, 'Expiration': {'Days': 1}}]}
    return iam, policy, lifecycle


def render(module, specification, secrets, slot, config, network, directory, data_root):
    bucket, region = slot['runtime']['S3_BUCKET'], slot['runtime']['S3_REGION']
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket) or not re.fullmatch(r'[a-z0-9-]+', region):
        raise ValueError('Invalid module bucket or signing region')
    origins = config['runtime']['PUBLIC_WEB_ORIGINS']
    for raw in origins.split(','):
        origin = urlsplit(raw)
        loopback = origin.scheme == 'http' and origin.hostname in ('localhost', '127.0.0.1')
        if ((origin.scheme != 'https' and not loopback) or not origin.hostname or origin.username or origin.password or
                origin.path or origin.query or origin.fragment or not re.fullmatch(r'https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?', raw)):
            raise ValueError('Storage web origins must be explicit HTTPS or loopback HTTP origins')
    iam, policy, lifecycle = documents(module, specification, bucket, secrets)
    iam_json = json.dumps(iam, indent=2) + '\n'
    for name, mode in (('config', 0o700), ('scripts', 0o755)):
        (directory / name).mkdir(parents=True, exist_ok=True, mode=mode)
        os.chmod(directory / name, mode)
    write(directory / 'config/s3-config.json', iam_json, 0o400, 10001)
    write(directory / 'config/admin.env', f"AWS_ACCESS_KEY_ID={secrets['S3_ADMIN_ACCESS_KEY']}\nAWS_SECRET_ACCESS_KEY={secrets['S3_ADMIN_SECRET_KEY']}\n")
    for name in ('configure-bucket.sh', 'render-cors.sh'):
        write(directory / 'scripts' / name, (DEPLOY / 'config/seaweedfs' / name).read_bytes(), 0o444)
    for name, value in (('bucket-policy.json', policy), ('bucket-lifecycle.json', lifecycle)):
        write(directory / 'scripts' / name, json.dumps(value) + '\n', 0o444)
    environment = {'STORAGE_PROJECT': f'cowork-{module}-storage', 'STORAGE_BIND_IP': slot['container']['advertise_address'],
                   'STORAGE_RUNTIME_NETWORK': network, 'STORAGE_MEMORY': specification['memory'],
                   'STORAGE_CPUS': str(specification['cpus']), 'STORAGE_IAM_DIGEST': hashlib.sha256(iam_json.encode()).hexdigest(),
                   'S3_BUCKET': bucket, 'S3_REGION': region, 'PUBLIC_WEB_ORIGINS': origins}
    write(directory / 'compose.env', ''.join(f'{key}={value}\n' for key, value in environment.items()))
    override = {'services': {service: {'volumes': [{'type': 'bind', 'source': str(data_root / name),
                'target': '/data', 'bind': {'create_host_path': False}}]} for service, name in DATA_DIRECTORIES.items()}}
    write(directory / 'compose.override.json', json.dumps(override) + '\n')


def apply(module, specification, directory):
    if os.geteuid() != 0:
        raise ValueError('Module storage apply requires root')
    os.umask(0o077)
    secrets = credentials(json.load(sys.stdin))
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        slots = runpy.run_path(str(PROD / 'slots.py'))
        slot, gateway, config, monitoring = (slots['load'](name, PROD / 'slots') for name in (module, 'gateway', 'config', 'monitoring'))
        network = slots['host_network'](PROD)['name']
        topology = json.loads(run('docker', 'network', 'inspect', network))[0]
        if topology['Driver'] != 'bridge' or topology.get('EnableIPv6') or len(topology['IPAM']['Config']) != 1:
            raise ValueError('Module storage requires the local IPv4 application bridge')
        bridge = topology.get('Options', {}).get('com.docker.network.bridge.name') or 'br-' + topology['Id'][:12]
        subnet = topology['IPAM']['Config'][0]['Subnet']
        slots['private'](subnet)
        firewall = {'bind_ip': slot['container']['advertise_address'],
                    'allowed_cidrs': [gateway['container']['advertise_address'] + '/32', monitoring['runtime']['MONITORING_BIND_IP'] + '/32'],
                    'bridge': bridge, 'subnet': subnet}
        for path in (Path('/etc/cowork'), Path('/usr/local/libexec')):
            path.mkdir(parents=True, exist_ok=True)
        write(Path('/etc/cowork/storage-firewall.json'), json.dumps(firewall) + '\n')
        write(Path('/usr/local/libexec/cowork-storage-firewall.py'), (PROD / 'storage/firewall.py').read_bytes(), 0o644)
        write(Path('/etc/systemd/system/cowork-storage-firewall.service'), (PROD / 'storage/firewall.service').read_bytes(), 0o644)
        run('/usr/bin/python3', '-I', '/usr/local/libexec/cowork-storage-firewall.py')
        run('systemctl', 'daemon-reload')
        run('systemctl', 'enable', 'cowork-storage-firewall.service')
        run('systemctl', 'start', 'cowork-storage-firewall.service')
        data_root = Path('/var/lib/cowork/storage') / module
        for name in DATA_DIRECTORIES.values():
            path = data_root / name
            path.mkdir(parents=True, exist_ok=True, mode=0o750)
            if path.is_symlink() or not path.is_dir():
                raise ValueError('Module storage requires dedicated data directories')
            os.chown(path, 10001, 10001)
            os.chmod(path, 0o750)
        render(module, specification, secrets, slot, config, network, directory, data_root)
        compose = ['docker', 'compose', '--project-directory', str(directory), '--env-file', str(directory / 'compose.env'),
                   '-p', f'cowork-{module}-storage', '-f', str(PROD / 'storage/compose.yaml'), '-f', str(directory / 'compose.override.json')]
        run(*compose, 'config', '--quiet')
        run(*compose, 'pull')
        # Workers share the server's network namespace and must follow a changed server container.
        run(*compose, 'stop', 'seaweedfs-admin', 'seaweedfs-lifecycle')
        run(*compose, 'up', '-d', '--wait', '--wait-timeout', '180', 'seaweedfs')
        run(*compose, 'run', '--rm', '--no-deps', 'seaweedfs-init')
        run(*compose, 'up', '-d', '--no-deps', '--force-recreate', 'seaweedfs-admin')
        run(*compose, 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '180', 'seaweedfs-lifecycle')
        print(f'{module}: storage and lifecycle worker ready; owner IAM and bucket policy applied; existing data retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'apply'))
    parser.add_argument('module', nargs='?')
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    values = catalog()
    if args.operation == 'check':
        print(f'Validated {len(values)} module storage definitions; no runtime changes')
        return
    if args.module not in values:
        raise ValueError('Storage apply requires a declared module')
    directory = args.directory or Path('/root/cowork-iac/storage') / args.module
    apply(args.module, values[args.module], directory.resolve())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'[module-storage] {error}', file=sys.stderr)
        sys.exit(1)
