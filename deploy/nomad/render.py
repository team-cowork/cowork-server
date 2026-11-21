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
CATALOG = ROOT / "deploy/tofu/modules/catalog/main.tf"
FAKE_IMAGE = "example.invalid/cowork-validation@sha256:" + "0" * 64
DATABASE_SUFFIXES = ("-mysql", "-postgresql", "-mongodb", "-surrealdb")
# HCL block labels cannot use variables, so shared jobs substitute the service name in the text.
SERVICE = "__SERVICE__"
TEMPLATE_SERVICES = {
    "data/databases/__SERVICE__-mysql.nomad.hcl": ("authorization", "channel", "project", "roadmap", "team", "user"),
    "data/databases/__SERVICE__-mongodb.nomad.hcl": ("chat", "voice"),
}


def job_files():
    """Return (job path, job text, file to edit); template jobs point at their template file."""
    files = [(path, path.read_text(), path) for path in NOMAD.rglob("*.nomad.hcl") if SERVICE not in path.name]
    for template, services in TEMPLATE_SERVICES.items():
        text = (NOMAD / template).read_text()
        files += [(NOMAD / template.replace(SERVICE, service), text.replace(SERVICE, service), NOMAD / template)
                  for service in services]
    return sorted(files)


def file_map(directory):
    return {str(path.relative_to(directory)): path.read_text() for path in sorted(directory.rglob("*"))
            if path.is_file() and "__pycache__" not in path.parts and path.name != "environment.json"}


def environment(service):
    return json.loads((RUNTIME / service / "environment.json").read_text())


def job_inputs(path, text, manifest=None, vault_mount="secret", gateway_host_port=21000):
    name = path.name.removesuffix(".nomad.hcl")
    declared = set(re.findall(r'^variable "([^"]+)"', text, re.MULTILINE))
    values = {"image": FAKE_IMAGE, "init_image": FAKE_IMAGE, "envoy_image": FAKE_IMAGE,
              "gateway_host_port": gateway_host_port,
              "secret_version": 1, "vault_mount": vault_mount, "runtime_env": {}, "config_files": {}}
    if "apps" in path.relative_to(NOMAD).parts:
        values["runtime_env"] = environment(name)
    elif name.endswith(DATABASE_SUFFIXES):
        scripts = ["mongodb-init.sh", "mongodb-init.js"] if name.endswith("-mongodb") else [
            "postgres-init.sh" if name.endswith("-postgresql") else "mysql-init.sh"]
        values["config_files"] = {name: (RUNTIME / "databases" / name).read_text() for name in scripts}
    elif name.endswith("-seaweedfs"):
        values["runtime_env"] = environment(name.removesuffix("-seaweedfs"))
        values["config_files"] = file_map(RUNTIME / "seaweedfs")
    elif name == "chat-elasticsearch":
        values["config_files"] = file_map(RUNTIME / "elasticsearch")
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
        elif name.endswith(DATABASE_SUFFIXES):
            values["secret_version"] = manifest["data_secret_versions"][name.rsplit("-", 1)[0]]
        elif name.endswith("-seaweedfs"):
            values["secret_version"] = manifest["data_secret_versions"][name.removesuffix("-seaweedfs") + "/seaweedfs"]
        elif name == "chat-elasticsearch":
            values.update(image=manifest["infrastructure_images"][name],
                          secret_version=manifest["data_secret_versions"]["chat/elasticsearch"])
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
    stages = {stage: {} for stage in ("data", "apps", "edge")}
    with tempfile.TemporaryDirectory(prefix="cowork-nomad-") as directory:
        for path, text, source in job_files():
            origin = source.relative_to(ROOT)
            values = job_inputs(path, text, manifest, vault_mount, gateway_host_port)
            variables = Path(directory) / "inputs.hcl"
            variables.write_text("\n".join(f"{key} = {json.dumps(hcl_value(value))}"
                                             for key, value in values.items()))
            jobspec = Path(directory) / path.name
            jobspec.write_text(text)
            if validate:
                formatted = subprocess.run([nomad, "fmt", "-check", str(jobspec)], capture_output=True, text=True)
                if formatted.returncode:
                    output = (formatted.stderr or formatted.stdout).replace(str(jobspec), str(origin))
                    raise ValueError(f"Run nomad fmt on {origin}: {output}")
                command = [nomad, "job", "validate", "-address=http://127.0.0.1:1"]
            else:
                command = [nomad, "job", "run", "-output"]
            result = subprocess.run([*command, f"-var-file={variables}", str(jobspec)],
                                    capture_output=True, text=True)
            if result.returncode:
                raise ValueError(f"{origin}: " + (result.stderr or result.stdout).replace(str(jobspec), str(origin)))
            name = path.name.removesuffix(".nomad.hcl")
            job_id = "cowork-" + name
            stage = path.relative_to(NOMAD).parts[0]
            stage = "edge" if name == "gateway" else "data" if stage == "platform" else stage
            if job_id in stages[stage]:
                raise ValueError("Duplicate Nomad job ID: " + job_id)
            stages[stage][job_id] = {
                "jobspec": text,
                "variables": {key: value if isinstance(value, str) else tofu_json(hcl_value(value))
                              for key, value in values.items()},
            }
            if not validate:
                payload = json.loads(result.stdout)
                job = payload.get("Job", payload)
                if job["ID"] != job_id or job_id in specs:
                    raise ValueError(f"Nomad job ID must match its unique filename: {origin}")
                specs[job_id] = job
    # Template jobs render only the services in TEMPLATE_SERVICES, so a catalog job without one must fail here.
    declared = set(re.findall(r'^\s*"(cowork-[^"]+)"\s*:\s*"nomad/', CATALOG.read_text(), re.MULTILINE))
    if not declared:
        raise ValueError(f"No Nomad jobs found in catalog: {CATALOG.relative_to(ROOT)}")
    missing = declared - {job_id for jobs in stages.values() for job_id in jobs}
    if missing:
        raise ValueError("Catalog jobs without a rendered Nomad job: " + ", ".join(sorted(missing)))
    return specs, stages
