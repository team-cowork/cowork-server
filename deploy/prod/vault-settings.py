#!/usr/bin/env python3
"""Read deployment snapshots or CAS-replace configuration in Vault KV v2."""
import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid
from pathlib import Path

NAME = re.compile(r"[a-z][a-z0-9-]{0,63}\Z")
ENV_NAME = re.compile(r"[A-Z][A-Z0-9_]*\Z")
RUNTIME_KEYS = set("""
APP_CONFIG_PROFILE ADVERTISE_IP BIND_IP HOST_PORT HEALTH_TIMEOUT_SECONDS
CONFIG_SERVER_URL EUREKA_SERVER_URL KAFKA_BOOTSTRAP_SERVERS
MYSQL_HOST MYSQL_PORT MYSQL_USER COWORK_MYSQL_PASSWORD
POSTGRES_HOST POSTGRES_PORT POSTGRES_USER COWORK_POSTGRES_PASSWORD
MONGO_HOST MONGO_PORT MONGO_USER COWORK_MONGO_PASSWORD
REDIS_HOST REDIS_PORT ELASTICSEARCH_URL
S3_INTERNAL_ENDPOINT S3_PUBLIC_ENDPOINT S3_PUBLIC_BASE_URL S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY
LIVEKIT_URL LIVEKIT_WS_URL LIVEKIT_API_KEY LIVEKIT_API_SECRET
GITHUB_APP_SERVICE_URL COWORK_GITHUB_APP_INTERNAL_API_KEY PUBLIC_WEB_ORIGIN PUBLIC_API_BASE_URL
JWT_SECRET JWT_ACCESS_EXPIRE JWT_REFRESH_EXPIRE
VAULT_EXTERNAL_HOST VAULT_PORT VAULT_BACKEND VAULT_TOKEN VAULT_BIND_IP VAULT_DATA_VOLUME VAULT_COMPOSE_PROJECT VAULT_UNSEAL_KEY
MONITORING_BIND_IP MONITORING_ADMIN_BIND_IP MONITORING_COMPOSE_PROJECT MONITORING_VOLUME_PREFIX
GRAFANA_ADMIN_PASSWORD DISCORD_WEBHOOK_URL MYSQL_EXPORTER_USER MYSQL_EXPORTER_PASSWORD
KAFKA_EXPORTER_SERVER POSTGRES_EXPORTER_DSN MONGO_EXPORTER_URI
LOG_HOST LOKI_PUSH_URL DOCKER_CONTAINER_LOG_DIR GHCR_READ_TOKEN
""".split())


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


def validate_deployment(data):
    demand(isinstance(data, dict) and set(data) <= {"ssh", "runtime", "application", "files", "runtime_refs", "application_refs"}, "Invalid deployment sections")
    ssh = data.get("ssh", {})
    demand(isinstance(ssh, dict) and set(ssh) == {"host", "port", "user", "key", "fingerprint"}, "ssh requires host, port, user, key, fingerprint")
    for key in ("host", "user", "key", "fingerprint"):
        demand(isinstance(ssh[key], str) and ssh[key].strip() and "\0" not in ssh[key], "Invalid SSH field")
    demand(isinstance(ssh["port"], int) and not isinstance(ssh["port"], bool) and 1 <= ssh["port"] <= 65535, "Invalid SSH port")
    demand(ssh["fingerprint"].startswith("SHA256:"), "An SSH SHA256 host fingerprint is required")
    for section in ("runtime", "application"):
        values = data.get(section, {})
        demand(isinstance(values, dict), "Environment section must be an object")
        for key, value in values.items():
            demand(ENV_NAME.fullmatch(key), "Invalid environment key")
            demand(isinstance(value, str) and "\0" not in value, "Environment values must be strings without NUL")
            if section == "runtime":
                demand(key in RUNTIME_KEYS, f"Unsupported runtime key: {key}")
        references = data.get(section + "_refs", {})
        demand(isinstance(references, dict), "References must be an object")
        for key, reference in references.items():
            demand(ENV_NAME.fullmatch(key) and key not in values, "Invalid or duplicate referenced environment key")
            if section == "runtime":
                demand(key in RUNTIME_KEYS, f"Unsupported runtime key: {key}")
            demand(isinstance(reference, dict) and set(reference) == {"path", "key"}, "A reference requires path and key")
            demand(isinstance(reference["path"], str) and all(NAME.fullmatch(part) for part in reference["path"].split("/")), "Invalid reference path")
            demand(isinstance(reference["key"], str) and reference["key"], "Invalid reference key")
    runtime = data.get("runtime", {})
    if "APP_CONFIG_PROFILE" in runtime:
        demand(runtime["APP_CONFIG_PROFILE"] in {"local", "prod"}, "Profile must be local or prod")
    files = data.get("files", {})
    demand(isinstance(files, dict) and set(files) <= {"firebase-credentials.json"}, "Unsupported file secret")
    if files:
        demand(isinstance(files["firebase-credentials.json"], dict), "Firebase credential must be a JSON object")
    return data


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Vault redirects are not allowed; use its canonical HTTPS address")


