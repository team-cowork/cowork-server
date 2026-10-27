"""Use GitHub's workload identity, or an explicit operator token, with Vault."""
import os
from urllib.parse import urlsplit

import hvac
import requests


def client(role):
    address = os.environ["VAULT_ADDR"].rstrip("/")
    parsed = urlsplit(address)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.path or parsed.query or parsed.fragment:
        raise ValueError("VAULT_ADDR must be an HTTPS origin")
    vault = hvac.Client(url=address, verify=os.environ.get("VAULT_CACERT") or True, timeout=30, allow_redirects=False)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        request_url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]
        if urlsplit(request_url).scheme != "https":
            raise ValueError("GitHub OIDC requires HTTPS")
        response = requests.get(request_url, params={"audience": address}, headers={
            "Authorization": "Bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"],
        }, timeout=30, allow_redirects=False)
        response.raise_for_status()
        token = vault.auth.jwt.jwt_login(role=role, jwt=response.json()["value"], path="github-actions")["auth"]["client_token"]
        print("::add-mask::" + token.replace("%", "%25"), flush=True)
        vault.token = token
    else:
        vault.token = os.environ["VAULT_TOKEN"]
    return vault


def read(vault, path, mount):
    try:
        result = vault.secrets.kv.v2.read_secret_version(path=path, mount_point=mount, raise_on_deleted_version=True)["data"]
        if result["data"] is None:
            raise ValueError("Refusing to overwrite a deleted secret version")
        return result["data"], result["metadata"]["version"]
    except hvac.exceptions.InvalidPath:
        return {}, 0


def write(vault, path, values, mount, previous=None):
    current, version = previous if previous is not None else read(vault, path, mount)
    if version and current == values:
        return version
    return vault.secrets.kv.v2.create_or_update_secret(
        path=path, secret=values, cas=version, mount_point=mount,
    )["data"]["version"]
