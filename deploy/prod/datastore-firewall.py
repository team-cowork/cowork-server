#!/usr/bin/env python3
"""Restore the root-managed, per-port ingress policy on the datastore VM."""
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys


STATE_DIR = Path("/etc/cowork/datastore-firewall")
POLICY_FILE = STATE_DIR / "policy.json"
UNIT_NAME = "cowork-datastore-firewall.service"
PREFIX = "COWORK_DB_"
MARK = "cowork-datastore"
PRIVATE = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


def private_network(value):
    if not isinstance(value, str):
        raise ValueError("A private IPv4 network string is required")
    result = ipaddress.ip_network(value, strict=True)
    if result.version != 4 or not any(result.subnet_of(parent) for parent in PRIVATE):
        raise ValueError("Only explicit RFC1918 IPv4 networks are supported")
    return str(result)


def port(value):
    if type(value) is not int or not 1 <= value <= 65535:
        raise ValueError("Invalid datastore port")
    return value


def binding(value):
    if not isinstance(value, dict) or set(value) != {"host_port", "container_port"}:
        raise ValueError("Invalid datastore binding")
    return {name: port(value[name]) for name in ("host_port", "container_port")}


def deployment(value):
    if not isinstance(value, dict) or set(value) != {"bind_ip", "bindings", "network"}:
        raise ValueError("Invalid datastore deployment binding")
    if not isinstance(value["bind_ip"], str):
        raise ValueError("The datastore binding address must be a string")
    address = str(ipaddress.IPv4Address(value["bind_ip"]))
    private_network(address)
    if not isinstance(value["network"], str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}", value["network"]):
        raise ValueError("Invalid Docker network name")
    if not isinstance(value["bindings"], list) or not 1 <= len(value["bindings"]) <= 16:
        raise ValueError("Invalid datastore bindings")
    bindings = [binding(item) for item in value["bindings"]]
    if len({item["host_port"] for item in bindings}) != len(bindings) or len({item["container_port"] for item in bindings}) != len(bindings):
        raise ValueError("Datastore ports must be unique")
    return {"bind_ip": address, "network": value["network"], "bindings": sorted(bindings, key=lambda item: item["host_port"])}


def policy(value):
    if not isinstance(value, dict) or set(value) != {"bind_ip", "network", "bridge", "subnet", "bindings", "internal_ports"}:
        raise ValueError("Invalid datastore firewall policy")
    if not isinstance(value["bindings"], list):
        raise ValueError("Invalid datastore firewall bindings")
    bindings = []
    peers = {}
    for item in value["bindings"]:
        if not isinstance(item, dict) or set(item) != {"host_port", "container_port", "peers"}:
            raise ValueError("Invalid per-port datastore policy")
        normalized = binding({name: item[name] for name in ("host_port", "container_port")})
        if not isinstance(item["peers"], list) or not 1 <= len(item["peers"]) <= 64:
            raise ValueError("Invalid datastore peers")
        peers[normalized["container_port"]] = sorted({private_network(peer) for peer in item["peers"]})
        bindings.append(normalized)
    result = deployment({"bind_ip": value["bind_ip"], "bindings": bindings, "network": value["network"]})
    if not isinstance(value["bridge"], str) or not re.fullmatch(r"br-[a-zA-Z0-9_-]{1,12}", value["bridge"]):
        raise ValueError("Invalid fixed Docker bridge interface")
    subnet = private_network(value["subnet"])
    if ipaddress.ip_network(subnet).prefixlen < 16:
        raise ValueError("The local Docker subnet is too broad")
    if not isinstance(value["internal_ports"], list) or len(value["internal_ports"]) > 16:
        raise ValueError("Invalid internal datastore ports")
    internal = [port(item) for item in value["internal_ports"]]
    if len(set(internal)) != len(internal) or set(internal) & set(peers):
        raise ValueError("Internal datastore ports must be distinct")
    return {**result, "bridge": value["bridge"], "subnet": subnet, "peers": peers, "internal_ports": sorted(internal)}


def document(content):
    if len(content) > 65536:
        raise ValueError("Datastore firewall input is too large")
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate datastore firewall input key")
            result[key] = value
        return result
    return json.loads(content, object_pairs_hook=unique_keys)


def owned(path, directory=False, private=False):
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected(info.st_mode) or info.st_uid != 0 or info.st_mode & (0o077 if private else 0o022):
        raise ValueError("Datastore firewall files and parents must be root-owned")


