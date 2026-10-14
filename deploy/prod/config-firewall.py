#!/usr/bin/env python3
"""Persist only the Config Server ingress policy; never flush Docker's rules."""
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


STATE_DIR = Path("/etc/cowork/config-firewall")
STATE_FILE = STATE_DIR / "policy.json"
UNIT_NAME = "cowork-config-firewall.service"
PRIVATE_NETWORKS = [ipaddress.ip_network(cidr) for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]


def private_network(value):
    if not isinstance(value, str):
        raise ValueError("Config firewall networks must be strings")
    network = ipaddress.ip_network(value, strict=True)
    if network.version != 4 or not any(network.subnet_of(parent) for parent in PRIVATE_NETWORKS):
        raise ValueError("Config ingress requires explicit RFC1918 IPv4 networks")
    return network


def policy(value):
    if not isinstance(value, dict) or set(value) != {"bindings", "cidrs"}:
        raise ValueError("Invalid Config firewall policy")
    bindings, cidrs = value["bindings"], value["cidrs"]
    if not isinstance(bindings, list) or not 1 <= len(bindings) <= 64:
        raise ValueError("Invalid Config firewall bindings")
    if not isinstance(cidrs, list) or not 1 <= len(cidrs) <= 256:
        raise ValueError("Invalid Config firewall peers")
    normalized_bindings = []
    for binding in bindings:
        if not isinstance(binding, dict) or set(binding) != {"address", "port"}:
            raise ValueError("Invalid Config firewall binding")
        if not isinstance(binding["address"], str):
            raise ValueError("Config firewall addresses must be strings")
        address = str(ipaddress.IPv4Address(binding["address"]))
        private_network(address)
        port = binding["port"]
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("Invalid Config firewall port")
        normalized = {"address": address, "port": port}
        if normalized not in normalized_bindings:
            normalized_bindings.append(normalized)
    return {"bindings": normalized_bindings, "cidrs": sorted({str(private_network(cidr)) for cidr in cidrs})}


def command(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"Config firewall command failed: {args[0]}")
    return result.stdout


def secure_directory(path, mode=0o755):
    if not path.exists():
        path.mkdir(mode=mode)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError("Config firewall installation requires root-owned directories")


