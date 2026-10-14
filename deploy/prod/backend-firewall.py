#!/usr/bin/env python3
"""Restore a fixed private-IPv4 ingress policy on a dedicated backend VM."""
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import shlex
import stat
import subprocess
import sys
import tempfile
import uuid


STATE_DIR = Path("/etc/cowork/backend-firewall")
POLICY_FILE = STATE_DIR / "policy.json"
DESIRED_FILE = STATE_DIR / "desired.json"
TRANSACTION_FILE = STATE_DIR / "transaction.json"
CHAIN = "COWORK_BACKEND"
UNIT_NAME = "cowork-backend-firewall.service"
PRIVATE_NETWORKS = [ipaddress.ip_network(cidr) for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
MONITORING_BRIDGE_PEERS = {("br-cowork-mon", "br-cowork-mon"), ("br-cowork-log", "br-cowork-mon")}


def private_network(value):
    if not isinstance(value, str):
        raise ValueError("Backend firewall networks must be strings")
    network = ipaddress.ip_network(value, strict=True)
    if network.version != 4 or not any(network.subnet_of(parent) for parent in PRIVATE_NETWORKS):
        raise ValueError("Backend ingress requires explicit RFC1918 IPv4 networks")
    return network


def normalize_binding(value):
    if not isinstance(value, dict) or set(value) != {"address", "host_port", "container_port"}:
        raise ValueError("Invalid backend firewall binding")
    if not isinstance(value["address"], str):
        raise ValueError("Backend firewall addresses must be strings")
    address = str(ipaddress.IPv4Address(value["address"]))
    private_network(address)
    for name in ("host_port", "container_port"):
        if type(value[name]) is not int or not 1 <= value[name] <= 65535:
            raise ValueError("Invalid backend firewall port")
    return {**value, "address": address}


def policy(value):
    if not isinstance(value, dict) or set(value) not in ({"bindings", "peers"}, {"bindings", "peers", "bridge_peers"}):
        raise ValueError("Invalid backend firewall policy")
    bindings, peers = value["bindings"], value["peers"]
    if not isinstance(bindings, list) or not 1 <= len(bindings) <= 16:
        raise ValueError("Invalid backend firewall bindings")
    if not isinstance(peers, list) or not 1 <= len(peers) <= 256:
        raise ValueError("Invalid backend firewall peers")
    normalized_bindings = []
    for binding in bindings:
        normalized = normalize_binding(binding)
        if normalized not in normalized_bindings:
            normalized_bindings.append(normalized)
    result = {"bindings": normalized_bindings, "peers": sorted({str(private_network(peer)) for peer in peers})}
    bridge_peers = value.get("bridge_peers", [])
    if not isinstance(bridge_peers, list) or len(bridge_peers) > 2:
        raise ValueError("Invalid monitoring bridge peers")
    normalized_bridges = set()
    for pair in bridge_peers:
        if not isinstance(pair, dict) or set(pair) != {"source", "destination"}:
            raise ValueError("Invalid monitoring bridge pair")
        names = (pair["source"], pair["destination"])
        if not all(isinstance(name, str) for name in names) or names not in MONITORING_BRIDGE_PEERS:
            raise ValueError("Only the declared monitoring and local log-agent bridges may reach Loki")
        normalized_bridges.add(names)
    if normalized_bridges:
        if any(binding["host_port"] != 3100 or binding["container_port"] != 3100 for binding in normalized_bindings):
            raise ValueError("Local monitoring bridge access is restricted to the Loki binding")
        result["bridge_peers"] = [{"source": source, "destination": destination}
                                  for source, destination in sorted(normalized_bridges)]
    return result


def parse_document(content):
    if len(content) > 65536:
        raise ValueError("Backend firewall policy is too large")
    return json.loads(content)


def check_owned(path, directory=False, private=False):
    info = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(info.st_mode) or info.st_uid != 0 or info.st_mode & (0o077 if private else 0o022):
        raise ValueError("Backend firewall requires root-owned files and directories")


def command(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"Backend firewall command failed: {args[0]}")
    return result.stdout


def apply_policy(value):
    raw_lines = command("iptables", "-w", "10", "-S").splitlines()
    lines, jumps = [], []
    for raw_line in raw_lines:
        line = shlex.split(raw_line)
        lines.append(line)
        if "-j" in line and line[line.index("-j") + 1] == CHAIN:
            if not raw_line.startswith("-A ") or line[1] not in {"INPUT", "DOCKER-USER"}:
                raise ValueError("Backend firewall has an unexpected ingress hook")
            jumps.append(raw_line)
    rules = ["*filter"]
    if ["-N", "DOCKER-USER"] not in lines:
        rules.append(":DOCKER-USER - [0:0]")
    rules.extend([f":{CHAIN} - [0:0]", f"-F {CHAIN}"])
    # A bridge name alone never trusts a routed external packet. Both ingress
    # and egress must match the reviewed local monitoring topology.
    rules.extend(f"-A {CHAIN} -i {pair['source']} -o {pair['destination']} -j RETURN"
                 for pair in value.get("bridge_peers", []))
    rules.extend(f"-A {CHAIN} -s {peer} -j RETURN" for peer in value["peers"])
    rules.append(f"-A {CHAIN} -j DROP")
    # Only this helper's chain and ingress jumps are replaced. A single filter
    # table commit preserves Docker, SSH, database and other host firewall rules.
    # iptables -S is canonical restore syntax. Shell quoting would turn ! into
    # a literal quoted token and break deletion of the INPUT inversion rule.
    rules.extend("-D" + jump[2:] for jump in jumps)
    for binding in value["bindings"]:
        address, host_port = binding["address"], binding["host_port"]
        rules.append(
            "-I DOCKER-USER 1 -p tcp -m conntrack --ctdir ORIGINAL "
            f"--ctorigdst {address} --ctorigdstport {host_port} -j {CHAIN}"
        )
        # Local health checks use loopback routing. Exclude them from INPUT,
        # while restricting remote connections if a host listener owns the port.
        rules.append(
            f"-I INPUT 1 ! -i lo -d {address} -p tcp -m tcp --dport {host_port} "
            f"-m conntrack --ctdir ORIGINAL -j {CHAIN}"
        )
    # Direct routing to container IPs does not retain the published host address.
    # Dedicated VMs reserve these container ports on standard Docker bridges.
    # ORIGINAL excludes replies to outbound connections; bridge output excludes
    # the VM's external egress. Do not accept arbitrary bridge names from input.
    for port in sorted({binding["container_port"] for binding in value["bindings"]}):
        for interface in ("docker+", "br+"):
            rules.append(
                f"-I DOCKER-USER 1 -o {interface} -p tcp -m tcp --dport {port} "
                f"-m conntrack --ctdir ORIGINAL -j {CHAIN}"
            )
    rules.append("COMMIT")
    command("iptables-restore", "--wait", "10", "--noflush", input_text="\n".join(rules) + "\n")


def read_policy(path):
    check_owned(path, private=True)
    return policy(parse_document(path.read_text()))


def atomic_json(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix=".backend-firewall-", dir=STATE_DIR)
    try:
        with os.fdopen(descriptor, "w") as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(value, output, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        sync_directory()
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def sync_directory():
    directory = os.open(STATE_DIR, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def read_transaction(transaction):
    check_owned(TRANSACTION_FILE, private=True)
    value = parse_document(TRANSACTION_FILE.read_text())
    if not isinstance(value, dict) or set(value) != {"transaction", "previous", "candidate", "requested"}:
        raise ValueError("Invalid backend firewall transaction")
    if str(uuid.UUID(value["transaction"])) != transaction:
        raise ValueError("Backend firewall transaction changed")
    for key in ("previous", "candidate", "requested"):
        value[key] = policy(value[key])
    return value


def finish_transaction():
    TRANSACTION_FILE.unlink()
    sync_directory()


def prepare(transaction, installed):
    if TRANSACTION_FILE.exists():
        raise ValueError("Resolve the retained backend firewall transaction before redeploying")
    requested = read_policy(DESIRED_FILE)
    if policy(parse_document(sys.stdin.read(65537))) != requested:
        raise ValueError("Reviewed backend desired policy changed after deployment validation")
    candidate_bridges = installed.get("bridge_peers", []) + requested.get("bridge_peers", [])
    candidate = policy({"bindings": installed["bindings"] + requested["bindings"],
                        "peers": installed["peers"] + requested["peers"],
                        "bridge_peers": [dict(pair) for pair in {
                            tuple(sorted(pair.items())) for pair in candidate_bridges}]})
    # The caller cannot submit a new policy through sudo. Only the administrator's
    # reviewed desired.json is eligible for this transaction.
    atomic_json(TRANSACTION_FILE, {"transaction": transaction, "previous": installed,
                                  "candidate": candidate, "requested": requested})


def main():
    simple = {"validate", "restore", "check-binding", "check-policy"}
    transactional = {"prepare", "activate", "rollback", "finalize"}
    if len(sys.argv) < 2 or sys.argv[1] not in simple | transactional:
        raise ValueError("Invalid backend firewall operation")
    action = sys.argv[1]
    if len(sys.argv) != (3 if action in transactional else 2):
        raise ValueError("Invalid backend firewall arguments")
    transaction = str(uuid.UUID(sys.argv[2])) if action in transactional else None
    if action == "validate":
        policy(parse_document(sys.stdin.read(65537)))
        print("[backend-firewall] Policy is valid")
        return
    if os.geteuid() != 0:
        raise ValueError("Backend firewall restoration requires root")
    os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
    for directory in (STATE_DIR, *STATE_DIR.parents):
        check_owned(directory, directory=True, private=directory == STATE_DIR)
    with (STATE_DIR / ".lock").open("a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        installed = read_policy(POLICY_FILE)
        if action in {"check-binding", "check-policy"}:
            if TRANSACTION_FILE.exists():
                raise ValueError("Resolve the retained backend firewall transaction before redeploying")
            if action == "check-policy":
                requested = policy(parse_document(sys.stdin.read(65537)))
                if requested != read_policy(DESIRED_FILE):
                    raise ValueError("Reviewed backend desired policy does not match the release slot")
            else:
                requested = normalize_binding(parse_document(sys.stdin.read(65537)))
                if requested not in installed["bindings"]:
                    raise ValueError("Deployment binding is not covered by the installed backend firewall policy")
            if command("systemctl", "is-enabled", UNIT_NAME).strip() != "enabled":
                raise ValueError("Backend firewall boot restoration is not enabled")
            command("systemctl", "is-active", "--quiet", UNIT_NAME)
        if action == "prepare":
            prepare(transaction, installed)
            return
        if action in {"activate", "rollback", "finalize"}:
            state = read_transaction(transaction)
            if action == "finalize" and installed != state["candidate"]:
                raise ValueError("Backend firewall candidate has not been activated")
            selected = state[{"activate": "candidate", "rollback": "previous", "finalize": "requested"}[action]]
            # Boot restores exactly the policy selected for this cutover phase.
            atomic_json(POLICY_FILE, selected)
            try:
                apply_policy(selected)
            except (OSError, ValueError, KeyError, IndexError, TypeError, RuntimeError, subprocess.TimeoutExpired):
                if action == "finalize":
                    # Keep the healthy candidate protected if retiring old rules fails.
                    atomic_json(POLICY_FILE, state["candidate"])
                    apply_policy(state["candidate"])
                raise
            if action in {"rollback", "finalize"}:
                finish_transaction()
            return
        # Checks never install caller-supplied data. Restore also runs before Docker.
        apply_policy(installed)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, IndexError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        print("[backend-firewall] Policy validation or restoration failed; refusing to continue", file=sys.stderr)
        sys.exit(1)