def command(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(f"Datastore firewall command failed: {args[0]}")
    return result.stdout


def verify_network(value):
    networks = json.loads(command("docker", "network", "inspect", value["network"]))
    if not isinstance(networks, list) or len(networks) != 1:
        raise ValueError("The fixed datastore Docker network is missing")
    current = networks[0]
    bridge = current.get("Options", {}).get("com.docker.network.bridge.name") or "br-" + current["Id"][:12]
    subnets = [item.get("Subnet") for item in current.get("IPAM", {}).get("Config", [])]
    if current.get("Driver") != "bridge" or current.get("EnableIPv6") or bridge != value["bridge"] or subnets != [value["subnet"]]:
        raise ValueError("The Docker network differs from the root-managed datastore policy")


def restore(value):
    ports = sorted(set(value["peers"]) | set(value["internal_ports"]))
    chains = {number: PREFIX + str(number) for number in ports}
    for tool in ("iptables", "ip6tables"):
        saved = command(tool, "-w", "10", "-S").splitlines()
        parsed = [shlex.split(line) for line in saved]
        rules = ["*filter"]
        if tool == "iptables" and ["-N", "DOCKER-USER"] not in parsed:
            rules.append(":DOCKER-USER - [0:0]")
        for chain in chains.values():
            rules.extend([f":{chain} - [0:0]", f"-F {chain}"])
        old_chains = {line[1] for line in parsed if line[:1] == ["-N"] and line[1].startswith(PREFIX)}
        for raw, line in zip(saved, parsed):
            if "-j" not in line or not line[line.index("-j") + 1].startswith(PREFIX):
                continue
            if line[:1] != ["-A"] or line[1] not in {"INPUT", "DOCKER-USER", "FORWARD"} or MARK not in line:
                raise ValueError("An unrecognized rule references a datastore firewall chain")
            # Preserve canonical iptables syntax, including the unquoted ! token.
            rules.append("-D" + raw[2:])
        for obsolete in sorted(old_chains - set(chains.values())):
            rules.extend([f"-F {obsolete}", f"-X {obsolete}"])
        for number, chain in chains.items():
            if tool == "iptables":
                # Both the ingress interface and source subnet must match.
                # This permits DB-local exporters without trusting a routed subnet.
                rules.append(f"-A {chain} -i {value['bridge']} -s {value['subnet']} -j RETURN")
                sources = set(value["peers"].get(number, [])) | {value["bind_ip"] + "/32"}
                rules.extend(f"-A {chain} -s {peer} -j RETURN" for peer in sorted(sources))
            rules.append(f"-A {chain} -j DROP")
        mark = f"-m comment --comment {MARK}"
        original = "-m conntrack --ctdir ORIGINAL"
        for item in value["bindings"]:
            host, target = item["host_port"], item["container_port"]
            chain = chains[target]
            rules.append(f"-I INPUT 1 ! -i lo -p tcp --dport {host} {original} {mark} -j {chain}")
            if tool == "iptables":
                rules.append(f"-I DOCKER-USER 1 -p tcp {original} --ctorigdst {value['bind_ip']} --ctorigdstport {host} {mark} -j {chain}")
        for number, chain in chains.items():
            if number not in {item["host_port"] for item in value["bindings"]}:
                rules.append(f"-I INPUT 1 ! -i lo -p tcp --dport {number} {original} {mark} -j {chain}")
            # Reserve these container ports on all Docker bridges. Original
            # direction excludes replies to connections initiated by containers.
            # IPv6 uses FORWARD because Docker need not create its IPv6 chain.
            forward = "DOCKER-USER" if tool == "iptables" else "FORWARD"
            for interface in ("docker+", "br+"):
                rules.append(f"-I {forward} 1 -o {interface} -p tcp --dport {number} {original} {mark} -j {chain}")
        rules.append("COMMIT")
        command(tool + "-restore", "--wait", "10", "--noflush", input_text="\n".join(rules) + "\n")


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {"validate", "restore", "check-binding"}:
        raise ValueError("Expected validate, restore or check-binding")
    operation = sys.argv[1]
    if operation == "validate":
        policy(document(sys.stdin.read(65537)))
        print("[datastore-firewall] Policy is valid")
        return
    if os.geteuid() != 0:
        raise ValueError("Datastore firewall restoration requires root")
    os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
    for directory in (STATE_DIR, *STATE_DIR.parents):
        owned(directory, directory=True, private=directory == STATE_DIR)
    owned(POLICY_FILE, private=True)
    with (STATE_DIR / ".lock").open("a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        value = policy(document(POLICY_FILE.read_text()))
        if operation == "check-binding":
            requested = deployment(document(sys.stdin.read(65537)))
            if requested != {name: value[name] for name in ("bind_ip", "network", "bindings")}:
                raise ValueError("Deployment bindings differ from the fixed datastore policy")
            if command("systemctl", "is-enabled", UNIT_NAME).strip() != "enabled":
                raise ValueError("Datastore firewall boot restoration is not enabled")
            command("systemctl", "is-active", "--quiet", UNIT_NAME)
            verify_network(value)
        # This operation restores the installed policy only. It cannot change
        # peers, select executable paths, or write the root-managed policy.
        restore(value)
        print("[datastore-firewall] Fixed policy applied")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, IndexError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        print("[datastore-firewall] Policy validation or restoration failed; refusing to continue", file=sys.stderr)
        sys.exit(1)
