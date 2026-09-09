#!/usr/bin/env python3
"""Combine changed deployment units with the explicit SSH inventory."""
import json
import os
from pathlib import Path

inventory = json.loads(Path(__file__).with_name("inventory.json").read_text())
changed = json.loads(os.environ["FILTER_RESULTS"])
profile = inventory["config_profile"]
if profile not in {"local", "prod"}:
    raise ValueError("inventory config_profile must be local or prod")
include = []
seen = set()
for target in inventory["targets"]:
    name = target["service"]
    if name in seen or not Path(__file__).with_name("services").joinpath(f"{name}.sh").is_file():
        raise ValueError(f"Duplicate or unknown service: {name}")
    seen.add(name)
    port = target["ssh_port"]
    if not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError(f"Invalid SSH port: {name}")
    if changed.get(name) == "true" or changed.get("shared") == "true" or (
        name not in {"monitoring", "vault"} and changed.get("application_shared") == "true"
    ):
        include.append({"service": name, "port": str(port), "config_profile": profile})
matrix = json.dumps({"include": include}, separators=(",", ":"))
services = ", ".join(target["service"] for target in include) or "none"
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    output.write(f"matrix={matrix}\nservices-list={services}\n")
print(f"Deploy targets: {services}")
