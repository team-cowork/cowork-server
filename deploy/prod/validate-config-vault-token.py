#!/usr/bin/env python3
"""Reject root, broadly scoped, or long-lived production Config Server Vault tokens."""
import json
import os
import sys
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Vault redirects are not allowed")


try:
    host = os.environ["VAULT_EXTERNAL_HOST"]
    port = int(os.environ.get("VAULT_PORT", "443"))
    if any(char in host for char in "/@?#:") or not host or not 1 <= port <= 65535:
        raise ValueError("Invalid Vault endpoint")
    request = urllib.request.Request(f"https://{host}:{port}/v1/auth/token/lookup-self",
                                     headers={"X-Vault-Token": os.environ["VAULT_TOKEN"]})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=10) as response:
        token = json.load(response)["data"]
    expected = "cowork-config-" + os.environ["APP_CONFIG_PROFILE"]
    if (set(token.get("policies", [])) != {expected} or token.get("identity_policies") or
            not 600 <= token.get("ttl", 0) <= 86400):
        raise ValueError("Use the Config Server policy alone and a token with 10 minutes to 24 hours remaining")
except Exception:
    print("[config-access] Vault token validation failed; check its policy, TTL and HTTPS endpoint", file=sys.stderr)
    sys.exit(1)
