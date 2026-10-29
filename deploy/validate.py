#!/usr/bin/env python3
"""Validate deployment source and native job specs without starting services."""
import argparse
import ast
import json
import os
import re
import runpy
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / "deploy/runtime"
FAKE_IMAGE = "example.invalid/cowork-validation@sha256:" + "0" * 64


def check_syntax():
    shell_files = sorted((ROOT / "deploy").rglob("*.sh"))
    python_files = sorted((ROOT / "deploy").rglob("*.py"))
    for path in shell_files:
        subprocess.run(["bash", "-n", str(path)], check=True)
    for path in python_files:
        ast.parse(path.read_text(), filename=str(path))
    for path in sorted((ROOT / "deploy").rglob("*.json")):
        json.loads(path.read_text())
    print(f"Deployment syntax valid: {len(shell_files)} shell and {len(python_files)} Python files; JSON parsed")


def check_compose():
    # Override interpolation values; neither the user's .env nor secret values are needed.
    env = {key: value for key, value in os.environ.items() if not key.startswith("COMPOSE_")}
    sources = [ROOT / "docker-compose.yml", *sorted((ROOT / "deploy/compose").glob("*.yaml")),
               ROOT / "deploy/runtime/vault/compose.yaml"]
    text = "\n".join(path.read_text() for path in sources)
    for name in set(re.findall(r"(?<!\$)\$\{([A-Za-z_][A-Za-z0-9_]*)", text)):
        env[name] = "validation-only"
    env.update(MYSQL_HOST_PORT="3306", KAFKA_EXTERNAL_HOST_PORT="9094", KAFKA_UI_HOST_PORT="8091", COWORK_PROJECT_HOST_PORT="8084",
               LIVEKIT_CONFIG_FILE="livekit.yaml", APP_CONFIG_PROFILE="local", VAULT_PORT="8200",
               MYSQL_PORT="3306", REDIS_PORT="6379", DOCKER_CONTAINER_LOG_DIR="/var/lib/docker/containers")
    for name, file in {"local": "docker-compose.yml", "vault": "deploy/runtime/vault/compose.yaml"}.items():
        command = ["docker", "compose", "--project-directory", str(ROOT), "--env-file", "/dev/null",
                   "-p", "cowork-validation", "--profile", "*", "-f", str(ROOT / file), "config", "--quiet"]
        subprocess.run(command, env=env, check=True)
        print(f"{name}: Compose configuration valid")


def file_map(directory):
    return {str(path.relative_to(directory)): path.read_text() for path in sorted(directory.rglob("*"))
            if path.is_file() and "__pycache__" not in path.parts and path.name != "environment.json"}


def environment(service):
    return json.loads((RUNTIME / service / "environment.json").read_text())


def job_inputs(path, manifest=None, vault_mount="secret"):
    name = path.name.removesuffix(".nomad.hcl")
    declared = set(re.findall(r'^variable "([^"]+)"', path.read_text(), re.MULTILINE))
    values = {"image": FAKE_IMAGE, "init_image": FAKE_IMAGE, "envoy_image": FAKE_IMAGE,
              "secret_version": 1, "vault_mount": vault_mount, "runtime_env": {}, "config_files": {}}
    if path.parent.name == "apps":
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
    elif name in {"kafka", "monitoring"}:
        values["config_files"] = file_map(RUNTIME / name)
        if name == "monitoring":
            values["runtime_env"] = environment(name)
    elif name not in {"redis", "livekit"}:
        raise ValueError(f"Register native validation inputs for {path.relative_to(ROOT)}")
    if manifest is not None:
        values["envoy_image"] = json.loads((ROOT / "deploy/production/versions.json").read_text())["envoy"]["image"]
        if path.parent.name == "apps":
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


def check_nomad(manifest=None, render_json=None, vault_mount="secret"):
    nomad = os.environ.get("NOMAD_BIN") or shutil.which("nomad")
    if not nomad:
        raise ValueError("Nomad CLI is required for native offline validation; install the pinned version or set NOMAD_BIN")
    paths = sorted((ROOT / "deploy/nomad").rglob("*.nomad.hcl"))
    rendered_jobs = {}
    with tempfile.TemporaryDirectory(prefix="cowork-nomad-validate-") as directory:
        for path in paths:
            formatted = subprocess.run([nomad, "fmt", "-check", str(path)], capture_output=True, text=True)
            if formatted.returncode:
                raise ValueError(f"Run nomad fmt on {path.relative_to(ROOT)}: {formatted.stderr or formatted.stdout}")
            variables = Path(directory) / "inputs.hcl"
            variables.write_text("\n".join(f"{key} = {json.dumps(hcl_value(value))}"
                                         for key, value in job_inputs(path, manifest, vault_mount).items()))
            command = [nomad, "job", "validate", "-address=http://127.0.0.1:1", f"-var-file={variables}", str(path)]
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode:
                raise ValueError(f"{path.relative_to(ROOT)}: {result.stderr or result.stdout}")
            if render_json is not None:
                command = [nomad, "job", "run", "-output", f"-var-file={variables}", str(path)]
                result = subprocess.run(command, capture_output=True, text=True)
                if result.returncode:
                    raise ValueError(f"Cannot render {path.relative_to(ROOT)}: {result.stderr}")
                payload = json.loads(result.stdout)
                job = payload.get("Job", payload)
                if job["ID"] in rendered_jobs:
                    raise ValueError(f"Duplicate Nomad job ID: {job['ID']}")
                rendered_jobs[job["ID"]] = job
    if render_json is not None:
        render_json.parent.mkdir(parents=True, exist_ok=True)
        render_json.write_text(json.dumps(rendered_jobs, indent=2, sort_keys=True) + "\n")
        print(f"Native parsed jobs written to {render_json}")
    references = "release manifest references" if manifest is not None else "placeholder immutable references"
    print(f"{len(paths)} native Nomad jobs validated offline with real runtime files and {references}")
    print("Docker driver/runtime availability requires the deployment environment; no containers or jobs were started")


def main(args):
    os.chdir(ROOT)
    if not args.render_only:
        check_syntax()
        check_compose()
    manifest = None
    if args.manifest is not None:
        manifest = json.loads(args.manifest.read_text())
        release = runpy.run_path(str(ROOT / "deploy/production/release.py"))
        release["validate_manifest"](manifest)
        for name, app in manifest["applications"].items():
            if app["data_secret_version"] != manifest["data_secret_versions"][name]:
                raise ValueError(f"Manifest data secret version differs for {name}")
    check_nomad(manifest, args.render_json, args.vault_mount)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=lambda value: Path(value).resolve(), help="Validate committed image/secret references")
    parser.add_argument("--render-json", type=lambda value: Path(value).resolve(), help="Write {jobID: native parsed job} without submitting jobs")
    parser.add_argument("--render-only", action="store_true", help="Validate and render release jobs without local Compose or repository-wide checks")
    parser.add_argument("--vault-mount", default=os.environ.get("VAULT_KV_MOUNT", "secret"))
    args = parser.parse_args()
    if args.render_json is not None and args.manifest is None:
        parser.error("--render-json requires --manifest")
    if args.render_only and (args.manifest is None or args.render_json is None):
        parser.error("--render-only requires --manifest and --render-json")
    try:
        main(args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
