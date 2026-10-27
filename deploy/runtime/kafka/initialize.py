"""Reconcile the repository's Kafka topics, SCRAM users and scoped ACLs."""

import json
import logging
import sys
import time
from pathlib import Path

from confluent_kafka import KafkaError, KafkaException
from confluent_kafka.admin import (
    AclBinding,
    AclBindingFilter,
    AclOperation,
    AclPermissionType,
    AdminClient,
    AlterConfigOpType,
    ConfigEntry,
    ConfigResource,
    NewTopic,
    ResourcePatternType,
    ResourceType,
    ScramCredentialInfo,
    ScramMechanism,
    UserScramCredentialUpsertion,
)

POLICY_FILE = Path("/local/kafka/policy.json")
CREDENTIALS_FILE = Path("/secrets/credentials.json")
READY_FILE = Path("/alloc/data/bootstrap-ready")
REQUEST_TIMEOUT = 60


def await_results(futures):
    for future in futures.values():
        future.result(timeout=REQUEST_TIMEOUT + 5)


def load_configuration():
    policy = json.loads(POLICY_FILE.read_text())
    credentials = json.loads(CREDENTIALS_FILE.read_text())
    expected = set(policy["principals"]) | {"cowork-monitoring", "broker"}
    if not isinstance(credentials, dict) or set(credentials) != expected:
        raise ValueError("Credential principals must match the repository policy")
    if any(not isinstance(value, str) or len(value) < 16 for value in credentials.values()):
        raise ValueError("Every SCRAM credential must contain at least 16 characters")
    for principal, grant in policy["principals"].items():
        if not principal.startswith("cowork-"):
            raise ValueError("Application principals must use the cowork namespace")
        if not set(grant["topics"]) <= set(policy["topics"]):
            raise ValueError("Application ACL references an undeclared topic")
    return policy, credentials


def wait_for_broker(admin):
    deadline = time.monotonic() + 180
    while True:
        try:
            metadata = admin.list_topics(timeout=5)
            if metadata.brokers:
                return
        except KafkaException:
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError("Kafka broker did not become available")
        time.sleep(2)


def reconcile_topics(admin, topics):
    creations = [
        NewTopic(name, num_partitions=spec["partitions"], replication_factor=1, config=spec["config"])
        for name, spec in topics.items()
    ]
    for future in admin.create_topics(creations, request_timeout=REQUEST_TIMEOUT).values():
        try:
            future.result(timeout=REQUEST_TIMEOUT + 5)
        except KafkaException as error:
            if error.args[0].code() != KafkaError.TOPIC_ALREADY_EXISTS:
                raise
    metadata = admin.list_topics(timeout=30)
    for name, spec in topics.items():
        topic = metadata.topics.get(name)
        if topic is None or topic.error or len(topic.partitions) != spec["partitions"]:
            raise ValueError("Existing Kafka topic partitions differ from the repository policy")
    resources = [
        ConfigResource(
            ResourceType.TOPIC,
            name,
            incremental_configs=[
                ConfigEntry(key, value, incremental_operation=AlterConfigOpType.SET)
                for key, value in spec["config"].items()
            ],
        )
        for name, spec in topics.items()
    ]
    await_results(admin.incremental_alter_configs(resources, request_timeout=REQUEST_TIMEOUT))


def desired_acls(policy):
    grants = set()

    def allow(principal, resource, name, operation, pattern=ResourcePatternType.LITERAL):
        grants.add(AclBinding(resource, name, pattern, "User:" + principal, "*", operation, AclPermissionType.ALLOW))

    for principal, specification in policy["principals"].items():
        for topic in specification["topics"]:
            for operation in (AclOperation.READ, AclOperation.WRITE, AclOperation.DESCRIBE):
                allow(principal, ResourceType.TOPIC, topic, operation)
        if specification["topics"]:
            for operation in (AclOperation.READ, AclOperation.DESCRIBE):
                allow(principal, ResourceType.GROUP, principal, operation, ResourcePatternType.PREFIXED)
            allow(principal, ResourceType.BROKER, "kafka-cluster", AclOperation.IDEMPOTENT_WRITE)
    # Monitoring reads metadata and offsets, never message payloads or commits.
    for operation in (AclOperation.DESCRIBE, AclOperation.DESCRIBE_CONFIGS):
        allow("cowork-monitoring", ResourceType.TOPIC, "*", operation)
    allow("cowork-monitoring", ResourceType.GROUP, "*", AclOperation.DESCRIBE)
    allow("cowork-monitoring", ResourceType.BROKER, "kafka-cluster", AclOperation.DESCRIBE)
    return grants


def reconcile_acls(admin, wanted):
    everything = AclBindingFilter(
        ResourceType.ANY, None, ResourcePatternType.ANY, None, None, AclOperation.ANY, AclPermissionType.ANY,
    )
    current = admin.describe_acls(everything, request_timeout=REQUEST_TIMEOUT).result(timeout=REQUEST_TIMEOUT + 5)
    managed = {binding for binding in current if binding.principal.startswith("User:cowork-") or binding.principal == "User:broker"}
    stale = managed - wanted
    if stale:
        filters = [
            AclBindingFilter(binding.restype, binding.name, binding.resource_pattern_type, binding.principal,
                             binding.host, binding.operation, binding.permission_type)
            for binding in stale
        ]
        await_results(admin.delete_acls(filters, request_timeout=REQUEST_TIMEOUT))
    missing = wanted - managed
    if missing:
        await_results(admin.create_acls(list(missing), request_timeout=REQUEST_TIMEOUT))


def main():
    phase = "configuration"
    try:
        READY_FILE.unlink(missing_ok=True)
        policy, credentials = load_configuration()
        # Prevent library diagnostics from including broker responses or credentials.
        logger = logging.getLogger("kafka-initializer")
        logger.addHandler(logging.NullHandler())
        logger.propagate = False
        admin = AdminClient({"bootstrap.servers": "127.0.0.1:9092", "client.id": "cowork-kafka-init", "logger": logger})
        phase = "broker readiness"
        wait_for_broker(admin)
        phase = "SCRAM credentials"
        changes = [
            UserScramCredentialUpsertion(
                user, ScramCredentialInfo(ScramMechanism.SCRAM_SHA_512, 16384), password.encode("utf-8"),
            )
            for user, password in credentials.items()
        ]
        await_results(admin.alter_user_scram_credentials(changes, request_timeout=REQUEST_TIMEOUT))
        phase = "topics"
        reconcile_topics(admin, policy["topics"])
        phase = "ACLs"
        reconcile_acls(admin, desired_acls(policy))
        READY_FILE.write_text("ready\n")
        print(f"Kafka initialized: {len(policy['topics'])} topics, {len(credentials)} principals; policy applied")
    except Exception as error:
        # Never render an exception containing a credential operation or its arguments.
        print(f"Kafka initialization failed during {phase} ({type(error).__name__})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
