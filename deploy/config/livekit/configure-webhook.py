#!/usr/bin/env python3
"""Render the private Voice webhook and optional mapped RTC configuration."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import stat
from urllib.parse import urlsplit


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate LiveKit configuration field")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Private JSON representation of the current config")
    parser.add_argument("--output", type=Path, required=True, help="New private JSON/YAML config; must not exist")
    parser.add_argument("--url", required=True)
    parser.add_argument("--rtc-public-ip")
    parser.add_argument("--rtc-tcp-port", type=int)
    parser.add_argument("--rtc-udp-port", type=int)
    parser.add_argument("--disable-turn", action="store_true")
    args = parser.parse_args()
    info = args.input.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError("Input must be an owned private regular file")
    config = json.loads(args.input.read_text(), object_pairs_hook=unique_object)
    if not isinstance(config, dict) or not isinstance(config.get("keys"), dict) or not config["keys"]:
        raise ValueError("Existing LiveKit API keys are required")
    if any(not isinstance(key, str) or not key or not isinstance(secret, str) or not secret
           or "${" in key or "${" in secret for key, secret in config["keys"].items()):
        raise ValueError("API keys must already contain actual credentials")
    key = os.environ.get("LIVEKIT_WEBHOOK_API_KEY")
    if not key and len(config["keys"]) == 1:
        key = next(iter(config["keys"]))
    if key not in config["keys"]:
        raise ValueError("Select an existing signing key with LIVEKIT_WEBHOOK_API_KEY")
    endpoint = urlsplit(args.url)
    private = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
    address = ipaddress.ip_address(endpoint.hostname or "")
    if (endpoint.scheme not in {"http", "https"} or address.version != 4
            or not any(address in network for network in private) or endpoint.username or endpoint.password
            or endpoint.query or endpoint.fragment or endpoint.path != "/voice/webhook"
            or endpoint.port is None or not 1 <= endpoint.port <= 65535):
        raise ValueError("Use the explicit private Voice webhook address and port")
    webhook = config.get("webhook", {})
    if not isinstance(webhook, dict):
        raise ValueError("Existing webhook configuration must be an object")
    config["webhook"] = {**webhook, "api_key": key, "urls": [args.url]}
    rtc_options = (args.rtc_public_ip, args.rtc_tcp_port, args.rtc_udp_port)
    if any(value is not None for value in rtc_options):
        if not all(value is not None for value in rtc_options):
            raise ValueError("Supply the public RTC address and both mapped ports together")
        public = ipaddress.IPv4Address(args.rtc_public_ip)
        if not public.is_global or not all(1 <= port <= 65535 for port in rtc_options[1:]):
            raise ValueError("RTC requires a global IPv4 address and valid mapped ports")
        rtc = config.get("rtc", {})
        if not isinstance(rtc, dict):
            raise ValueError("Existing RTC configuration must be an object")
        # The pinned media transport implementation selects an explicit range
        # before UDP mux. Remove both range fields when using a single port.
        rtc.pop("port_range_start", None)
        rtc.pop("port_range_end", None)
        rtc.update(node_ip=str(public), use_external_ip=False,
                   tcp_port=args.rtc_tcp_port, udp_port=args.rtc_udp_port)
        config["rtc"] = rtc
    if args.disable_turn:
        turn = config.get("turn", {})
        if not isinstance(turn, dict):
            raise ValueError("Existing TURN configuration must be an object")
        config["turn"] = {**turn, "enabled": False}
    os.umask(0o077)
    with args.output.open("x") as output:
        os.fchmod(output.fileno(), 0o600)
        # JSON is valid YAML, so no template expansion or YAML dependency is needed.
        json.dump(config, output, indent=2)
        output.write("\n")
    print("LiveKit config prepared; existing credentials retained")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit("LiveKit config preparation failed; inspect the private input and destination")
