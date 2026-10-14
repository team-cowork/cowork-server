#!/usr/bin/env python3
"""Load versioned deployment contracts. No runtime or framework is inferred here."""
import argparse
import ipaddress
import json
import re
from pathlib import Path

NAME = re.compile(r"[a-z][a-z0-9-]{0,63}\Z")
ENV = re.compile(r"[A-Z][A-Z0-9_]*\Z")
SECRET_NAME = re.compile(r"PASSWORD|SECRET|TOKEN|PRIVATE_KEY|API_KEY|ACCOUNTS_JSON|ROLE_ID")
PRIVATE = tuple(ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
RESERVED = {"BASH_ENV", "ENV", "PATH", "HOME", "SHELLOPTS", "BASHOPTS", "PS4", "CDPATH", "GLOBIGNORE", "IFS", "LD_PRELOAD", "LD_LIBRARY_PATH", "DOCKER_HOST", "DOCKER_CONFIG", "DEPLOY_SETTINGS_DIR", "DEPLOY_STATE_DIR", "DEPLOY_SHA", "DEPLOY_TARGET", "DEPLOY_IMAGE_TAG", "DEPLOY_IMAGE_OWNER", "RELEASE_ROOT", "PORT", "APP_ADVERTISE_ADDRESS", "APP_ADVERTISE_PORT", "APP_SERVICE_NAME", "BIND_IP", "ADVERTISE_IP", "HOST_PORT", "CONTAINER_PORT", "HEALTH_PATH", "HEALTH_TIMEOUT_SECONDS", "STOP_TIMEOUT_SECONDS", "SLOT_KIND"}
RESERVED_PREFIXES = ("DEPLOY_", "PYTHON", "BASH", "LD_", "GIT_", "DOCKER_", "SSH_")


def environment_key(key):
    return (isinstance(key, str) and ENV.fullmatch(key) and key not in RESERVED and
            (not key.startswith(RESERVED_PREFIXES) or key == "DOCKER_CONTAINER_LOG_DIR"))


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def pairs(values):
    result = {}
    for key, value in values:
        demand(key not in result, "Duplicate slot JSON key")
        result[key] = value
    return result


def private(value):
    network = ipaddress.ip_network(value, strict=True)
    demand(network.version == 4 and any(network.subnet_of(n) for n in PRIVATE), "Slot peers must be RFC1918 IPv4 networks")
    return str(network)


def validate(value, target):
    demand(NAME.fullmatch(target), "Invalid slot target")
    demand(isinstance(value, dict) and set(value) <= {"schema_version", "service", "kind", "runtime", "secrets", "github_secrets", "container", "peers"}, "Invalid slot sections")
    demand(value.get("schema_version") == 1, "Unsupported slot schema")
    demand(isinstance(value.get("service"), str) and NAME.fullmatch(value["service"]), "Invalid slot service")
    demand(value.get("kind") in {"application", "config", "infrastructure"}, "Invalid slot kind")
    demand((value["kind"] == "config") == (value["service"] == "config"), "Config requires its explicit slot capability")
    for key, item in value.get("runtime", {}).items():
        demand(environment_key(key), "Invalid or reserved slot environment key")
        demand(isinstance(item, str) and "\0" not in item, "Slot environment values must be strings")
        demand(not SECRET_NAME.search(key), "Store secret values in Vault, not a slot")
    secrets = value.get("secrets", {})
    demand(isinstance(secrets, dict) and set(secrets) <= {"runtime", "application"}, "Invalid slot secret sections")
    for section, refs in secrets.items():
        demand(isinstance(refs, dict), "Slot secret references must be objects")
        for key, ref in refs.items():
            demand(environment_key(key) and key not in value.get("runtime", {}), "Invalid, reserved or duplicate secret key")
            demand(isinstance(ref, dict) and set(ref) == {"path", "key"}, "A slot secret reference requires path and key")
            demand(isinstance(ref["path"], str) and all(NAME.fullmatch(p) for p in ref["path"].split("/")), "Invalid Vault secret path")
            demand(isinstance(ref["key"], str) and ref["key"], "Invalid Vault secret key")
    demand(not set(secrets.get("runtime", {})).intersection(secrets.get("application", {})), "Duplicate secret environment key")
    github_secrets = value.get("github_secrets", {})
    demand(isinstance(github_secrets, dict), "GitHub secret references must be an object")
    if github_secrets:
        demand(value["kind"] == "infrastructure" and value["service"] == "monitoring"
               and github_secrets == {"DISCORD_MONITORING_ALERT_WEBHOOK": "DISCORD_MONITORING_ALERT_WEBHOOK"},
               "Only monitoring can consume its GitHub monitoring webhook")
        demand(not set(github_secrets).intersection(value.get("runtime", {}))
               and not set(github_secrets).intersection(secrets.get("runtime", {}))
               and not set(github_secrets).intersection(secrets.get("application", {})),
               "Secret source declarations must not overlap")
    if value["kind"] == "infrastructure":
        demand("container" not in value and "peers" not in value, "Infrastructure lifecycle owns its containers and ingress")
        return value
    demand(value.get("runtime", {}).get("APP_CONFIG_PROFILE") in {"local", "prod"}, "A slot must declare APP_CONFIG_PROFILE")
    c = value.get("container", {})
    demand(isinstance(c, dict) and set(c) == {"port", "host_port", "bind_address", "advertise_address", "readiness", "metrics_path", "limits", "stop_timeout_seconds", "mounts"}, "Invalid container contract")
    for key in ("port", "host_port"):
        demand(type(c[key]) is int and 1 <= c[key] <= 65535, "Invalid slot port")
    address = str(ipaddress.IPv4Address(c["advertise_address"]))
    private(address)
    bind = str(ipaddress.IPv4Address(c["bind_address"]))
    demand(bind == address or (bind == "0.0.0.0" and value["service"] == "gateway"), "Private slots must bind their advertised address")
    readiness = c["readiness"]
    demand(isinstance(readiness, dict) and set(readiness) == {"path", "timeout_seconds"}, "Invalid readiness contract")
    demand(type(readiness["timeout_seconds"]) is int and 1 <= readiness["timeout_seconds"] <= 1050, "Readiness timeout must fit the deployment and rollback session")
    for path in (readiness["path"], c["metrics_path"]):
        demand(isinstance(path, str) and path.startswith("/") and not path.startswith("//")
               and not re.search(r"[\x00-\x20\x7f?#]", path), "Invalid health or metrics path")
    demand(type(c["stop_timeout_seconds"]) is int and 1 <= c["stop_timeout_seconds"] <= 120, "Invalid stop timeout")
    limits = c["limits"]
    demand(isinstance(limits, dict) and set(limits) == {"memory", "cpus", "pids"}, "Invalid slot limits")
    demand(isinstance(limits["memory"], str) and re.fullmatch(r"[1-9][0-9]*[mg]", limits["memory"]), "Invalid memory limit")
    demand(type(limits["cpus"]) in {int, float} and 0.1 <= limits["cpus"] <= 128, "Invalid CPU limit")
    demand(type(limits["pids"]) is int and 64 <= limits["pids"] <= 65536, "Invalid PID limit")
    demand(isinstance(c["mounts"], list), "Mounts must be a list")
    destinations = set()
    for mount in c["mounts"]:
        demand(isinstance(mount, dict) and set(mount) == {"type", "source", "target", "read_only"}, "Invalid mount contract")
        demand(mount["type"] == "volume" and re.fullmatch(r"cowork-[a-z0-9][a-z0-9_.-]*", mount["source"]), "Only dedicated named volumes are allowed in application slots")
        target_path = Path(mount["target"])
        demand(target_path.is_absolute() and ".." not in target_path.parts and len(target_path.parts) > 1 and mount["target"] not in destinations and "," not in mount["target"], "Invalid mount target")
        demand(type(mount["read_only"]) is bool, "Mount read_only must be boolean")
        destinations.add(mount["target"])
    demand(isinstance(value.get("peers"), list) and value["peers"], "Slot peers are required")
    for peer in value["peers"]:
        private(peer)
    return value


def load(target, directory=None):
    directory = directory or Path(__file__).with_name("slots")
    demand(NAME.fullmatch(target), "Invalid slot target")
    path = directory / f"{target}.json"
    demand(path.is_file() and not path.is_symlink(), "Deployment target has no regular slot document")
    return validate(json.loads(path.read_text(), object_pairs_hook=pairs), target)


def environment(slot):
    result = dict(slot.get("runtime", {}))
    result["SLOT_KIND"] = slot["kind"]
    if slot["kind"] == "infrastructure":
        return result
    c = slot["container"]
    result.update(APP_SERVICE_NAME=f"cowork-{slot['service']}", PORT=str(c["port"]),
                  APP_ADVERTISE_ADDRESS=c["advertise_address"], APP_ADVERTISE_PORT=str(c["host_port"]),
                  ADVERTISE_IP=c["advertise_address"], BIND_IP=c["bind_address"], HOST_PORT=str(c["host_port"]),
                  CONTAINER_PORT=str(c["port"]), HEALTH_PATH=c["readiness"]["path"],
                  HEALTH_TIMEOUT_SECONDS=str(c["readiness"]["timeout_seconds"]),
                  STOP_TIMEOUT_SECONDS=str(c["stop_timeout_seconds"]))
    if slot["kind"] == "config":
        result["CONFIG_ALLOWED_CIDRS"] = ",".join(slot["peers"])
    return result


def host_network(directory=None):
    base = directory or Path(__file__).parent
    path = base / "network/host-network.json"
    if directory is not None and not path.exists():
        # Replay can read releases from before the network directory was introduced.
        path = base / "host-network.json"
    demand(path.is_file() and not path.is_symlink(), "Missing regular host network contract")
    value = json.loads(path.read_text(), object_pairs_hook=pairs)
    demand(isinstance(value, dict) and set(value) == {"name", "mtu"}, "Invalid host network contract")
    demand(type(value["mtu"]) is int and 1280 <= value["mtu"] <= 9000, "Invalid host network MTU")
    demand(isinstance(value["name"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value["name"]), "Invalid Docker network name")
    return value


def container_arguments(slot, network_directory=None):
    c = slot["container"]
    result = ["--network", host_network(network_directory)["name"], "--memory", c["limits"]["memory"], "--cpus", str(c["limits"]["cpus"]), "--pids-limit", str(c["limits"]["pids"]),
              "--stop-timeout", str(c["stop_timeout_seconds"]), "--security-opt", "no-new-privileges:true", "--cap-drop", "ALL"]
    for mount in c["mounts"]:
        result.extend(["--mount", f"type=volume,source={mount['source']},target={mount['target']}" + (",readonly" if mount["read_only"] else "")])
    return result


def firewall(slot):
    c = slot["container"]
    if slot["kind"] == "config":
        return {"bindings": [{"address": c["bind_address"], "port": c["host_port"]}], "cidrs": slot["peers"]}
    # node-agent's node_exporter runs alongside every application container on the same VM and is
    # scraped by the same peers that already reach this slot's own port (ansible firewall.yml applies
    # this output for every IaC-managed application host except gateway, which isn't a firewall target
    # here and needs its own ingress fix — same for config, whose firewall is rendered separately by
    # config-firewall.py and does not read this function's output at all).
    bindings = [
        {"address": c["bind_address"], "host_port": c["host_port"], "container_port": c["port"]},
        {"address": c["bind_address"], "host_port": 9100, "container_port": 9100},
    ]
    return {"bindings": bindings, "peers": slot["peers"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("validate", "firewall"))
    parser.add_argument("target", nargs="?")
    args = parser.parse_args()
    host_network()
    targets = [args.target] if args.target else [p.stem for p in Path(__file__).with_name("slots").glob("*.json")]
    for target in targets:
        slot = load(target)
        if args.operation == "firewall":
            print(json.dumps(firewall(slot)))
    if args.operation == "validate":
        print(f"Validated {len(targets)} deployment slots")


if __name__ == "__main__":
    main()
