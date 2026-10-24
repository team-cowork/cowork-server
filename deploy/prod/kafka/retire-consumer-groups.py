#!/usr/bin/env python3
"""Retire declared Kafka groups after their replacements catch up; dry-run by default."""
import argparse
import fcntl
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone


NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,248}\Z")
FIELDS = {"consumer_service", "old_group", "old_topic", "new_group", "new_topic"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def absolute_path(value):
    path = Path(value)
    if not path.is_absolute():
        raise argparse.ArgumentTypeError("path must be absolute")
    return path


def load_catalog(path):
    document = json.loads(path.read_text())
    require(isinstance(document, dict) and set(document) == {"schema_version", "retirements"},
            "Invalid retirement catalog fields")
    require(type(document["schema_version"]) is int and document["schema_version"] == 1,
            "Unsupported retirement catalog version")
    entries = document["retirements"]
    require(isinstance(entries, list) and entries, "Retirement catalog must contain entries")
    old_groups = set()
    for entry in entries:
        require(isinstance(entry, dict) and set(entry) == FIELDS, "Invalid retirement entry fields")
        require(all(isinstance(value, str) and NAME.fullmatch(value) for value in entry.values()),
                "Catalog identifiers must be literal Kafka/service names")
        require(re.fullmatch(r"[a-z][a-z0-9-]*", entry["consumer_service"]), "Invalid consumer service")
        require(entry["old_group"] != entry["new_group"], "Replacement group must differ")
        require(entry["old_group"] not in old_groups, "Duplicate retired group")
        old_groups.add(entry["old_group"])
    require(not old_groups.intersection(entry["new_group"] for entry in entries),
            "A replacement group cannot also be retired")
    return entries


class Kafka:
    def __init__(self, args):
        self.args = args
        self.trace = []

    def call(self, tool, *arguments):
        command = ["docker", "exec", "--env", "LC_ALL=C", self.args.container,
                   f"/opt/kafka/bin/kafka-{tool}.sh", "--bootstrap-server", self.args.bootstrap_server,
                   *arguments]
        invocation = {"at": timestamp(), "command": command}
        self.trace.append(invocation)
        result = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
        invocation.update(exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)
        # Some Kafka CLI failures print Error and still exit zero.
        require(result.returncode == 0 and not re.search(
            r"(?im)^\s*(?:Error\b|Exception\b|.*\bERROR\b|Skip getting offsets)",
            result.stdout + "\n" + result.stderr),
            f"Kafka {tool} command failed: {result.stdout.strip()} {result.stderr.strip()}")
        return result.stdout

    def groups(self):
        names = self.call("consumer-groups", "--list").splitlines()
        names = [name.strip() for name in names if name.strip()]
        require(all(NAME.fullmatch(name) for name in names), "Unrecognized group list output")
        return set(names)

    def state(self, group):
        lines = self.call("consumer-groups", "--describe", "--group", group, "--state").splitlines()
        lines = [line.split() for line in lines if line.strip()]
        require(len(lines) == 2 and lines[0][0] == "GROUP"
                and lines[0][-2:] == ["STATE", "#MEMBERS"] and lines[1][0] == group,
                f"Unrecognized state output for {group}")
        members = int(lines[1][-1])
        require(members >= 0, f"Invalid member count for {group}")
        return {"state": lines[1][-2], "members": members}

    def offsets(self, group, topic):
        lines = self.call("consumer-groups", "--describe", "--group", group, "--offsets").splitlines()
        rows = [line.split() for line in lines if line.strip()]
        expected = ["GROUP", "TOPIC", "PARTITION", "CURRENT-OFFSET", "LOG-END-OFFSET",
                    "LAG", "CONSUMER-ID", "HOST", "CLIENT-ID"]
        require(len(rows) > 1 and rows[0] == expected, f"Missing or unrecognized offsets for {group}")
        offsets = {}
        for values in rows[1:]:
            require(len(values) == len(expected), f"Malformed offset row for {group}")
            row = dict(zip(expected, values))
            require(row["GROUP"] == group and row["TOPIC"] == topic,
                    f"{group} contains offsets outside declared topic {topic}")
            partition, offset = int(row["PARTITION"]), int(row["CURRENT-OFFSET"])
            require(partition >= 0 and offset >= 0 and partition not in offsets,
                    f"Missing, negative or duplicate committed offset for {group}")
            offsets[partition] = {"offset": offset, "consumer_id": row["CONSUMER-ID"]}
        require(offsets, f"No committed offsets for {group}")
        return offsets

    def topic(self, topic):
        output = self.call("topics", "--describe", "--topic", "^" + re.escape(topic) + "$")
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        summaries = [line for line in lines if "PartitionCount:" in line]
        require(len(summaries) == 1, f"Missing topic description for {topic}")
        identity = re.search(r"\bTopicId:\s+([A-Za-z0-9_-]{22})\b", summaries[0])
        count = re.search(r"\bPartitionCount:\s+(\d+)\b", summaries[0])
        require(identity and identity.group(1) != "A" * 22 and count,
                f"Missing topic UUID/partition count for {topic}")
        partitions = []
        for line in lines:
            name = re.match(r"Topic:\s+(\S+)", line)
            require(name and name.group(1) == topic, f"Unexpected topic in description of {topic}")
            partition = re.search(r"\bPartition:\s+(\d+)\b", line)
            if partition:
                partitions.append(int(partition.group(1)))
        require(int(count.group(1)) > 0 and sorted(partitions) == list(range(int(count.group(1)))),
                f"Incomplete partition metadata for {topic}")
        return {"uuid": identity.group(1), "partitions": sorted(partitions)}

    def bounds(self, topic, when, partitions):
        output = self.call("get-offsets", "--topic", "^" + re.escape(topic) + "$", "--time", when)
        offsets = {}
        for line in output.splitlines():
            if not line.strip():
                continue
            fields = line.strip().split(":")
            require(len(fields) == 3 and fields[0] == topic, f"Unexpected offset output for {topic}")
            partition, offset = int(fields[1]), int(fields[2])
            require(partition >= 0 and offset >= 0 and partition not in offsets,
                    f"Invalid {when} offset for {topic}")
            offsets[partition] = offset
        require(set(offsets) == set(partitions), f"Incomplete {when} offsets for {topic}")
        return offsets


def observe(kafka, entry, baseline):
    old_group, new_group = entry["old_group"], entry["new_group"]
    old_topic, new_topic = entry["old_topic"], entry["new_topic"]
    new_state = kafka.state(new_group)
    require(new_state["state"] == "Stable" and new_state["members"] > 0,
            f"Replacement group {new_group} must be Stable with active members")
    new_offsets = kafka.offsets(new_group, new_topic)
    partitions = baseline["new_topic"]["partitions"]
    require(set(new_offsets) == set(partitions), f"Incomplete assignment/offsets for {new_group}")
    low = kafka.bounds(new_topic, "earliest", partitions)
    high = kafka.bounds(new_topic, "latest", partitions)
    for partition in partitions:
        row = new_offsets[partition]
        require(row["consumer_id"] != "-", f"{new_topic}[{partition}] is not currently assigned")
        require(low[partition] < high[partition], f"{new_topic}[{partition}] is empty")
        require(low[partition] <= row["offset"] <= high[partition]
                and row["offset"] >= baseline["high_watermarks"][partition],
                f"{new_group} has not reached the captured watermark for partition {partition}")
    require(kafka.topic(old_topic) == baseline["old_topic"]
            and kafka.topic(new_topic) == baseline["new_topic"], "Topic identity or partitions changed")
    require(kafka.state(new_group) == new_state, f"Replacement group {new_group} changed during observation")
    old_offsets = kafka.offsets(old_group, old_topic)
    require(old_offsets == baseline["old_offsets"], f"Retired group {old_group} offsets changed")
    require(all(row["consumer_id"] == "-" for row in old_offsets.values()),
            f"Retired group {old_group} still has assigned partitions")
    old_state = kafka.state(old_group)
    require(old_state == {"state": "Empty", "members": 0}, f"Retired group {old_group} is not Empty")
    return {"at": timestamp(), "old_state": old_state, "old_offsets": old_offsets,
            "new_state": new_state, "new_offsets": new_offsets, "earliest": low, "latest": high}


def save_backup(directory, path, document):
    with tempfile.NamedTemporaryFile(mode="w", dir=directory, prefix="retirement-", suffix=".json",
                                     encoding="utf-8", delete=False) as output:
        temporary = Path(output.name)
        json.dump(document, output, ensure_ascii=False, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    if path is not None:
        os.replace(temporary, path)
    else:
        path = temporary
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return path


def retire(kafka, entry, args):
    old_group = entry["old_group"]
    kafka.trace = []
    if old_group not in kafka.groups():
        print(json.dumps({"old_group": old_group, "status": "already-absent"}), flush=True)
        return
    record = {"started_at": timestamp(), "retirement": entry, "apply": args.apply,
              "status": "checking", "observations": [], "commands": kafka.trace}
    backup = None
    try:
        baseline = {"old_topic": kafka.topic(entry["old_topic"]),
                    "new_topic": kafka.topic(entry["new_topic"]),
                    "old_offsets": kafka.offsets(old_group, entry["old_topic"])}
        baseline["earliest"] = kafka.bounds(entry["new_topic"], "earliest", baseline["new_topic"]["partitions"])
        baseline["high_watermarks"] = kafka.bounds(entry["new_topic"], "latest", baseline["new_topic"]["partitions"])
        record["baseline"] = baseline
        require(all(baseline["earliest"][partition] < high
                    for partition, high in baseline["high_watermarks"].items()), "Replacement topic has an empty partition")
        record["observations"].append(observe(kafka, entry, baseline))
        time.sleep(args.observation_interval)
        record["observations"].append(observe(kafka, entry, baseline))
        record["status"] = "eligible"
        backup = save_backup(args.state_directory, backup, record)
        if args.apply:
            record["observations"].append(observe(kafka, entry, baseline))
            record["status"] = "deleting"
            backup = save_backup(args.state_directory, backup, record)
            output = kafka.call("consumer-groups", "--delete", "--group", old_group)
            require(f"Deletion of requested consumer groups ('{old_group}') was successful." in output,
                    f"Kafka did not confirm deletion of {old_group}")
            require(old_group not in kafka.groups(), f"Retired group {old_group} still exists after deletion")
            record["status"] = "deleted"
        else:
            record["status"] = "dry-run"
        save_backup(args.state_directory, backup, record)
        print(json.dumps({"old_group": old_group, "status": record["status"], "backup": str(backup)}), flush=True)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        record.update(status="failed", error=str(error))
        backup = save_backup(args.state_directory, backup, record)
        raise ValueError(f"{old_group}: {error}; backup={backup}") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", help="Consumer service whose declared retirements may be processed")
    parser.add_argument("--apply", action="store_true", help="Delete eligible groups (default: dry-run)")
    parser.add_argument("--catalog", type=absolute_path, default=Path(__file__).resolve().with_name("retirements.json"))
    parser.add_argument("--state-directory", type=absolute_path, default=Path("/var/lib/cowork/kafka-retirements"))
    parser.add_argument("--observation-interval", type=float, default=15, help="Seconds between healthy observations (default: 15)")
    parser.add_argument("--container", default="cowork-kafka")
    parser.add_argument("--bootstrap-server", default="localhost:9092")
    parser.add_argument("--validate-catalog", action="store_true", help="Validate the catalog without contacting Kafka")
    args = parser.parse_args()
    entries = load_catalog(args.catalog)
    if args.validate_catalog:
        require(not args.apply, "--validate-catalog cannot be combined with --apply")
        print(json.dumps({"status": "valid", "retirements": len(entries)}))
        return
    require(args.service is not None and re.fullmatch(r"[a-z][a-z0-9-]*", args.service), "--service is required")
    require(math.isfinite(args.observation_interval) and args.observation_interval > 0,
            "Observation interval must be positive and finite")
    require(NAME.fullmatch(args.container), "Invalid Kafka container name")
    require(re.fullmatch(r"[A-Za-z0-9._-]+:[0-9]{1,5}", args.bootstrap_server), "Invalid bootstrap server")
    selected = [entry for entry in entries if entry["consumer_service"] == args.service]
    if not selected:
        print(json.dumps({"status": "no-retirements", "service": args.service}))
        return
    args.state_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (args.state_directory / "retirements.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        kafka = Kafka(args)
        for entry in selected:
            retire(kafka, entry, args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "failed", "error": str(error)}), file=sys.stderr)
        sys.exit(1)