def atomic_write(path, content, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=".config-firewall-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            os.fchown(output.fileno(), 0, 0)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def save_state(state):
    atomic_write(STATE_FILE, (json.dumps(state, sort_keys=True) + "\n").encode(), 0o600)


def load_state():
    info = STATE_FILE.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError("Config firewall policy must be a private root-owned file")
    state = json.loads(STATE_FILE.read_text())
    # Accept the first installed format during an operator-managed upgrade.
    if set(state) == {"transaction", "active", "previous", "candidate"}:
        state["requested"] = state["candidate"]
    if set(state) != {"transaction", "active", "previous", "candidate", "requested"}:
        raise ValueError("Invalid Config firewall state")
    uuid.UUID(state["transaction"])
    for name in ("active", "previous", "candidate", "requested"):
        state[name] = policy(state[name])
    return state


def runtime_policy():
    lines = [shlex.split(line) for line in command("iptables", "-w", "10", "-S").splitlines()]
    rules = [line for line in lines if line[:2] == ["-A", "COWORK_CONFIG"]]
    jumps = [line for line in lines if "-j" in line and line[line.index("-j") + 1] == "COWORK_CONFIG"]
    cidrs, bindings = [], []
    for line in rules[:-1]:
        if len(line) != 6 or line[:3] != ["-A", "COWORK_CONFIG", "-s"] or line[4:] != ["-j", "RETURN"]:
            raise ValueError("Unrecognized existing Config firewall rule")
        cidrs.append(str(private_network(line[3])))
    if rules and rules[-1] != ["-A", "COWORK_CONFIG", "-j", "DROP"]:
        raise ValueError("Existing Config firewall must end with DROP")
    for line in jumps:
        if len(line) != 14 or line[:2] != ["-A", "DOCKER-USER"]:
            raise ValueError("Unrecognized existing Config firewall jump")
        options = dict(zip(line[2::2], line[3::2]))
        if set(options) != {"-p", "-m", "--ctdir", "--ctorigdst", "--ctorigdstport", "-j"} or (
            options["-p"], options["-m"], options["--ctdir"], options["-j"]
        ) != ("tcp", "conntrack", "ORIGINAL", "COWORK_CONFIG"):
            raise ValueError("Unrecognized existing Config firewall match")
        address = private_network(options["--ctorigdst"])
        if address.prefixlen != 32:
            raise ValueError("Existing Config firewall destination must be one IPv4 address")
        bindings.append({"address": str(address.network_address), "port": int(options["--ctorigdstport"])})
    current = policy({"bindings": bindings, "cidrs": cidrs}) if jumps else None
    return current, jumps, ["-N", "DOCKER-USER"] in lines


def apply_policy(value):
    value = policy(value)
    _, jumps, docker_user_exists = runtime_policy()
    rules = ["*filter"]
    if not docker_user_exists:
        rules.append(":DOCKER-USER - [0:0]")
    rules.extend([":COWORK_CONFIG - [0:0]", "-F COWORK_CONFIG"])
    rules.extend(f"-A COWORK_CONFIG -s {cidr} -j RETURN" for cidr in value["cidrs"])
    rules.append("-A COWORK_CONFIG -j DROP")
    # Only our exact jumps are replaced. --noflush preserves every other chain.
    rules.extend(shlex.join(["-D", *jump[1:]]) for jump in jumps)
    for binding in value["bindings"]:
        rules.append(
            "-I DOCKER-USER 1 -p tcp -m conntrack --ctdir ORIGINAL "
            f"--ctorigdst {binding['address']} --ctorigdstport {binding['port']} -j COWORK_CONFIG"
        )
    rules.append("COMMIT")
    command("iptables-restore", "--wait", "10", "--noflush", input_text="\n".join(rules) + "\n")


def existing_container_bindings():
    # Query only during prepare: boot-time restore must run before Docker starts.
    # A successful empty listing means absence; daemon/inspect errors propagate.
    containers = command("docker", "container", "ls", "--all", "--no-trunc", "--format", "{{.ID}} {{.Names}}")
    identifiers = [line.split()[0] for line in containers.splitlines() if line.split()[1:] == ["cowork-config"]]
    if not identifiers:
        return []
    if len(identifiers) != 1:
        raise ValueError("Ambiguous existing Config container")
    existing = json.loads(command(
        "docker", "container", "inspect", "--format",
        '{"name":{{json .Name}},"bindings":{{json .HostConfig.PortBindings}}}', identifiers[0],
    ))
    if existing["name"] != "/cowork-config":
        raise ValueError("Existing Config container changed during preparation")
    ports = existing["bindings"] or {}
    if not isinstance(ports, dict) or not ports:
        raise ValueError("Existing Config container requires an explicit private published port")
    bindings = []
    for container_port, published in ports.items():
        if not container_port.endswith("/tcp") or not isinstance(published, list) or not published:
            raise ValueError("Existing Config container must publish explicit TCP bindings")
        if not 1 <= int(container_port.removesuffix("/tcp")) <= 65535:
            raise ValueError("Invalid existing Config container port")
        for binding in published:
            # The old image may use a different internal port. Protect every
            # actual published binding until its container has been retired.
            address = str(ipaddress.IPv4Address(binding["HostIp"]))
            private_network(address)
            bindings.append({"address": address, "port": int(binding["HostPort"])})
    return bindings


def prepare(transaction):
    incoming = sys.stdin.read(65537)
    if len(incoming) > 65536:
        raise ValueError("Config firewall policy is too large")
    requested = policy(json.loads(incoming))
    previous, _, _ = runtime_policy()
    if previous is None and STATE_FILE.exists():
        previous = load_state()["active"]
    existing_bindings = existing_container_bindings()
    if previous:
        previous = policy({"bindings": previous["bindings"] + existing_bindings, "cidrs": previous["cidrs"]})
    # During cutover the old container may still run. Protect its old binding
    # even at the first transition; stopped containers can return on rollback.
    candidate = policy({
        "bindings": (previous["bindings"] if previous else existing_bindings) + requested["bindings"],
        "cidrs": requested["cidrs"],
    })
    previous = previous or candidate
    # Preparing does not change live rules. Boot still restores the previous
    # policy until activation; the first protected deployment retains its limit.
    save_state({"transaction": transaction, "active": previous, "previous": previous,
                "candidate": candidate, "requested": requested})
    command("systemctl", "enable", UNIT_NAME)


def finalize(state):
    # The caller has already checked readiness and removed the old container.
    # Keep only the requested binding after that point, including on reboot.
    if state["active"] != state["candidate"]:
        raise ValueError("Config firewall candidate has not been activated")
    committed = {"transaction": state["transaction"], **{
        name: state["requested"] for name in ("active", "previous", "candidate", "requested")
    }}
    save_state(committed)
    try:
        apply_policy(committed["active"])
    except (OSError, ValueError, KeyError, IndexError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        # A failed finalization keeps the cutover restrictions and must fail CD.
        # The new healthy container is retained; the old one no longer exists.
        save_state(state)
        apply_policy(state["active"])
        raise


def main():
    action = sys.argv[1]
    if action == "render" and len(sys.argv) == 2:
        print(json.dumps(policy({
            "bindings": [{"address": os.environ["BIND_IP"], "port": int(os.environ["HOST_PORT"])}],
            "cidrs": [cidr.strip() for cidr in os.environ["CONFIG_ALLOWED_CIDRS"].split(",")],
        })))
        return
    if action not in {"prepare", "activate", "rollback", "finalize", "restore"} or os.geteuid() != 0:
        raise ValueError("Expected a Config firewall operation as root")
    transaction = None if action == "restore" else str(uuid.UUID(sys.argv[2]))
    if len(sys.argv) != (2 if action == "restore" else 3):
        raise ValueError("Invalid Config firewall arguments")
    os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
    secure_directory(STATE_DIR.parent)
    secure_directory(STATE_DIR, 0o700)
    os.chmod(STATE_DIR, 0o700)
    with (STATE_DIR / ".lock").open("a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        if action == "prepare":
            prepare(transaction)
            return
        state = load_state()
        if transaction is not None and transaction != state["transaction"]:
            raise ValueError("Config firewall deployment transaction changed")
        if action == "finalize":
            finalize(state)
            return
        if action != "restore":
            state["active"] = state["candidate" if action == "activate" else "previous"]
            save_state(state)
        # Failure leaves a restrictive boot policy and propagates to the caller:
        # Docker cannot start, or the deployment stops the previous container.
        apply_policy(state["active"])
    if action in {"activate", "rollback"}:
        # Release the lock before starting the unit: restore acquires it again.
        # Never restart this required unit, which would also stop Docker.
        command("systemctl", "start", UNIT_NAME)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, IndexError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        print("[config-firewall] Policy installation or restoration failed; refusing to continue", file=sys.stderr)
        sys.exit(1)
