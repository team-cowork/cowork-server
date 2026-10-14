#!/usr/bin/env python3
"""Select targets from each target's last successful runtime deployment."""
import json
import os
import runpy
import subprocess
from pathlib import Path

from deployment_state import SHA, successful_sha

ROOT = Path.cwd()
IMAGE_INPUTS = runpy.run_path(str(Path(__file__).resolve().parents[1] / "images/build-matrix.py"))


def ancestor(base, head):
    result = subprocess.run(["git", "merge-base", "--is-ancestor", base, head])
    if result.returncode not in {0, 1}:
        raise ValueError("Cannot compare deployment history")
    return result.returncode == 0


def affected(service, paths, target=None):
    # Prose and examples cannot alter a running container. Slot JSON is executable desired state.
    paths = [path for path in paths if not path.endswith((".md", ".rst", ".txt")) and not path.startswith("docs/")]
    if "deploy/prod/host-network.json" in paths and service in IMAGE_INPUTS["CATALOG"]:
        return True
    if f"deploy/prod/slots/{target or service}.json" in paths:
        return True
    if service == "monitoring" and any(path.startswith("deploy/prod/slots/") for path in paths):
        return True
    config_firewall = {"deploy/prod/config-firewall.py", "deploy/prod/config-firewall.service",
                       "deploy/prod/bootstrap-config-firewall.sh", "deploy/prod/lib/config-firewall.sh"}
    shared = {"deploy/prod/deploy.sh", "deploy/prod/defaults.sh", "deploy/prod/inventory.json",
              ".github/workflows/cowork-prod-cd.yml"}
    if any(path not in config_firewall and (
        path in shared or path.startswith(("deploy/prod/lib/", ".github/actions/deploy-target/"))
        or (path.startswith("deploy/prod/") and path.count("/") == 2 and path.endswith(".py"))
    ) for path in paths):
        return True
    if f"deploy/prod/services/{service}.sh" in paths:
        return True
    if service == "config" and any(path in config_firewall for path in paths):
        return True
    if service in IMAGE_INPUTS["CATALOG"]:
        # Custom clients load configuration at startup and do not all refresh.
        config = "cowork-config/src/main/resources/"
        if any(path in {config + "application.yml", config + "application.yaml"}
               or path.startswith((config + f"configs/cowork-{service}.",
                                   config + f"configs/cowork-{service}-", config + "configs/application"))
               for path in paths):
            return True
        return IMAGE_INPUTS["affected"](service, paths)
    prefixes = {
        "monitoring": ("deploy/config/monitoring/", "deploy/prod/monitoring/"),
        "vault": ("deploy/prod/vault/", "deploy/config/vault/server.hcl"),
        "datastores": ("deploy/prod/datastores/", "deploy/images/elasticsearch/", "deploy/compose/stack.yaml"),
        "storage": ("deploy/prod/storage/", "deploy/config/seaweedfs/"),
        "log-agent": ("deploy/prod/log-agent/",),
        "node-agent": ("deploy/prod/node-agent/",),
    }
    return any(path.startswith(prefixes[service]) for path in paths)


def main():
    sha = os.environ["RELEASE_SHA"]
    if not SHA.fullmatch(sha) or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() != sha:
        raise ValueError("Checkout must match the release SHA")
    inventory = json.loads((ROOT / "deploy/prod/inventory.json").read_text())
    include, seen, changes = [], set(), {}
    for target in inventory["targets"]:
        service, name = target["service"], target["target"]
        slot = ROOT / "deploy/prod/slots" / f"{name}.json"
        known = ((slot.is_file() and json.loads(slot.read_text())["service"] == service)
                 or (ROOT / "deploy/prod/services" / f"{service}.sh").is_file())
        if name in seen or not known:
            raise ValueError("Duplicate or unknown deployment target")
        seen.add(name)
        base = successful_sha(service, name)
        if base == sha:
            continue
        if base and subprocess.run(
            ["git", "cat-file", "-e", f"{base}^{{commit}}"], capture_output=True,
        ).returncode != 0:
            print(f"{name}: previous source is unavailable; deploy the selected source")
            base = None
        if base:
            if ancestor(sha, base):
                print(f"{name}: skip stale automatic rollout; a newer SHA is deployed")
                continue
            if base not in changes:
                changes[base] = subprocess.check_output(
                    ["git", "diff", "--name-only", "--no-renames", "-z", base, sha],
                ).decode().rstrip("\0").split("\0")
            if not affected(service, changes[base], name):
                continue
        # A new target or a pre-tracking deployment has no trustworthy baseline.
        include.append({"service": service, "target": name})
    matrix = json.dumps({"include": include}, separators=(",", ":"))
    services = ", ".join(target["target"] for target in include) or "none"
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"matrix={matrix}\nservices-list={services}\n")
    print(f"Deploy targets: {services}")


if __name__ == "__main__":
    main()
