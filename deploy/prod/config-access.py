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


def is_private_ipv4(host):
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.version == 4 and address.is_private and not address.is_loopback and not address.is_unspecified


def validate_url(name):
    endpoint = urlsplit(os.environ[name])
    if (not endpoint.hostname or endpoint.username is not None or
            endpoint.password is not None or endpoint.query or endpoint.fragment):
        raise ValueError(f"{name} must be a canonical URL without credentials")
    if endpoint.scheme == "https":
        return endpoint
    # TLS 없는 구성은 호스트 이름(DNS 변조 가능)이 아니라 사설 IPv4 주소 리터럴일 때만 허용한다.
    if endpoint.scheme == "http" and is_private_ipv4(endpoint.hostname):
        return endpoint
    raise ValueError(f"{name} must be an HTTPS URL, or an HTTP URL with a private IPv4 address")


def main():
    endpoints = [validate_url(name) for name in ("CONFIG_SERVER_URL", "EUREKA_SERVER_URL")]
    # Config Server 배포에서만 설정되는 값: 서버의 TLS 설정과 URL 스킴이 어긋나면 배포 전에 실패시킨다.
    tls = os.environ.get("CONFIG_TLS_ENABLED")
    if tls is not None:
        if tls not in ("true", "false"):
            raise ValueError("CONFIG_TLS_ENABLED must be true or false")
        expected = "https" if tls == "true" else "http"
        if any(endpoint.scheme != expected for endpoint in endpoints):
            raise ValueError(f"CONFIG_SERVER_URL and EUREKA_SERVER_URL must use {expected} when CONFIG_TLS_ENABLED={tls}")
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
