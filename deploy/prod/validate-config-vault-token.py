#!/usr/bin/env python3
"""Validate production Config AppRole login and renewal; revoke the probe token."""
import json
import os
import re
import sys
import urllib.request


POLICY = "cowork-config-prod"
MIN_TTL = 600
MAX_TTL = 86400


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Vault redirects are not allowed")


def validate_policies(value):
    if set(value.get("policies", [])) != {POLICY} or value.get("identity_policies"):
        raise ValueError("Use the Config Server policy alone")


def validate_ttl(value):
    if type(value) is not int or not MIN_TTL <= value <= MAX_TTL:
        raise ValueError("Use a TTL from 10 minutes to 24 hours")


def validate_auth(auth):
    validate_policies(auth)
    validate_ttl(auth.get("lease_duration"))
    if auth.get("renewable") is not True:
        raise ValueError("Use renewable tokens")


def main():
    stage = "settings"
    probe_token = None
    failed = False
    try:
        if os.environ["APP_CONFIG_PROFILE"] != "prod":
            raise ValueError("Only production AppRole credentials can be validated")
        host = os.environ["VAULT_EXTERNAL_HOST"]
        port = int(os.environ.get("VAULT_PORT", "443"))
        mount = os.environ.get("VAULT_APP_ROLE_PATH") or "approle"
        role_id = os.environ["VAULT_ROLE_ID"]
        secret_id = os.environ["VAULT_SECRET_ID"]
        if (not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host)
                or not 1 <= port <= 65535
                or not re.fullmatch(r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*", mount)
                or not role_id.strip() or not secret_id.strip()):
            raise ValueError("Invalid Vault endpoint or AppRole credentials")

        opener = urllib.request.build_opener(NoRedirect)

        def request(path, body=None, token=None):
            headers = {"Content-Type": "application/json"}
            if token:
                headers["X-Vault-Token"] = token
            req = urllib.request.Request(
                f"https://{host}:{port}/v1/{path}", headers=headers,
                data=None if body is None else json.dumps(body).encode(),
            )
            with opener.open(req, timeout=10) as response:
                return {} if response.status == 204 else json.load(response)

        stage = "AppRole login"
        auth = request(f"auth/{mount}/login", {"role_id": role_id, "secret_id": secret_id})["auth"]
        # Capture the token before validation so rejected tokens are also revoked.
        probe_token = auth["client_token"]
        if not isinstance(probe_token, str) or not probe_token:
            raise ValueError("Missing login token")
        stage = "login policy and lifetime"
        validate_auth(auth)

        stage = "token lookup"
        token = request("auth/token/lookup-self", token=probe_token)["data"]
        validate_policies(token)
        validate_ttl(token.get("ttl"))
        # A hard 24-hour cap bounds each token; AppRole supplies the next token.
        validate_ttl(token.get("explicit_max_ttl"))
        if (token.get("renewable") is not True or token.get("type") != "service"
                or token.get("num_uses") != 0 or token.get("period", 0) != 0):
            raise ValueError("Use non-periodic renewable service tokens without a use limit")

        stage = "token renewal"
        renewed = request("auth/token/renew-self", {}, probe_token)["auth"]
        validate_auth(renewed)
    except Exception:
        # Do not include exceptions, HTTP bodies, headers, or credentials in output.
        print(f"[config-access] Vault AppRole validation failed at {stage}; check Cloud configuration", file=sys.stderr)
        failed = True
    finally:
        if probe_token:
            try:
                request("auth/token/revoke-self", {}, probe_token)
            except Exception:
                print("[config-access] Vault probe token revocation failed; check revoke-self permission and Vault availability",
                      file=sys.stderr)
                failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
