#!/usr/bin/env python3
"""Deploy shared Redis, Elasticsearch and Kafka without replacing their data."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent
DEPLOY = SOURCE.parents[1]
BOOTSTRAP = Path('/etc/cowork/datastores-bootstrap.json')
NETWORK = 'cowork-server_default'
PROJECT = 'cowork-datastores'
ACCOUNT_ID = 10002
PORTS = {'redis': 6379, 'elasticsearch': 9200, 'kafka': 9094}
VOLUMES = {
    'redis': {'cowork-prod-redis-data': '/data'},
    'elasticsearch': {'cowork-prod-elasticsearch-data': '/usr/share/elasticsearch/data'},
    'kafka': {'cowork-kafka-data-20261013-fresh': '/var/lib/kafka/data'},
}
REPOSITORIES = {'redis': 'redis', 'kafka': 'apache/kafka'}
SNAPSHOT = None
COMMAND_ENV = None
PHASE = 'argument-validation'


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    value = {}
    for key, child in pairs:
        demand(key not in value, 'Duplicate datastore settings field')
        value[key] = child
    return value


def exact_keys(value, keys, message):
    demand(isinstance(value, dict) and set(value) == set(keys), message)


def write(path, content):
    with path.open('xb') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(content if isinstance(content, bytes) else content.encode())


def run(*args, input_text=None, timeout=600):
    result = subprocess.run(args, env=COMMAND_ENV, input=input_text, text=True,
                            capture_output=True, timeout=timeout)
    if result.returncode:
        diagnostics = Path.home() / '.local/state/cowork/datastores-diagnostics'
        diagnostics.mkdir(parents=True, mode=0o700, exist_ok=True)
        info = diagnostics.lstat()
        demand(stat.S_ISDIR(info.st_mode) and info.st_uid == ACCOUNT_ID and not info.st_mode & 0o077,
               'Datastore diagnostics must be private')
        failure = diagnostics / (SNAPSHOT.name + '-' + PHASE + '.log')
        write(failure, (result.stdout + '\n' + result.stderr)[-20 * 1024 * 1024:])
        print(f'[datastores] Private failure diagnostics: {failure}', file=sys.stderr)
        raise RuntimeError('Datastore command failed')
    return result.stdout


def settings():
    value = json.loads(os.environ['DATASTORE_SETTINGS_JSON'], object_pairs_hook=unique_object)
    exact_keys(value, ('bind_ip', 'kafka'), 'Unexpected shared datastore settings fields')
    demand(isinstance(value['bind_ip'], str), 'Private binding must be an IPv4 string')
    address = ipaddress.ip_address(value['bind_ip'])
    demand(address.version == 4 and any(address in ipaddress.ip_network(n) for n in
                                       ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')),
           'Datastores must bind a private IPv4 address')
    value['bind_ip'] = str(address)
    exact_keys(value['kafka'], ('cluster_id',), 'Unexpected Kafka settings fields')
    demand(isinstance(value['kafka']['cluster_id'], str) and
           re.fullmatch(r'[A-Za-z0-9_-]{22}', value['kafka']['cluster_id']), 'Invalid Kafka cluster ID')
    return value


def root_file(path):
    for directory in (path.parent,):
        info = directory.lstat()
        demand(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
               'Datastore bootstrap directory must be root-owned and not writable by CD')
    info = path.lstat()
    demand(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022,
           'Datastore bootstrap manifest must be a root-owned regular file')
    return json.loads(path.read_text(), object_pairs_hook=unique_object)


def pinned_image(reference, repository):
    match = re.fullmatch(re.escape(repository) + r':([0-9]+)\.[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}', reference)
    demand(match is not None, 'An engine image must use the official repository and a versioned digest')
    return match.group(1)


def source_configuration():
    # Compose parses YAML; no optional Python YAML dependency or partial YAML parser.
    stack = json.loads(run('docker', 'compose', '--project-directory', str(SNAPSHOT), '--env-file', '/dev/null',
                           '-f', str(DEPLOY / 'compose/stack.yaml'), 'config', '--no-interpolate',
                           '--no-env-resolution', '--format', 'json'))['services']
    images = {name: stack[name]['image'] for name in REPOSITORIES}
    majors = {name: pinned_image(image, REPOSITORIES[name]) for name, image in images.items()}
    dockerfile = DEPLOY / 'images/elasticsearch/Dockerfile'
    content = dockerfile.read_text()
    first = content.splitlines()[0]
    demand(first.startswith('FROM '), 'Elasticsearch requires a pinned base image')
    majors['elasticsearch'] = pinned_image(first.removeprefix('FROM '), 'docker.elastic.co/elasticsearch/elasticsearch')
    # Content-addressed image naming avoids replacement on a new release SHA when
    # the actual Elasticsearch build inputs have not changed.
    digest = hashlib.sha256(content.encode()).hexdigest()
    images['elasticsearch'] = 'cowork-elasticsearch:prod-' + digest
    environment = stack['kafka-init']['environment']
    topics = environment['KAFKA_TOPICS'].split()
    compacted = environment['KAFKA_COMPACTED_TOPICS'].split()
    demand(topics and len(topics) == len(set(topics)) and len(compacted) == len(set(compacted)) and
           set(compacted) <= set(topics), 'Invalid source topic catalog')
    demand(all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,248}', topic) for topic in topics), 'Invalid source topic name')
    # Existing partition contracts stay fixed. A repartitioning change requires
    # a separately reviewed topic version and dataset procedure.
    demand(environment['KAFKA_TOPIC_PARTITIONS'] == '${KAFKA_TOPIC_PARTITIONS:-1}' and
           environment['KAFKA_TOPIC_REPLICATION_FACTOR'] == '${KAFKA_TOPIC_REPLICATION_FACTOR:-1}',
           'Review partition and replication changes before deploying datastores')
    command = '\n'.join(stack['kafka-init']['command']).replace('\\\n', ' ')
    retention = re.search(r"--entity-name\s+chat\.message\.v2\s+--add-config\s+'cleanup\.policy=delete,retention\.ms=([0-9]+)'", command)
    demand(retention is not None and command.count('--add-config') == 2 and
           '--add-config cleanup.policy=compact' in command, 'Review changed Kafka provisioning policy')
    policies = {topic: {'cleanup.policy': 'compact' if topic in compacted else 'delete'} for topic in topics}
    demand('chat.message.v2' in policies and 'chat.message.v2' not in compacted, 'Invalid Chat topic policy')
    policies['chat.message.v2']['retention.ms'] = retention.group(1)
    return images, majors, policies, dockerfile


def bootstrap_state(value, majors):
    bootstrap = root_file(BOOTSTRAP)
    exact_keys(bootstrap, ('schema_version', 'network', 'volumes', 'kafka_cluster_id', 'engines'), 'Invalid bootstrap manifest')
    demand(type(bootstrap['schema_version']) is int and bootstrap['schema_version'] == 1, 'Unsupported bootstrap version')
    exact_keys(bootstrap['network'], ('name', 'id'), 'Invalid bootstrap network')
    demand(bootstrap['network']['name'] == NETWORK and re.fullmatch(r'[0-9a-f]{64}', bootstrap['network']['id']),
           'Unexpected bootstrap network')
    demand(bootstrap['kafka_cluster_id'] == value['kafka']['cluster_id'], 'Kafka cluster ID differs from initialized data')
    demand(isinstance(bootstrap['engines'], dict) and set(PORTS) <= set(bootstrap['engines']),
           'A shared engine is missing from the bootstrap manifest')
    for name in PORTS:
        engine = bootstrap['engines'][name]
        exact_keys(engine, ('major', 'bootstrap_container_id', 'bootstrap_image_id'), 'Invalid bootstrap engine identity')
        demand(engine['major'] == majors[name], 'Engine major change requires an operator data-compatibility review')
        demand(re.fullmatch(r'[0-9a-f]{64}', engine['bootstrap_container_id']) and
               re.fullmatch(r'sha256:[0-9a-f]{64}', engine['bootstrap_image_id']), 'Invalid bootstrap container identity')
    expected_volumes = {volume for volumes in VOLUMES.values() for volume in volumes}
    demand(isinstance(bootstrap['volumes'], dict) and expected_volumes <= set(bootstrap['volumes']),
           'A shared data volume is missing from the bootstrap manifest')
    live = json.loads(run('docker', 'volume', 'inspect', *sorted(expected_volumes)))
    demand(len(live) == len(expected_volumes), 'A required initialized data volume is missing')
    for volume in live:
        expected = bootstrap['volumes'][volume['Name']]
        exact_keys(expected, ('created_at', 'driver', 'mountpoint'), 'Invalid registered volume identity')
        demand(expected['driver'] == 'local' and expected['mountpoint'] ==
               '/var/lib/docker/volumes/' + volume['Name'] + '/_data', 'Unexpected registered volume location')
        demand(volume['CreatedAt'] == expected['created_at'] and volume['Driver'] == expected['driver'] and
               volume['Mountpoint'] == expected['mountpoint'], 'An initialized data volume was replaced or moved')
    network = json.loads(run('docker', 'network', 'inspect', NETWORK))[0]
    demand(network['Id'] == bootstrap['network']['id'] and network['Driver'] == 'bridge', 'The registered network changed')
    # An unrelated container must never share an engine's writable data volume.
    all_ids = run('docker', 'ps', '-aq', '--no-trunc').splitlines()
    containers = json.loads(run('docker', 'inspect', *all_ids)) if all_ids else []
    for container in containers:
        for mount in container['Mounts']:
            volume = mount.get('Name')
            if mount['Type'] == 'volume' and volume in expected_volumes:
                owner = next(name for name, volumes in VOLUMES.items() if volume in volumes)
                demand(container['Name'] == '/cowork-' + owner and mount['Destination'] == VOLUMES[owner][volume],
                       'A registered data volume has an unexpected container attachment')
    return bootstrap, containers


def render(value, images):
    # raw env_file (Compose >=2.30) preserves $, quotes, # and spaces in secrets.
    # There are no init-script mounts. Initialization remains an operator task.
    environment = {
        'kafka': {'KAFKA_NODE_ID': '1', 'KAFKA_PROCESS_ROLES': 'broker,controller',
                  'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:9094',
                  'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://kafka:9092,EXTERNAL://' + value['bind_ip'] + ':9094',
                  'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT',
                  'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@kafka:9093', 'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
                  'KAFKA_LOG_DIRS': '/var/lib/kafka/data', 'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'false',
                  'KAFKA_DELETE_TOPIC_ENABLE': 'false', 'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1',
                  'CLUSTER_ID': value['kafka']['cluster_id']},
    }
    for engine, variables in environment.items():
        write(SNAPSHOT / (engine + '.env'), ''.join(key + '=' + val + '\n' for key, val in variables.items()))
    variables = {'DATASTORE_BIND_IP': value['bind_ip'], **{name.upper() + '_IMAGE': image for name, image in images.items()}}
    write(SNAPSHOT / 'compose.env', ''.join(key + '=' + val + '\n' for key, val in variables.items()))
    return environment


def validate_compose(compose, value, images, environment):
    model = json.loads(run(*compose, 'config', '--format', 'json'))
    exact_keys(model['services'], PORTS, 'Unexpected production datastore services')
    demand(set(model['networks']) == {'datastores'}, 'Unexpected production network list')
    network = model['networks']['datastores']
    demand(network.get('name') == NETWORK and network.get('external') is True and not network.get('ipam') and
           set(network) <= {'name', 'external', 'ipam'}, 'Unexpected production network')
    demand({v['name'] for v in model['volumes'].values()} == {v for values in VOLUMES.values() for v in values} and
           all(v.get('external') is True for v in model['volumes'].values()), 'All data volumes must already exist externally')
    for name, service in model['services'].items():
        demand(service['image'] == images[name] and service['container_name'] == 'cowork-' + name, 'Unexpected engine image or name')
        ports = service['ports']
        demand(len(ports) == 1 and ports[0]['host_ip'] == value['bind_ip'] and ports[0]['target'] == PORTS[name] and
               int(ports[0]['published']) == PORTS[name] and ports[0]['protocol'] == 'tcp', 'Unexpected engine port binding')
        demand(service['networks'] == {'datastores': {'aliases': [name]}}, 'Unexpected engine network alias')
        actual = {}
        for mount in service['volumes']:
            demand(mount['type'] == 'volume' and mount.get('volume', {}).get('nocopy') is True,
                   'Normal CD permits registered data volumes only')
            actual[model['volumes'][mount['source']]['name']] = mount['target']
        demand(actual == VOLUMES[name], 'Unexpected persistent data mapping')
        if name in environment:
            demand(service['environment'] == environment[name], 'Compose altered a runtime credential or engine setting')


def require_compose_ownership(containers):
    for container in containers:
        if container['Name'].removeprefix('/cowork-') not in PORTS:
            continue
        labels = container['Config'].get('Labels') or {}
        demand(labels.get('com.docker.compose.project') == PROJECT and
               labels.get('com.docker.compose.service') == container['Name'].removeprefix('/cowork-'),
               'Operator adoption is required before normal CD can replace an unmanaged engine')


def verify_runtime(bootstrap, value, images):
    for name in PORTS:
        container = json.loads(run('docker', 'inspect', 'cowork-' + name))[0]
        demand(container['Config']['Image'] == images[name], 'An engine is using an unexpected image')
        demand(container['State']['Running'] and container['State'].get('Health', {}).get('Status') == 'healthy',
               'A required engine did not pass readiness')
        labels = container['Config'].get('Labels') or {}
        demand(labels.get('com.docker.compose.project') == PROJECT and labels.get('com.docker.compose.service') == name,
               'An engine is outside the datastore Compose project')
        mounts = {m['Name']: m['Destination'] for m in container['Mounts'] if m['Type'] == 'volume' and m['Name'] in VOLUMES[name]}
        demand(mounts == VOLUMES[name], 'An engine does not use its registered data volume')
        network = container['NetworkSettings']['Networks'].get(NETWORK, {})
        demand(set(container['NetworkSettings']['Networks']) == {NETWORK} and
               network.get('NetworkID') == bootstrap['network']['id'], 'Engine network identity changed')
        demand(container['HostConfig']['PortBindings'] == {str(PORTS[name]) + '/tcp': [
            {'HostIp': value['bind_ip'], 'HostPort': str(PORTS[name])}]}, 'Engine port binding differs from installed policy')
    meta = run('docker', 'exec', 'cowork-kafka', 'cat', '/var/lib/kafka/data/meta.properties')
    demand('cluster.id=' + value['kafka']['cluster_id'] in meta.splitlines(), 'Kafka is using an unexpected cluster')


def topic_description():
    text = run('docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-topics.sh',
               '--bootstrap-server', 'localhost:9092', '--describe')
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


def validate_topics(descriptions, policies, require_all):
    for topic, desired in policies.items():
        if topic not in descriptions:
            demand(not require_all, 'A required topic is missing after provisioning')
            continue
        partitions, replication, configs = descriptions[topic]
        demand(partitions == 1 and replication == 1, 'A topic has a different partition or replication contract')
        for key, expected in desired.items():
            actual = re.search(r'(?:^|,)\s*' + re.escape(key) + r'=([^,\s]+)', configs)
            if actual is None:
                # Describe effective defaults only when the topic summary did not
                # include the setting. Never repair a policy automatically.
                effective = run('docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-configs.sh',
                                '--bootstrap-server', 'localhost:9092', '--entity-type', 'topics',
                                '--entity-name', topic, '--describe', '--all')
                actual = re.search(r'(?:^|\s)' + re.escape(key) + r'=([^\s]+)', effective, re.MULTILINE)
            demand(actual is not None and actual.group(1) == expected,
                   'Existing Kafka topic policy differs; explicit recovery review is required')


def provision_topics(policies):
    before = topic_description()
    validate_topics(before, policies, require_all=False)
    for topic, policy in policies.items():
        if topic in before:
            continue
        args = ['docker', 'exec', 'cowork-kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                'localhost:9092', '--create', '--if-not-exists', '--topic', topic, '--partitions', '1', '--replication-factor', '1']
        for key, value in policy.items():
            args.extend(('--config', key + '=' + value))
        run(*args)
    validate_topics(topic_description(), policies, require_all=True)
    print(f'[datastores] Verified {len(policies)} required topics; created {len(set(policies) - set(before))} missing topics')


def main():
    global SNAPSHOT, COMMAND_ENV, PHASE
    demand(len(sys.argv) == 2 and sys.argv[1] in ('check', 'deploy'), 'Datastore action must be check or deploy')
    demand(os.geteuid() == ACCOUNT_ID and os.getegid() == ACCOUNT_ID,
           'Datastore CD requires cowork-datastores UID/GID 10002')
    demand(os.environ['DEPLOY_TARGET'] == 'datastores' and re.fullmatch(r'[0-9a-f]{40}', os.environ['DEPLOY_SHA']),
           'Datastore CD requires an immutable release target')
    os.umask(0o077)
    SNAPSHOT = Path(os.environ['DEPLOY_SETTINGS_DIR'])
    info = SNAPSHOT.lstat()
    expected = Path.home() / '.local/state/cowork/settings/datastores'
    demand(stat.S_ISDIR(info.st_mode) and info.st_uid == ACCOUNT_ID and not info.st_mode & 0o077 and
           SNAPSHOT.resolve().parent == expected.resolve(), 'A private datastore snapshot is required')
    COMMAND_ENV = {key: os.environ[key] for key in ('HOME', 'PATH', 'LANG', 'DOCKER_CONFIG') if key in os.environ}
    PHASE = 'settings-validation'
    value = settings()
    version = run('docker', 'compose', 'version', '--short').strip().removeprefix('v')
    parsed = re.match(r'([0-9]+)\.([0-9]+)\.([0-9]+)', version)
    demand(parsed is not None and tuple(map(int, parsed.groups())) >= (2, 30, 0), 'Docker Compose 2.30 or later is required')
    PHASE = 'source-configuration'
    images, majors, policies, dockerfile = source_configuration()
    PHASE = 'initialized-volume-validation'
    bootstrap, containers = bootstrap_state(value, majors)
    PHASE = 'private-snapshot-rendering'
    environment = render(value, images)
    PHASE = 'installed-firewall-validation'
    run('sudo', '-n', '/usr/bin/python3', '-I', '/usr/local/libexec/cowork-datastore-firewall.py', 'check-binding',
        input_text=json.dumps({'bind_ip': value['bind_ip'], 'bindings': [{'host_port': p, 'container_port': p} for p in PORTS.values()],
                               'network': NETWORK}))
    compose = ['docker', 'compose', '--project-directory', str(SNAPSHOT), '--env-file', str(SNAPSHOT / 'compose.env'),
               '-p', PROJECT, '-f', str(SOURCE / 'compose.yaml')]
    PHASE = 'compose-validation'
    validate_compose(compose, value, images, environment)
    if sys.argv[1] == 'check':
        print('[datastores] Settings, Compose, registered volumes and installed ingress policy are valid; no engines changed')
        return
    PHASE = 'compose-ownership-validation'
    require_compose_ownership(containers)
    PHASE = 'image-pull'
    for name in REPOSITORIES:
        run('docker', 'pull', images[name])
    PHASE = 'elasticsearch-build'
    run('docker', 'build', '--pull', '--file', str(dockerfile), '--tag', images['elasticsearch'], str(dockerfile.parent))
    PHASE = 'pre-replacement-validation'
    bootstrap, containers = bootstrap_state(value, majors)
    require_compose_ownership(containers)
    PHASE = 'engine-deployment'
    run(*compose, 'up', '-d', '--wait', '--wait-timeout', '240', '--no-build', '--pull', 'never')
    PHASE = 'engine-readiness'
    verify_runtime(bootstrap, value, images)
    PHASE = 'topic-provisioning'
    provision_topics(policies)
    print('[datastores] Shared engines passed readiness; initialized volumes and Kafka cluster identity were retained')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, IndexError, subprocess.TimeoutExpired):
        print(f'[datastores] Failed during {PHASE}; inspect the VM state before retrying', file=sys.stderr)
        sys.exit(1)
