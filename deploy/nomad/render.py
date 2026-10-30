"""Build the shared Nomad CLI and OpenTofu inputs from native job declarations."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NOMAD = ROOT / "deploy/nomad"
RUNTIME = ROOT / "deploy/runtime"
FAKE_IMAGE = "example.invalid/cowork-validation@sha256:" + "0" * 64


def file_map(directory):
    return {str(path.relative_to(directory)): path.read_text() for path in sorted(directory.rglob("*"))
            if path.is_file() and "__pycache__" not in path.parts and path.name != "environment.json"}


def environment(service):
    return json.loads((RUNTIME / service / "environment.json").read_text())


def job_inputs(path, manifest=None, vault_mount="secret", gateway_host_port=21000):
    name = path.name.removesuffix(".nomad.hcl")
    declared = set(re.findall(r'^variable "([^"]+)"', path.read_text(), re.MULTILINE))
    values = {"image": FAKE_IMAGE, "init_image": FAKE_IMAGE, "envoy_image": FAKE_IMAGE,
              "gateway_host_port": gateway_host_port,
              "secret_version": 1, "vault_mount": vault_mount, "runtime_env": {}, "config_files": {}}
    if "apps" in path.relative_to(NOMAD).parts:
        values["runtime_env"] = environment(name)
    elif name == "user-migrations":
        values["runtime_env"] = environment("user")
    elif name.endswith("-db"):
        owner = name.removesuffix("-db")
        scripts = ["mongodb-init.sh", "mongodb-init.js"] if owner in {"chat", "voice"} else [
            "postgres-init.sh" if owner == "preference" else "mysql-init.sh"]
        values["config_files"] = {name: (RUNTIME / "databases" / name).read_text() for name in scripts}
    elif name.endswith("-storage"):
        values["runtime_env"] = environment(name.removesuffix("-storage"))
        values["config_files"] = file_map(RUNTIME / "storage")
    elif name == "chat-search":
        values["config_files"] = file_map(RUNTIME / "search")
    elif name == "kafka":
        values["config_files"] = {filename: (RUNTIME / "kafka" / filename).read_text()
                                  for filename in ("server.properties", "start-broker.sh", "policy.json")}
    elif name == "monitoring":
        values["config_files"] = file_map(RUNTIME / name)
        values["runtime_env"] = environment(name)
    elif name not in {"redis", "livekit"}:
        raise ValueError(f"Register native validation inputs for {path.relative_to(ROOT)}")
    if manifest is not None:
        values["envoy_image"] = json.loads((ROOT / "deploy/production/config/versions.json").read_text())["envoy"]["image"]
        if "apps" in path.relative_to(NOMAD).parts:
            values.update(image=manifest["applications"][name]["image"],
                          secret_version=manifest["applications"][name]["secret_version"])
        elif name == "user-migrations":
            values.update(image=manifest["infrastructure_images"][name],
                          secret_version=manifest["data_secret_versions"]["user"])
        elif name.endswith("-db"):
            values["secret_version"] = manifest["data_secret_versions"][name.rsplit("-", 1)[0]]
        elif name.endswith("-storage"):
            values["secret_version"] = manifest["data_secret_versions"][name.removesuffix("-storage") + "/storage"]
        elif name == "chat-search":
            values.update(image=manifest["infrastructure_images"][name],
                          secret_version=manifest["data_secret_versions"]["chat/search"])
        elif name == "kafka":
            values.update(init_image=manifest["infrastructure_images"]["kafka-init"],
                          secret_version=manifest["data_secret_versions"][name])
        elif name == "monitoring":
            values["secret_version"] = manifest["platform_secret_versions"][name]
        elif name == "livekit":
            values["secret_version"] = manifest["data_secret_versions"]["voice/livekit"]
    if not declared <= values.keys():
        raise ValueError(f"Unknown Nomad input variables: {sorted(declared - values.keys())}")
    return {key: values[key] for key in declared}


def hcl_value(value):
    # Nomad parses hcl2 variables as expressions. Preserve literal Grafana/shell interpolation.
    if isinstance(value, str):
        return value.replace("${", "$${").replace("%{", "%%{")
    if isinstance(value, dict):
        return {key: hcl_value(item) for key, item in value.items()}
    return value


def tofu_json(value):
    # Match OpenTofu jsonencode so unchanged HCL2 inputs do not trigger rollouts.
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    for character in ("<", ">", "&", "\u2028", "\u2029"):
        encoded = encoded.replace(character, f"\\u{ord(character):04x}")
    return encoded


def render_jobs(manifest=None, vault_mount="secret", gateway_host_port=21000, *, validate=False):
    nomad = os.environ.get("NOMAD_BIN") or shutil.which("nomad")
    if not nomad:
        raise ValueError("Install the pinned Nomad CLI or set NOMAD_BIN for offline job parsing")
    specs = {}
    stages = {stage: {} for stage in ("data", "apps", "edge", "batch")}
    with tempfile.TemporaryDirectory(prefix="cowork-nomad-") as directory:
        for path in sorted(NOMAD.rglob("*.nomad.hcl")):
            values = job_inputs(path, manifest, vault_mount, gateway_host_port)
            variables = Path(directory) / "inputs.hcl"
            variables.write_text("\n".join(f"{key} = {json.dumps(hcl_value(value))}"
                                             for key, value in values.items()))
            if validate:
                subprocess.run([nomad, "fmt", "-check", str(path)], check=True, capture_output=True, text=True)
                command = [nomad, "job", "validate", "-address=http://127.0.0.1:1"]
            else:
                command = [nomad, "job", "run", "-output"]
            result = subprocess.run([*command, f"-var-file={variables}", str(path)],
                                    capture_output=True, text=True)
            if result.returncode:
                raise ValueError(f"{path.relative_to(ROOT)}: {result.stderr or result.stdout}")
            name = path.name.removesuffix(".nomad.hcl")
            job_id = "cowork-" + name
            stage = path.relative_to(NOMAD).parts[0]
            stage = "edge" if name == "gateway" else "data" if stage == "platform" else stage
            if job_id in stages[stage]:
                raise ValueError("Duplicate Nomad job ID: " + job_id)
            stages[stage][job_id] = {
                "jobspec": path.read_text(),
                "variables": {key: value if isinstance(value, str) else tofu_json(hcl_value(value))
                              for key, value in values.items()},
            }
            if not validate:
                payload = json.loads(result.stdout)
                job = payload.get("Job", payload)
                if job["ID"] != job_id or job_id in specs:
                    raise ValueError(f"Nomad job ID must match its unique filename: {path.relative_to(ROOT)}")
                specs[job_id] = job
    return specs, stages
