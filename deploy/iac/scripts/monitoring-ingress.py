#!/usr/bin/env python3
"""Build the Loki ingress policy from declared application and infrastructure peers."""
import ipaddress
import json
import runpy
from pathlib import Path

prod = Path(__file__).resolve().parents[2] / "prod"
slots = runpy.run_path(str(prod / "slots.py"))
monitoring = slots["load"]("monitoring")["runtime"]
peers = set()
for path in (prod / "slots").glob("*.json"):
    slot = slots["load"](path.stem)
    if slot["kind"] in {"application", "config"}:
        peers.add(slot["container"]["advertise_address"] + "/32")
peers.add(monitoring["MONITORING_BIND_IP"] + "/32")
peers.update(filter(None, monitoring.get("MONITORING_ADDITIONAL_LOG_PEERS", "").split(",")))
for peer in peers:
    slots["private"](peer)
print(json.dumps({
    "bindings": [{"address": monitoring["MONITORING_BIND_IP"], "host_port": 3100, "container_port": 3100}],
    "peers": sorted(peers, key=lambda p: int(ipaddress.ip_network(p).network_address)),
    "bridge_peers": [
        {"source": "br-cowork-mon", "destination": "br-cowork-mon"},
        {"source": "br-cowork-log", "destination": "br-cowork-mon"},
    ],
}))
