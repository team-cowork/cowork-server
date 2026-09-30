#!/usr/bin/env python3
"""Validate and render the Config Server's Docker ingress policy (no secret output)."""
import ipaddress
import os
import sys
from urllib.parse import urlsplit


def trusted_networks():
    values = os.environ["CONFIG_ALLOWED_CIDRS"].split(",")
    networks = [ipaddress.ip_network(value.strip(), strict=True) for value in values]
    if not networks or any(net.version != 4 or not net.is_private or net.is_loopback or net.prefixlen < 8 for net in networks):
        raise ValueError("CONFIG_ALLOWED_CIDRS must contain explicit private IPv4 peer networks")
    return networks


def validate_url(name):
    endpoint = urlsplit(os.environ[name])
    if (endpoint.scheme != "https" or not endpoint.hostname or endpoint.username is not None or
            endpoint.password is not None or endpoint.query or endpoint.fragment):
        raise ValueError(f"{name} must be a canonical HTTPS URL without credentials")
    return endpoint


def main():
    for name in ("CONFIG_SERVER_URL", "EUREKA_SERVER_URL"):
        validate_url(name)
    if len(sys.argv) > 1 and sys.argv[1] == "firewall":
        address = ipaddress.ip_address(os.environ["BIND_IP"])
        if address.version != 4 or not address.is_private or address.is_unspecified or address.is_loopback:
            raise ValueError("Bind production Config Server to a private IPv4 address")
        port = int(os.environ.get("HOST_PORT", "8761"))
        if not 1 <= port <= 65535:
            raise ValueError("Invalid Config Server host port")
        networks = trusted_networks()
        # Only this named chain is replaced; unrelated host rules are preserved.
        print("*filter\n:COWORK_CONFIG - [0:0]\n-F COWORK_CONFIG")
        for network in networks:
            print(f"-A COWORK_CONFIG -s {network} -j RETURN")
        print("-A COWORK_CONFIG -j DROP\nCOMMIT")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError) as error:
        print(f"[config-access] {error}", file=sys.stderr)
        sys.exit(1)
