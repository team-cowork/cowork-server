#!/usr/bin/env python3
"""Select deployment units; each target resolves its VM and settings from Vault."""
import json
import os
import re
from pathlib import Path

inventory = json.loads(Path(__file__).with_name("inventory.json").read_text())
changed = json.loads(os.environ["FILTER_RESULTS"])
include = []
seen = set()
for target in inventory["targets"]:
    name = target["service"]
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", target["target"]):
        raise ValueError("Invalid deployment target")
    if target["target"] in seen or not Path(__file__).with_name("services").joinpath(f"{name}.sh").is_file():
        raise ValueError(f"Duplicate or unknown service: {name}")
    seen.add(target["target"])
    if changed.get(name) == "true" or changed.get("shared") == "true" or (
        name not in {"monitoring", "vault", "log-agent"} and changed.get("application_shared") == "true"
    ):
        include.append({"service": name, "target": target["target"]})
matrix = json.dumps({"include": include}, separators=(",", ":"))
services = ", ".join(target["service"] for target in include) or "none"
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    output.write(f"matrix={matrix}\nservices-list={services}\n")
print(f"Deploy targets: {services}")
