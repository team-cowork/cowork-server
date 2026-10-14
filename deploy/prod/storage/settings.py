#!/usr/bin/env python3
"""Validate public storage routing settings without making network requests."""
import ipaddress
import os
import re
import sys
from urllib.parse import urlsplit


def origin(name, public=False):
    value = os.environ[name]
    endpoint = urlsplit(value)
    if (endpoint.scheme not in ("http", "https") or not endpoint.hostname or
            endpoint.username is not None or endpoint.password is not None or
            endpoint.path not in ("", "/") or endpoint.query or endpoint.fragment or
            any(char.isspace() for char in value)):
        raise ValueError(f"{name} must be an HTTP(S) origin")
    if endpoint.port is not None and not 1 <= endpoint.port <= 65535:
        raise ValueError(f"{name} has an invalid port")
    if public and os.environ.get("APP_CONFIG_PROFILE") == "prod":
        if endpoint.scheme != "https" or endpoint.hostname == "localhost" or endpoint.hostname.endswith(".localhost"):
            raise ValueError(f"{name} must use a public HTTPS origin")
        try:
            address = ipaddress.ip_address(endpoint.hostname)
        except ValueError:
            pass
        else:
            if not address.is_global:
                raise ValueError(f"{name} must use a public address")
    return value.rstrip("/")


def main():
    origin("PUBLIC_API_BASE_URL", public=True)
    bucket = os.environ["S3_BUCKET"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise ValueError("S3_BUCKET must be a valid bucket name")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError) as error:
        print(f"[storage-config] {error}", file=sys.stderr)
        sys.exit(1)
