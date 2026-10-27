#!/usr/bin/env python3
"""Bootstrap deployment trust with an explicitly supplied operator Vault token."""
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit

import hvac
import requests

from vault_client import read, write

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "team-cowork/cowork-server"
REPOSITORY_ID = "1208653612"
WORKFLOW = REPOSITORY + "/.github/workflows/cowork-prod-cd.yml@refs/heads/main"


def origin(value):
    value = value.rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("Platform endpoints must be HTTPS origins without credentials")
    return value


def token_file(path):
    value = path.read_text().strip()
    if not value or any(char in value for char in "\0\r\n"):
        raise ValueError("Invalid bootstrap token file")
    return value


def platform_request(address, ca, header, token, method, path, body=None, missing=False):
    response = requests.request(method, address + "/v1/" + path, json=body,
                                headers={header: token}, verify=str(ca), timeout=30,
                                allow_redirects=False)
    if missing and response.status_code == 404:
        return None
    if not 200 <= response.status_code < 300:
        raise RuntimeError("Platform ACL request failed (HTTP " + str(response.status_code) + ")")
    return response.json() if response.content else None


def policy(vault, name, paths):
    source = "\n".join(f"path {json.dumps(path)} {{ capabilities = {json.dumps(capabilities)} }}"
                       for path, capabilities in sorted(paths.items()))
    vault.sys.create_or_update_policy(name=name, policy=source)


def github_role(vault, name, environment, audience):
    vault.auth.jwt.create_role(
        name=name, path="github-actions", role_type="jwt", user_claim="repository_id", allowed_redirect_uris=[],
        bound_audiences=[audience],
        bound_subject=f"repo:{REPOSITORY}:environment:{environment}",
        bound_claims={"repository_id": REPOSITORY_ID, "ref": "refs/heads/main",
                      "workflow_ref": WORKFLOW, "environment": environment},
        token_policies=[name], token_no_default_policy=True, token_type="service",
        token_ttl=1200, token_max_ttl=1200, token_explicit_max_ttl=1200,
    )


