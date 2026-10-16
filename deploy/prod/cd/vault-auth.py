#!/usr/bin/env python3
"""Authenticate the current GitHub job to its scoped Vault role."""
import json
import re
import urllib.error
import urllib.request
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


def authenticate(env, role):
    if not re.fullmatch(r"prod-(?:iac|deploy-vault|settings-[a-z][a-z0-9-]*)", role):
        raise ValueError("Invalid Vault workload role")
    if env.get("GITHUB_ACTIONS") != "true":
        if not env.get("VAULT_TOKEN"):
            raise ValueError("Local execution requires an explicit Vault token")
        return dict(env)
    address = urlsplit(env.get("VAULT_ADDR", ""))
    request_url = urlsplit(env.get("ACTIONS_ID_TOKEN_REQUEST_URL", ""))
    for value in (address, request_url):
        if value.scheme != "https" or not value.hostname or value.username or value.password or value.fragment:
            raise ValueError("Vault and GitHub OIDC require authenticated HTTPS")
    if address.query or not env.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN"):
        raise ValueError("Invalid Vault address or missing GitHub OIDC credentials")
    audience = env["VAULT_ADDR"].rstrip("/")
    query = [(k, v) for k, v in parse_qsl(request_url.query, keep_blank_values=True) if k != "audience"]
    query.append(("audience", audience))

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError("Authentication redirects are not allowed")

    opener = urllib.request.build_opener(NoRedirect())
    try:
        request = urllib.request.Request(urlunsplit(request_url._replace(query=urlencode(query))),
            headers={"Authorization": "Bearer " + env["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
        with opener.open(request, timeout=30) as response:
            jwt = json.load(response)["value"]
        request = urllib.request.Request(audience + "/v1/auth/github-actions/login",
            data=json.dumps({"role": role, "jwt": jwt}).encode(), headers={"Content-Type": "application/json"})
        with opener.open(request, timeout=30) as response:
            token = json.load(response)["auth"]["client_token"]
        if not isinstance(token, str) or not token or any(c in token for c in "\r\n\0"):
            raise ValueError("Invalid Vault token response")
    except (OSError, ValueError, KeyError, urllib.error.URLError):
        raise RuntimeError("GitHub OIDC authentication to Vault failed; response bodies were withheld") from None
    print("::add-mask::" + token.replace("%", "%25"), flush=True)
    result = {**env, "VAULT_TOKEN": token}
    result.pop("ACTIONS_ID_TOKEN_REQUEST_TOKEN", None)
    result.pop("ACTIONS_ID_TOKEN_REQUEST_URL", None)
    return result
