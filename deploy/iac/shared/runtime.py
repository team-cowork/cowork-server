#!/usr/bin/env python3
"""Apply dedicated shared services without replacing their persistent data."""
import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent
ROOT = SOURCE.parents[2]
FIREWALL = runpy.run_path(str(SOURCE / 'firewall.py'))
POLICY_KEYS = ('bind_ip', 'bindings', 'internal_ports', 'host_ports')
REPOSITORIES = {'redis': 'redis', 'kafka': 'apache/kafka', 'livekit': 'livekit/livekit-server'}


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def catalog():
    values = json.loads((SOURCE / 'catalog.json').read_text())
    demand(set(values) == set(REPOSITORIES), 'Declare Redis, Kafka and LiveKit as dedicated services')
    network = json.loads((ROOT / 'deploy/prod/network/host-network.json').read_text())['name']
    for service, value in values.items():
        demand(isinstance(value['secrets'], dict), 'Shared service secret references must be an object')
        for key, ref in value['secrets'].items():
            demand(re.fullmatch(r'[A-Z][A-Z0-9_]*', key) and isinstance(ref, dict)
                   and set(ref) == {'path', 'key'}, 'A shared service secret reference requires path and key')
            demand(all(isinstance(item, str) and item and not any(c in item for c in '\r\n\0')
                       for item in ref.values()), 'Shared service secret paths and keys must be nonempty single-line strings')
        demand(re.fullmatch(re.escape(REPOSITORIES[service]) + r':[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}', value['image']),
               'Shared service images require official versioned digest pins')
        demand(re.fullmatch(r'[1-9][0-9]*[mg]', value['memory']) and type(value['cpus']) in {int, float}
               and 0 < value['cpus'] <= 16, 'Invalid shared service resource limits')
        FIREWALL['policy']({'network': network, **{k: value[k] for k in POLICY_KEYS}})
        if service != 'livekit':
            demand(all(peer != '0.0.0.0/0' for b in value['bindings'] for peer in b['peers']),
                   'Redis and Kafka ingress must use explicit private peers')
        else:
            rtc = value['rtc']
            demand(ipaddress.IPv4Address(rtc['public_ip']).is_global, 'LiveKit RTC requires a public IPv4 address')
            ports = {(b['protocol'], b['container_port']) for b in value['bindings']}
            demand(('tcp', rtc['tcp_port']) in ports and ('udp', rtc['udp_port']) in ports
                   and ('tcp', 7880) in ports, 'LiveKit ingress must match its advertised RTC ports')
    return values, network


def credentials(service, specification, value):
    expected = set(specification['secrets'])
    demand(isinstance(value, dict) and set(value) == expected, 'Invalid shared service credential fields')
    demand(all(isinstance(v, str) and v and not any(c in v for c in '\r\n\0') for v in value.values()),
           'Shared service credentials must be nonempty single-line strings')
    if service == 'kafka':
        demand(re.fullmatch(r'[A-Za-z0-9_-]{22}', value['KAFKA_CLUSTER_ID']), 'Invalid Kafka cluster ID')
    return value


def write(path, content):
    temporary = path.with_suffix(path.suffix + '.new')
    with temporary.open('w') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(content)
    temporary.replace(path)


def run(*args, input_text=None, timeout=600):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        # LiveKit output and rendered configuration can include credentials.
        raise RuntimeError(f'{args[0]} failed; inspect the dedicated service on its VM')
    return result.stdout


