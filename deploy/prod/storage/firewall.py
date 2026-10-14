#!/usr/bin/env python3
"""Restore dedicated S3 ingress rules before Docker can publish the service."""
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


POLICY = Path("/etc/cowork/storage-firewall.json")
MARK = "cowork-storage"
CHAIN = "COWORK_STORAGE"
PRIVATE = [ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]


def network(value):
    result = ipaddress.ip_network(value, strict=True)
    if result.version != 4 or not any(result.subnet_of(parent) for parent in PRIVATE):
        raise ValueError("Storage ingress requires explicit private IPv4 addresses")
    return str(result)


def run(*args, input_text=None):
    result = subprocess.run(args, text=True, input=input_text, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(f"Storage firewall command failed: {args[0]}")
    return result.stdout


def load_policy():
    info = POLICY.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError("Storage firewall policy must be root-owned and mode 0600")
    value = json.loads(POLICY.read_text())
    if set(value) != {"bind_ip", "allowed_cidrs", "bridge", "subnet"}:
        raise ValueError("Invalid storage firewall policy")
    address = str(ipaddress.IPv4Address(value["bind_ip"]))
    network(address)
    if not isinstance(value["allowed_cidrs"], list) or not 1 <= len(value["allowed_cidrs"]) <= 32:
        raise ValueError("Invalid storage firewall peers")
    peers = sorted({network(peer) for peer in value["allowed_cidrs"]})
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', value['bridge']):
        raise ValueError('Invalid module storage bridge')
    return address, peers, value['bridge'], network(value['subnet'])


def restore():
    address, peers, bridge, subnet = load_policy()
    for tool in ("iptables", "ip6tables"):
        saved_lines = run(tool, "-w", "10", "-S").splitlines()
        current = [shlex.split(line) for line in saved_lines]
        rules = ["*filter", f":{CHAIN} - [0:0]", f"-F {CHAIN}"]
        if tool == "iptables" and ["-N", "DOCKER-USER"] not in current:
            rules.append(":DOCKER-USER - [0:0]")
        for saved_line, line in zip(saved_lines, current):
            if line[:1] != ["-A"] or "-j" not in line or line[line.index("-j") + 1] != CHAIN:
                continue
            if MARK not in line or line[1] not in ("INPUT", "DOCKER-USER", "FORWARD"):
                raise ValueError("Storage firewall chain has an unrecognized reference")
            # iptables-restore parses its own format, not shell quoting. Keep
            # the canonical -S spelling so an inversion token remains `!`.
            rules.append(saved_line.replace("-A ", "-D ", 1))
        if tool == "iptables":
            rules.append(f'-A {CHAIN} -i {bridge} -s {subnet} -j RETURN')
            rules.extend(f"-A {CHAIN} -p tcp --dport 9000 -s {peer} -j RETURN" for peer in peers)
        rules.append(f"-A {CHAIN} -j DROP")
        if tool == "iptables":
            # Match the published S3 endpoint before DNAT, not unrelated containers on port 9000.
            rules.extend([
                f"-I DOCKER-USER 1 -o {bridge} -p tcp --dport 9000 -m conntrack --ctdir ORIGINAL --ctorigdst {address} --ctorigdstport 9000 -m comment --comment {MARK} -j {CHAIN}",
                f"-I INPUT 1 ! -i lo -d {address} -p tcp --dport 9000 -m conntrack --ctdir ORIGINAL -m comment --comment {MARK} -j {CHAIN}",
            ])
        # S3 publishes IPv4 only. Remove old IPv6 hooks without claiming other IPv6 listeners.
        rules.append("COMMIT")
        # --noflush preserves all other application and Docker firewall rules.
        run(tool + "-restore", "--wait", "10", "--noflush", input_text="\n".join(rules) + "\n")


if __name__ == "__main__":
    if os.geteuid() != 0:
        raise SystemExit("Run the storage firewall helper as root")
    with open("/run/cowork-storage-firewall.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if sys.argv[1:]:
            raise SystemExit("Unsupported storage firewall operation")
        restore()
