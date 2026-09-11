#!/usr/bin/env python3
"""Static deployment validation. Does not build, pull, or start any container."""
import ast
import json
import os
import re
import runpy
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
for path in sorted((ROOT / "deploy").rglob("*.sh")):
    subprocess.run(["bash", "-n", str(path)], check=True)
for path in sorted((ROOT / "deploy").rglob("*.py")):
    ast.parse(path.read_text(), filename=str(path))

# No real .env values are emitted or required. Override every Compose placeholder.
env = {key: value for key, value in os.environ.items() if not key.startswith("COMPOSE_")}
compose_text = "\n".join(path.read_text() for path in (ROOT / "deploy").rglob("*.yaml"))
for name in set(re.findall(r"(?<!\$)\$\{([A-Za-z_][A-Za-z0-9_]*)", compose_text)):
    env[name] = "validation-only"
env.update(
    MYSQL_HOST_PORT="3306", KAFKA_EXTERNAL_HOST_PORT="9094", COWORK_CONFIG_HOST_PORT="8761",
    COWORK_PROJECT_HOST_PORT="8084", LIVEKIT_CONFIG_FILE="livekit.yaml", SPRING_PROFILES_ACTIVE="local",
    VAULT_PORT="8200", MYSQL_PORT="3306", REDIS_PORT="6379", VAULT_HOST_PORT="8200",
    MONITORING_BIND_IP="127.0.0.1", VAULT_BIND_IP="127.0.0.1", MONITORING_ADMIN_BIND_IP="127.0.0.1",
    DOCKER_CONTAINER_LOG_DIR="/var/lib/docker/containers", MONITORING_PROMETHEUS_CONFIG="/tmp/cowork-validation-prometheus.json",
)
models = {
    "local": ["docker-compose.yml"],
    "single-vm-prod": ["deploy/compose/stack.yaml", "deploy/compose/single-vm.prod.yaml"],
    "monitoring": ["deploy/prod/monitoring/compose.yaml"],
    "vault": ["deploy/prod/vault/compose.yaml"],
    "log-agent": ["deploy/prod/log-agent/compose.yaml"],
}
for name, files in models.items():
    command = ["docker", "compose", "--project-directory", str(ROOT), "--env-file", "/dev/null", "-p", "cowork-validation"]
    for file in files:
        command.extend(["-f", str(ROOT / file)])
    result = subprocess.run(command + ["config", "--format", "json"], env=env, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f"{name}: {result.stderr}")
    model = json.loads(result.stdout)
    for service in model["services"].values():
        if "build" in service:
            build = service["build"]
            dockerfile = Path(build["context"]) / build["dockerfile"]
            if not dockerfile.is_file():
                raise SystemExit(f"Missing Dockerfile: {dockerfile}")
        for volume in service.get("volumes", []):
            if volume["type"] == "bind":
                source = Path(volume["source"])
                if source.is_relative_to(ROOT) and not source.exists():
                    raise SystemExit(f"Missing bind mount source: {source}")
    print(f"{name}: Compose configuration and repository paths valid ({len(model['services'])} services)")

inventory = json.loads((ROOT / "deploy/prod/inventory.json").read_text())
settings_schema = runpy.run_path(str(ROOT / "deploy/prod/vault-settings.py"))
settings_schema["validate_deployment"](json.loads((ROOT / "deploy/prod/settings.example.json").read_text()))
seen = set()
for target in inventory["targets"]:
    name = target["service"]
    if target["target"] in seen or not (ROOT / "deploy/prod/services" / f"{name}.sh").is_file():
        raise SystemExit(f"Duplicate or missing deployment unit: {name}")
    seen.add(target["target"])
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", target["target"]):
        raise SystemExit(f"Invalid deployment target: {name}")
print(f"Shell/Python syntax and {len(seen)} inventory targets valid")