def render(service, value, secrets, network, directory):
    name = 'cowork-' + service
    engine = {'image': value['image'], 'container_name': name, 'hostname': service,
              'restart': 'unless-stopped', 'networks': {'runtime': {'aliases': [service]}},
              'ports': [f'{value["bind_ip"]}:{p["host_port"]}:{p["container_port"]}/{p["protocol"]}' for p in value['bindings']],
              'mem_limit': value['memory'], 'cpus': value['cpus'], 'pids_limit': 512,
              'stop_grace_period': '60s', 'logging': {'driver': 'json-file', 'options': {'max-size': '20m', 'max-file': '5'}}}
    compose = {'name': name, 'services': {service: engine}, 'networks': {'runtime': {'external': True, 'name': network}}}
    if service in {'redis', 'kafka'}:
        mount = '/data' if service == 'redis' else '/var/lib/kafka/data'
        engine['volumes'] = [{'type': 'volume', 'source': 'data', 'target': mount, 'volume': {'nocopy': True}}]
        compose['volumes'] = {'data': {'external': True, 'name': name + '-data'}}
    if service == 'redis':
        engine['command'] = ['redis-server', '--appendonly', 'yes', '--appendfsync', 'everysec', '--protected-mode', 'no']
        health = ['CMD', 'redis-cli', 'ping']
    elif service == 'kafka':
        engine['environment'] = {
            'KAFKA_NODE_ID': '1', 'KAFKA_PROCESS_ROLES': 'broker,controller',
            'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094',
            'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://kafka:9092,EXTERNAL://' + value['bind_ip'] + ':9094',
            'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT',
            'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@kafka:9093', 'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
            'KAFKA_LOG_DIRS': '/var/lib/kafka/data', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'false',
            'KAFKA_DELETE_TOPIC_ENABLE': 'false', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1',
            'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1', 'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1',
            'KAFKA_HEAP_OPTS': '-Xms512m -Xmx768m', 'CLUSTER_ID': secrets['KAFKA_CLUSTER_ID'],
        }
        health = ['CMD', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--list']
    else:
        voice = json.loads((ROOT / 'deploy/prod/slots/voice.json').read_text())['container']
        config = {'port': 7880, 'rtc': {'node_ip': value['rtc']['public_ip'], 'use_external_ip': False,
                                      'tcp_port': value['rtc']['tcp_port'], 'udp_port': value['rtc']['udp_port']},
                  'turn': {'enabled': False}, 'keys': {secrets['LIVEKIT_API_KEY']: secrets['LIVEKIT_API_SECRET']},
                  'webhook': {'api_key': secrets['LIVEKIT_API_KEY'],
                              'urls': [f'http://{voice["advertise_address"]}:{voice["host_port"]}/voice/webhook']},
                  'logging': {'level': 'info'}}
        config_json = json.dumps(config, indent=2) + '\n'
        write(directory / 'livekit.json', config_json)
        engine['labels'] = {'cowork.config-digest': hashlib.sha256(config_json.encode()).hexdigest()}
        engine['command'] = ['--config', '/etc/livekit.yaml']
        engine['volumes'] = [{'type': 'bind', 'source': str(directory / 'livekit.json'), 'target': '/etc/livekit.yaml', 'read_only': True}]
        health = ['CMD-SHELL', 'wget -qO- http://127.0.0.1:7880 || exit 1']
    engine['healthcheck'] = {'test': health, 'interval': '10s', 'timeout': '10s', 'retries': 24, 'start_period': '30s'}
    write(directory / 'compose.json', json.dumps(compose, indent=2) + '\n')


def kafka_policies(directory):
    stack = json.loads(run('docker', 'compose', '--project-directory', str(directory), '--env-file', '/dev/null',
                           '-f', str(ROOT / 'deploy/compose/stack.yaml'), 'config', '--no-interpolate',
                           '--no-env-resolution', '--format', 'json'))['services']
    environment = stack['kafka-init']['environment']
    topics, compacted = environment['KAFKA_TOPICS'].split(), environment['KAFKA_COMPACTED_TOPICS'].split()
    demand(topics and len(topics) == len(set(topics)) and set(compacted) <= set(topics), 'Invalid Kafka topic catalog')
    demand(all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,248}', t) for t in topics), 'Invalid Kafka topic name')
    demand(environment['KAFKA_TOPIC_PARTITIONS'] == '${KAFKA_TOPIC_PARTITIONS:-1}' and
           environment['KAFKA_TOPIC_REPLICATION_FACTOR'] == '${KAFKA_TOPIC_REPLICATION_FACTOR:-1}',
           'Existing Kafka topics require one partition and one replica')
    command = '\n'.join(stack['kafka-init']['command']).replace('\\\n', ' ')
    retention = re.search(r"--entity-name\s+chat\.message\.v2\s+--add-config\s+'cleanup\.policy=delete,retention\.ms=([0-9]+)'", command)
    segment = re.search(r'--add-config\s+cleanup\.policy=compact,segment\.ms=([0-9]+)', command)
    demand(retention is not None and segment is not None and command.count('--add-config') == 2,
           'Review changed Kafka topic provisioning policy')
    policies = {topic: {'cleanup.policy': 'compact', 'segment.ms': segment.group(1)} if topic in compacted
                else {'cleanup.policy': 'delete'} for topic in topics}
    demand('chat.message.v2' in policies and 'chat.message.v2' not in compacted, 'Invalid Chat topic retention')
    policies['chat.message.v2']['retention.ms'] = retention.group(1)
    return policies


def topic_description():
    text = run('docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--describe')
    descriptions = {}
    for line in text.splitlines():
        if not line.startswith('Topic: ') or 'TopicId:' not in line:
            continue
        name = re.search(r'^Topic:\s+(\S+)', line).group(1)
        partitions = re.search(r'PartitionCount:\s+([0-9]+)', line)
        replication = re.search(r'ReplicationFactor:\s+([0-9]+)', line)
        demand(partitions is not None and replication is not None, 'Unexpected Kafka topic description')
        configurations = line.split('Configs:', 1)[1].strip() if 'Configs:' in line else ''
        descriptions[name] = (int(partitions.group(1)), int(replication.group(1)), configurations)
    return descriptions


def topic_policy(topic, key, configs):
    if configs is not None:
        listed = re.search(r'(?:^|,)\s*' + re.escape(key) + r'=([^,\s]+)', configs)
        if listed is not None:
            return listed.group(1)
    effective = run('docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-configs.sh',
                    '--bootstrap-server', 'localhost:9092', '--entity-type', 'topics',
                    '--entity-name', topic, '--describe', '--all')
    actual = re.search(r'(?:^|\s)' + re.escape(key) + r'=([^\s]+)', effective, re.MULTILINE)
    return actual.group(1) if actual is not None else None


def provision_topics(policies):
    before = topic_description()
    for topic, desired in policies.items():
        if topic not in before:
            args = ['docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                    'localhost:9092', '--create', '--if-not-exists', '--topic', topic, '--partitions', '1', '--replication-factor', '1']
            for key, value in desired.items():
                args.extend(('--config', key + '=' + value))
            run(*args)
    after = topic_description()
    for topic, desired in policies.items():
        demand(topic in after, 'A provisioned Kafka topic is missing')
        partitions, replication, configs = after[topic]
        demand(partitions == 1 and replication == 1, 'Kafka partition or replication contract differs')
        for key, expected in desired.items():
            if topic_policy(topic, key, configs) == expected:
                continue
            # A declared policy the broker never had must still reach existing topics; creation
            # only covers new ones, so converge here instead of failing the deployment.
            run('docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-configs.sh',
                '--bootstrap-server', 'localhost:9092', '--entity-type', 'topics',
                '--entity-name', topic, '--alter', '--add-config', key + '=' + expected)
            demand(topic_policy(topic, key, None) == expected,
                   'Existing Kafka topic policy differs from the source catalog')
    print(f'Kafka: verified {len(policies)} topics; created {len(set(policies) - set(before))} missing topics')


def apply(service, value, network, directory):
    demand(os.geteuid() == 0, 'Dedicated shared service apply requires root')
    os.umask(0o077)
    secrets = credentials(service, value, json.load(sys.stdin))
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        desired = {'network': network, **{key: value[key] for key in POLICY_KEYS}}
        installed = json.loads(Path('/etc/cowork/shared-firewall/policy.json').read_text())
        demand(all(installed[key] == item for key, item in desired.items()), 'Installed shared firewall does not match the catalog')
        run('systemctl', 'is-enabled', '--quiet', 'cowork-shared-firewall.service')
        run('/usr/bin/python3', '-I', '/usr/local/libexec/cowork-shared-firewall.py', 'restore')
        previous = json.loads((directory / 'credentials.json').read_text()) if (directory / 'credentials.json').exists() else {}
        if service == 'kafka' and previous:
            demand(previous.get('KAFKA_CLUSTER_ID') == secrets['KAFKA_CLUSTER_ID'], 'Kafka cluster identity cannot change during a normal apply')
        render(service, value, secrets, network, directory)
        compose = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(directory / 'compose.json')]
        run(*compose, 'config', '--quiet')
        run('docker', 'pull', value['image'])
        if service in {'redis', 'kafka'}:
            run('docker', 'volume', 'create', f'cowork-{service}-data')
        if service == 'kafka':
            # The official Kafka image runs as appuser and cannot initialize a root-owned empty external volume.
            run('docker', 'run', '--rm', '--network', 'none', '--user', 'root', '--entrypoint', '/bin/sh',
                '--mount', 'type=volume,source=cowork-kafka-data,target=/var/lib/kafka/data,volume-nocopy',
                value['image'], '-c', 'chown appuser:appuser /var/lib/kafka/data')
        run(*compose, 'up', '-d', '--wait', '--wait-timeout', '300', '--pull', 'never')
        if service == 'kafka':
            meta = run('docker', 'exec', 'cowork-kafka', 'cat', '/var/lib/kafka/data/meta.properties')
            demand('cluster.id=' + secrets['KAFKA_CLUSTER_ID'] in meta.splitlines(), 'Kafka data has a different cluster identity')
            provision_topics(kafka_policies(directory))
        write(directory / 'credentials.json', json.dumps(secrets) + '\n')
    print(f'{service}: dedicated service ready; persistent data retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'policy', 'apply'))
    parser.add_argument('service', nargs='?')
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    values, network = catalog()
    if args.operation == 'check':
        print(f'Validated {len(values)} dedicated shared services; no runtime changes')
        return
    demand(args.service in values, 'Select a dedicated shared service')
    value = values[args.service]
    if args.operation == 'policy':
        print(json.dumps({'network': network, **{k: value[k] for k in POLICY_KEYS}}))
        return
    directory = args.directory or Path('/root/cowork-iac/shared') / args.service
    apply(args.service, value, network, directory.resolve())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, IndexError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'[shared-service] {error}', file=sys.stderr)
        sys.exit(1)