def main():
    state = Path(os.environ.get("COWORK_PLATFORM_STATE_DIR") or os.environ["PLATFORM_STATE_DIR"]).resolve()
    if not state.is_dir() or state.is_relative_to(ROOT):
        raise ValueError("Bootstrap state must exist outside the checkout")
    ca = state / "pki/ca.crt"
    address = origin(os.environ["VAULT_ADDR"])
    vault_token = os.environ["VAULT_TOKEN"]
    if not vault_token or any(char in vault_token for char in "\0\r\n"):
        raise ValueError("Supply the operator Vault token through the environment")
    vault = hvac.Client(url=address, token=vault_token,
                        verify=os.environ.get("VAULT_CACERT") or True, timeout=30,
                        allow_redirects=False)
    if not vault.sys.is_initialized() or vault.sys.is_sealed():
        raise ValueError("Existing Vault must be initialized and unsealed before bootstrap")
    mount = os.environ.get("VAULT_KV_MOUNT", "secret")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", mount):
        raise ValueError("Invalid Vault KV mount")
    catalog = json.loads((ROOT / "deploy/production/services.json").read_text())
    mounts = vault.sys.list_mounted_secrets_engines()["data"]
    existing = mounts.get(mount + "/")
    if not existing:
        vault.sys.enable_secrets_engine(backend_type="kv", path=mount, options={"version": "2"})
    elif existing["type"] != "kv" or existing.get("options", {}).get("version") != "2":
        raise ValueError("The application secret mount exists but is not KV v2; bootstrap never replaces it")
    for name in ("nomad", "consul"):
        existing = mounts.get(name + "/")
        if existing and existing["type"] != name:
            raise ValueError("Secret engine mount has a different type: " + name)
        if not existing:
            vault.sys.enable_secrets_engine(backend_type=name, path=name)

    methods = vault.sys.list_auth_methods()["data"]
    for name in ("github-actions", "jwt-nomad"):
        existing = methods.get(name + "/")
        if existing and existing["type"] != "jwt":
            raise ValueError("Auth mount has a different type: " + name)
        if not existing:
            vault.sys.enable_auth_method(method_type="jwt", path=name)
    # OpenTofu owns jwt-nomad configuration, workload roles and cowork-* policies.
    vault.auth.jwt.configure(path="github-actions",
                             oidc_discovery_url="https://token.actions.githubusercontent.com",
                             bound_issuer="https://token.actions.githubusercontent.com",
                             jwt_supported_algs=["RS256"])
    audience = origin(os.environ.get("VAULT_OIDC_AUDIENCE", "https://cowork-vault.https.gsmsv.site"))
    deployment = {
        "nomad/creds/deployment": ["read"],
        "consul/creds/deployment": ["read"],
        "sys/policies/acl/cowork-*": ["create", "read", "update", "delete"],
        "auth/jwt-nomad/config": ["create", "read", "update"],
        "auth/jwt-nomad/role/cowork-*": ["create", "read", "update", "delete"],
        "auth/token/lookup-self": ["read"],
        "auth/token/revoke-self": ["update"],
    }
    for service, spec in catalog.items():
        paths = {}
        prefixes = [f"nomad/apps/{service}", f"nomad/data/{service}"]
        prefixes += [f"nomad/data/{service}/{group}" for group in spec.get("data_secret_groups", {})]
        for prefix in prefixes:
            paths[f"{mount}/data/{prefix}"] = ["create", "read", "update"]
            paths[f"{mount}/metadata/{prefix}"] = ["read"]
            deployment[f"{mount}/data/{prefix}"] = ["read"]
            deployment[f"{mount}/metadata/{prefix}"] = ["read"]
        name = "prod-settings-" + service
        policy(vault, name, paths)
        github_role(vault, name, spec["environment"], audience)
    # Keep the deployment policy outside the workload policy name prefix.
    policy(vault, "prod-iac", deployment)
    github_role(vault, "prod-iac", "Prod-IaC", audience)

    nomad_server = origin(os.environ.get("PLATFORM_NOMAD_ADDR", "https://10.0.0.111:4646"))
    consul_server = origin(os.environ.get("PLATFORM_CONSUL_ADDR", "https://10.0.0.111:8501"))
    nomad_address = origin(os.environ.get("NOMAD_ADDR", nomad_server))
    consul_address = origin(os.environ.get("CONSUL_HTTP_ADDR", consul_server))
    nomad_token = token_file(state / "nomad-bootstrap.token")
    consul_token = token_file(state / "consul-bootstrap.token")
    nomad_rules = (ROOT / "deploy/ansible/files/nomad-deploy-policy.hcl").read_text()
    platform_request(nomad_address, ca, "X-Nomad-Token", nomad_token, "POST", "acl/policy/cowork-deploy",
                     {"Name": "cowork-deploy", "Description": "Cowork production deployments", "Rules": nomad_rules})
    consul_rules = '''service_prefix "cowork-" {
  policy = "write"
  intentions = "write"
}
node_prefix "" { policy = "read" }
agent_prefix "" { policy = "read" }
key_prefix "cowork/tofu/" { policy = "write" }
session_prefix "" { policy = "write" }
mesh = "write"
acl = "write"
'''
    previous = platform_request(consul_address, ca, "X-Consul-Token", consul_token,
                                "GET", "acl/policy/name/cowork-deploy", missing=True)
    policy_path = "acl/policy" + ("/" + previous["ID"] if previous else "")
    platform_request(consul_address, ca, "X-Consul-Token", consul_token, "PUT", policy_path,
                     {"Name": "cowork-deploy", "Description": "Cowork production deployments", "Rules": consul_rules})
    # Management credentials are sent directly to the encrypted engine configuration,
    # never to Terraform state, generated configuration or command arguments.
    vault.write("nomad/config/access", address=nomad_server, token=nomad_token, ca_cert=ca.read_text())
    vault.write("nomad/config/lease", ttl="20m", max_ttl="20m")
    vault.write("nomad/role/deployment", policies="cowork-deploy", type="client", **{"global": False})
    vault.write("consul/config/access", address=urlsplit(consul_server).netloc, scheme="https",
                token=consul_token, ca_cert=ca.read_text())
    vault.write("consul/roles/deployment", consul_policies=["cowork-deploy"], local=True,
                ttl="20m", max_ttl="20m")

    monitoring = token_file(state / "tokens/monitoring-read.consul")
    for attempt in range(3):
        current = read(vault, "nomad/apps/monitoring", mount)
        try:
            write(vault, "nomad/apps/monitoring", {**current[0], "CONSUL_HTTP_TOKEN": monitoring}, mount, current)
            break
        except hvac.exceptions.InvalidRequest:
            if attempt == 2:
                raise
    devices = vault.sys.list_enabled_audit_devices()["data"]
    if "file/" not in devices:
        vault.sys.enable_audit_device(device_type="file", options={"file_path": "stdout"}, path="file")
    elif devices["file/"]["type"] != "file" or devices["file/"]["options"].get("file_path") != "stdout":
        raise ValueError("Existing audit device must retain its stdout destination")
    print("Configured scoped GitHub roles, short-lived deployment credentials and monitoring discovery")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit(f"Vault bootstrap failed ({type(error).__name__}); response bodies withheld") from None