def vault_request(path, body=None):
    address = os.environ["VAULT_ADDR"].rstrip("/")
    parsed = urlsplit(address)
    demand(parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.query and not parsed.fragment,
           "VAULT_ADDR must be a canonical HTTPS address")
    token = os.environ["VAULT_TOKEN"]
    demand(bool(token), "VAULT_TOKEN is required")
    headers = {"X-Vault-Token": token, "Content-Type": "application/json"}
    request = urllib.request.Request(address + "/v1/" + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    # Never print response bodies: Vault errors may include sensitive configuration.
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise ValueError(f"Vault request failed (HTTP {error.code}); check policy, path and CAS version") from None


def mask(value):
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
    parser.add_argument("action", choices=("read", "update", "bootstrap"))
    parser.add_argument("--target", required=True)
    parser.add_argument("--scope", choices=("deployment", "application"), default="deployment")
    parser.add_argument("--profile", choices=("base", "local", "prod"), default="base")
    parser.add_argument("--version", type=int, default=0)
    parser.add_argument("--expected-version", type=int)
    args = parser.parse_args()
    demand(NAME.fullmatch(args.target), "Invalid target name")
    mount = os.environ.get("VAULT_KV_MOUNT", "secret")
    demand(NAME.fullmatch(mount), "Invalid KV mount")
    demand(args.version >= 0, "Version must be nonnegative")
    if args.scope == "deployment":
        demand(args.profile == "base", "Deployment documents have no profile suffix")
        path = f"deploy/{args.target}"
    else:
        services = {p.stem for p in Path(__file__).with_name("services").glob("*.sh")}
        demand(args.target == "application" or args.target in services - {"config", "monitoring", "vault", "log-agent"}, "Invalid Config Server application")
        path = "application" if args.target == "application" else f"cowork-{args.target}"
        if args.profile != "base":
            path += "/" + args.profile
    api_path = f"{mount}/data/{path}"
    if args.action == "update":
        demand(args.expected_version is not None and args.expected_version >= 0, "Expected KV version is required (0 creates only)")
        data = parse_json(os.environ["VAULT_UPDATE_JSON"])
        if args.scope == "deployment":
            validate_deployment(data)
        else:
            demand(isinstance(data, dict), "Application properties must be a flat JSON object")
            for key, value in data.items():
                demand(bool(re.fullmatch(r"[A-Za-z0-9_.\[\]-]+", key)), "Invalid application property key")
                demand(isinstance(value, (str, int, float, bool)), "Application properties must be scalar values")
        result = vault_request(api_path, {"options": {"cas": args.expected_version}, "data": data})
        print(f"Updated {path}, Vault version {result['data']['version']}")
        return
    demand(args.scope == "deployment", "Only deployment documents are sent to a VM")
    if args.action == "bootstrap":
        demand(args.target == "vault", "Bootstrap bypass is restricted to Vault recovery")
        data = validate_deployment(parse_json(os.environ["VAULT_BOOTSTRAP_JSON"]))
        demand(not data.get("runtime_refs") and not data.get("application_refs"), "Vault recovery cannot depend on Vault references")
        version = "bootstrap"
    else:
        suffix = f"?version={args.version}" if args.version else ""
        result = vault_request(api_path + suffix)["data"]
        data = validate_deployment(result["data"])
        version = result["metadata"]["version"]
        references = {}
        for section in ("runtime", "application"):
            values = data.setdefault(section, {})
            for key, reference in data.pop(section + "_refs", {}).items():
                path = reference["path"]
                if path not in references:
                    references[path] = vault_request(f"{mount}/data/{path}")["data"]
                    print(f"Loaded referenced configuration {path}, Vault version {references[path]['metadata']['version']}")
                values[key] = references[path]["data"][reference["key"]]
        validate_deployment(data)
    mask(data)
    for key, value in data.pop("ssh").items():
        output("ssh_" + key, value, secret=True)
    data["vault_version"] = version
    encoded = json.dumps(data).encode()
    demand(len(encoded) <= 65536, "Deployment snapshot exceeds the SSH environment size budget")
    output("payload", base64.b64encode(encoded).decode(), secret=True)
    print(f"Loaded deployment target {args.target}, Vault version {version}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        # JSON error text contains positions, not the input document.
        print(f"[vault-settings] {error}", file=sys.stderr)
        sys.exit(1)
