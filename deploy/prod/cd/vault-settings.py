#!/usr/bin/env python3
"""Resolve release-slot deployment snapshots from Vault or offline recovery inputs."""
import argparse
import base64
import json
import hashlib
import os
import re
import runpy
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid
from pathlib import Path

NAME = re.compile(r"[a-z][a-z0-9-]{0,63}\Z")
SLOTS = runpy.run_path(str(Path(__file__).resolve().parents[1] / "slots.py"))
AUTHENTICATE = runpy.run_path(str(Path(__file__).with_name("vault-auth.py")))["authenticate"]
_workload_token = None


def authenticate_process(role):
    """Keep the workload token in memory, outside Actions and child-process environments."""
    global _workload_token
    _workload_token = AUTHENTICATE(os.environ, role)["VAULT_TOKEN"]
    os.environ.pop("ACTIONS_ID_TOKEN_REQUEST_TOKEN", None)
    os.environ.pop("ACTIONS_ID_TOKEN_REQUEST_URL", None)


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        demand(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def parse_json(value):
    return json.loads(value, object_pairs_hook=object_pairs)


def validate_deployment(data, target, slot):
    demand(NAME.fullmatch(target) and isinstance(slot, dict), "A deployment requires a release slot")
    demand(isinstance(data, dict) and set(data) <= {"ssh", "variables", "runtime", "application"}, "Invalid deployment sections")
    ssh = data.get("ssh", {})
    demand(isinstance(ssh, dict) and set(ssh) == {"host", "port", "user", "key", "fingerprint"}, "ssh requires host, port, user, key, fingerprint")
    for key in ("host", "user", "key", "fingerprint"):
        demand(isinstance(ssh[key], str) and ssh[key].strip() and "\0" not in ssh[key], "Invalid SSH field")
    demand(isinstance(ssh["port"], int) and not isinstance(ssh["port"], bool) and 1 <= ssh["port"] <= 65535, "Invalid SSH port")
    demand(ssh["fingerprint"].startswith("SHA256:"), "An SSH SHA256 host fingerprint is required")
    if "variables" in data:
        SLOTS["validate_variables"](data["variables"])
        if slot["kind"] != "infrastructure":
            demand(data["variables"].get("APP_CONFIG_PROFILE") in {"local", "prod"},
                   "An application snapshot requires APP_CONFIG_PROFILE")
        demand(not set(data["variables"]).intersection(data.get("runtime", {}))
               and not set(data["variables"]).intersection(data.get("application", {})),
               "Variable and secret environment names must not overlap")
    for section in ("runtime", "application"):
        values = data.get(section, {})
        demand(isinstance(values, dict), "Environment section must be an object")
        for key, value in values.items():
            demand(SLOTS["environment_key"](key), "Invalid or reserved environment key")
            demand(isinstance(value, str) and "\0" not in value, "Environment values must be strings without NUL")
            if section == "runtime":
                allowed = set(slot.get("secrets", {}).get("runtime", {}))
                demand(key in allowed, f"Unsupported runtime key: {key}")
    demand(not set(data.get("runtime", {})).intersection(data.get("application", {})),
           "Duplicate secret environment key")
    if slot["kind"] == "infrastructure":
        demand(not data.get("application"), "Infrastructure units use runtime settings only")
    return data


class VaultHTTPError(ValueError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"Vault request failed (HTTP {status}); check policy, path and CAS version")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Vault redirects are not allowed; use its canonical HTTPS address")


def vault_request(path, body=None, *, allow_missing=False, method=None):
    address = os.environ["VAULT_ADDR"].rstrip("/")
    parsed = urlsplit(address)
    demand(parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.query and not parsed.fragment,
           "VAULT_ADDR must be a canonical HTTPS address")
    token = _workload_token or os.environ["VAULT_TOKEN"]
    demand(bool(token), "VAULT_TOKEN is required")
    headers = {"X-Vault-Token": token, "Content-Type": "application/json"}
    request = urllib.request.Request(address + "/v1/" + path, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
    # Never print response bodies: Vault errors may include sensitive configuration.
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            return json.load(response) if response.status != 204 else None
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        raise VaultHTTPError(error.code) from None


def read_document(path, version=0, allow_missing=False, mount=None):
    demand(isinstance(path, str) and all(NAME.fullmatch(part) for part in path.split("/")), "Invalid Vault document path")
    demand(type(version) is int and version >= 0, "Invalid Vault document version")
    mount = mount or os.environ.get("VAULT_KV_MOUNT", "secret")
    demand(NAME.fullmatch(mount), "Invalid KV mount")
    suffix = f"?version={version}" if version else ""
    result = vault_request(f"{mount}/data/{path}{suffix}", allow_missing=allow_missing)
    return result["data"] if result is not None else None


def validate_versions(versions):
    demand(isinstance(versions, dict) and all(
        isinstance(path, str) and all(NAME.fullmatch(part) for part in path.split("/"))
        and type(version) is int and version > 0 for path, version in versions.items()),
        "Invalid pinned Vault reference versions")
    return versions


def resolved_snapshot(target, slot, mount=None, reference_versions=None, require_variables=False, configuration=None):
    """Resolve variables and secrets once, retaining the exact KV versions used."""
    pinned = validate_versions(reference_versions or {})
    result = {"runtime": {}, "application": {}}
    versions = {}
    configuration = configuration or Path(__file__).resolve().parents[1] / "configuration.json"
    catalog = parse_json(configuration.read_text())
    runtime_path = f"runtime/{target}"
    managed = target in catalog.get("targets", {})
    runtime = None
    if managed:
        runtime = read_document(runtime_path, pinned.get(runtime_path, 0),
                                allow_missing=not require_variables and runtime_path not in pinned, mount=mount)
    if runtime is not None:
        variables = runtime["data"]
        SLOTS["validate_variables"](variables)
        declared = set(catalog["targets"][target].get("variables", {}))
        declared.update(key for key, spec in catalog.get("shared", {}).items()
                        if spec["kind"] == "variable" and target in spec["targets"])
        demand(set(variables) == declared, "The runtime snapshot must contain exactly its declared GitHub variables")
        # Config credentials are declared together by the slot. Its referenced
        # username is authoritative; avoid duplicating it in ordinary variables.
        secret_names = {key for section in slot.get("secrets", {}).values() for key in section}
        result["variables"] = {key: value for key, value in variables.items() if key not in secret_names}
        versions[runtime_path] = runtime["metadata"]["version"]
    references = {}
    for section in ("runtime", "application"):
        for key, ref in slot.get("secrets", {}).get(section, {}).items():
            path = ref["path"]
            if path not in references:
                references[path] = read_document(path, pinned.get(path, 0), mount=mount)
            document = references[path]
            result[section][key] = document["data"][ref["key"]]
            versions[path] = document["metadata"]["version"]
    if runtime is not None:
        declarations = list(catalog.get("targets", {}).get(target, {}).get("secrets", {}).items())
        declarations.extend((key, spec) for key, spec in catalog.get("shared", {}).items()
                            if spec["kind"] == "secret" and target in spec["targets"])
        profile = runtime["data"].get("APP_CONFIG_PROFILE", "prod")
        for key, spec in declarations:
            if not spec.get("environment") or key in result["runtime"] or key in result["application"]:
                continue
            refs = [ref for ref in spec["writes"] if ref.get("target", target) == target]
            demand(refs, "An application secret requires a matching Vault write")
            ref = refs[0]
            path = ref["path"].replace("{profile}", profile)
            if path not in references:
                references[path] = read_document(path, pinned.get(path, 0), mount=mount)
            document = references[path]
            result["application"][key] = document["data"][ref["key"]]
            versions[path] = document["metadata"]["version"]
    result["secret_versions"] = versions
    return result


def mask(value):
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    if isinstance(value, dict):
        for child in value.values():
            mask(child)
    elif isinstance(value, str) and value:
        escaped = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::add-mask::{escaped}")


def output(name, value, secret=False):
    value = str(value)
    if secret:
        mask(value)
    delimiter = uuid.uuid4().hex
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"{name}<<{delimiter}\n{value}\n{delimiter}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("read", "bootstrap"))
    parser.add_argument("--target", required=True)
    parser.add_argument("--version", type=int, default=0)
    parser.add_argument("--slot-directory", type=Path, default=Path(__file__).resolve().parents[1] / "slots")
    parser.add_argument("--reference-versions", default="{}", help="JSON map of pinned referenced Vault document versions")
    args = parser.parse_args()
    pinned = validate_versions(parse_json(args.reference_versions))
    demand(NAME.fullmatch(args.target), "Invalid target name")
    mount = os.environ.get("VAULT_KV_MOUNT", "secret")
    demand(NAME.fullmatch(mount), "Invalid KV mount")
    demand(args.version >= 0, "Version must be nonnegative")
    api_path = f"{mount}/data/deploy/{args.target}"
    slot_path = args.slot_directory / f"{args.target}.json"
    slot = SLOTS["load"](args.target, args.slot_directory)
    if args.action == "bootstrap":
        demand(args.target == "vault" and slot["service"] == "vault" and slot["kind"] == "infrastructure",
               "Offline recovery is restricted to the Vault slot")
        data = validate_deployment(parse_json(os.environ["VAULT_BOOTSTRAP_JSON"]), args.target, slot)
        demand(set(data) == {"ssh", "runtime"}
               and set(data["runtime"]) == set(slot.get("secrets", {}).get("runtime", {}))
               and not slot.get("secrets", {}).get("application"),
               "Recovery inputs must supply SSH credentials and exactly the Vault slot secrets")
        demand(all(data["runtime"].values()), "Vault recovery secrets must be nonempty")
        version = "bootstrap"
        reference_versions = {}
    else:
        authenticate_process("prod-deploy-vault" if args.target == "vault" else "prod-settings-" + args.target)
        suffix = f"?version={args.version}" if args.version else ""
        result = vault_request(api_path + suffix)["data"]
        data = validate_deployment(result["data"], args.target, slot)
        version = result["metadata"]["version"]
        demand(set(data) == {"ssh"}, "Deployment documents must contain SSH credentials only")
        configuration = args.slot_directory.parent / "configuration.json"
        managed = args.target in parse_json(configuration.read_text()).get("targets", {})
        snapshot = resolved_snapshot(args.target, slot, mount, pinned, require_variables=managed,
                                     configuration=configuration)
        reference_versions = snapshot.pop("secret_versions")
        data.update(snapshot)
        validate_deployment(data, args.target, slot)
    mask(data)
    for key, value in data.pop("ssh").items():
        output("ssh_" + key, value, secret=True)
    data["vault_version"] = version
    data["secret_versions"] = reference_versions
    data["slot_digest"] = hashlib.sha256(slot_path.read_bytes()).hexdigest()
    encoded = json.dumps(data).encode()
    demand(len(encoded) <= 65536, "Deployment snapshot exceeds the SSH environment size budget")
    output("payload", base64.b64encode(encoded).decode(), secret=True)
    print(f"Loaded deployment target {args.target}, Vault version {version}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, KeyError, OSError) as error:
        # JSON error text contains positions, not the input document.
        print(f"[vault-settings] {error}", file=sys.stderr)
        sys.exit(1)
