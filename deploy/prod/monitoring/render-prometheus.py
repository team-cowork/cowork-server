#!/usr/bin/env python3
"""Render VM-reachable discovery addresses without requiring PyYAML on the VM."""
import ipaddress
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit


RFC1918 = [ipaddress.ip_network(cidr) for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]


def private_ipv4(host):
    # is_private는 링크 로컬과 문서용 대역도 포함하므로 RFC1918 사설 대역만 허용한다.
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.version == 4 and any(address in network for network in RFC1918)


def http_url(name):
    value = os.environ[name]
    if "," in value or any(char.isspace() for char in value):
        raise ValueError(f"{name} must contain exactly one URL")
    url = urlsplit(value)
    if not url.hostname or url.username is not None or url.query or url.fragment:
        raise ValueError(f"{name} must be a URL without credentials")
    # Config Server는 TLS 없이 사설 IPv4 주소 리터럴로만 접근한다(deploy/prod/config/config-access.py와 같은 규칙).
    if url.scheme != "http" or not private_ipv4(url.hostname):
        raise ValueError(f"{name} must be an HTTP URL with a private IPv4 address")
    return value


# Prometheus는 server 뒤에 "/apps"를 그대로 붙인다. 배포 문서의 끝 슬래시 형식("/eureka/")이 "//apps"가 되지 않도록 제거한다.
eureka = http_url("EUREKA_SERVER_URL").rstrip("/")
config_server = urlsplit(http_url("CONFIG_SERVER_URL"))
basic_auth = {"username": os.environ["CONFIG_CLIENT_USERNAME"], "password": os.environ["CONFIG_CLIENT_PASSWORD"]}
slots_directory = Path(__file__).resolve().parents[1] / "slots"
application_slots = [
    slot for path in sorted(slots_directory.glob("*.json"))
    if (slot := json.loads(path.read_text())).get("kind") == "application"
]
if not application_slots:
    raise ValueError("Monitoring requires declared application slots")
gateway_slots = [slot for slot in application_slots if slot["service"] == "gateway"]
if len(gateway_slots) != 1:
    raise ValueError("Public monitoring requires exactly one Gateway slot")
public_api_url = gateway_slots[0]["runtime"]["PUBLIC_API_BASE_URL"]
public_api = urlsplit(public_api_url)
if (public_api.scheme != "https" or not public_api.hostname or public_api.username is not None
        or public_api.password is not None or public_api.path not in {"", "/"}
        or public_api.query or public_api.fragment or "," in public_api_url
        or any(char.isspace() for char in public_api_url)):
    raise ValueError("Gateway PUBLIC_API_BASE_URL must be one HTTPS origin without credentials")
public_health_url = f"https://{public_api.netloc}/api/health"
probe_targets = []
for slot in application_slots:
    container = slot["container"]
    address = container["advertise_address"]
    if not private_ipv4(address):
        raise ValueError("Application monitoring targets must use private IPv4 addresses")
    probe_targets.append({
        "targets": [f'http://{address}:{container["host_port"]}{container["readiness"]["path"]}'],
        "labels": {"service": slot["service"]},
    })
database_catalog = json.loads(
    (Path(__file__).resolve().parents[2] / "iac/databases/catalog.json").read_text()
)
database_targets = []
for module, database in sorted(database_catalog.items()):
    slot = json.loads((slots_directory / f"{module}.json").read_text())
    address = slot["container"]["advertise_address"]
    if not private_ipv4(address):
        raise ValueError("Database exporter targets must use private IPv4 addresses")
    database_targets.append({
        "targets": [f'{address}:{database["exporter"]["port"]}'],
        "labels": {"service": database["engine"], "module": module},
    })
service_labels = [
    {"source_labels": ["__meta_eureka_app_name"], "target_label": "service", "regex": "COWORK-(.*)", "replacement": "${1}"},
    {"source_labels": ["service"], "target_label": "service", "action": "lowercase"},
]
scrape_configs = [
    {
        "job_name": "cowork-services",
        "eureka_sd_configs": [{"server": eureka, "refresh_interval": "30s", "basic_auth": basic_auth, "follow_redirects": False}],
        "relabel_configs": [
            {"source_labels": ["__meta_eureka_app_instance_metadata_prometheus_scrape"], "regex": "true", "action": "keep"},
            {"source_labels": ["__meta_eureka_app_instance_metadata_prometheus_path"], "target_label": "__metrics_path__", "regex": "(.+)"},
            *service_labels,
        ],
    },
    {
        "job_name": "cowork-external-services",
        "scheme": config_server.scheme,
        "basic_auth": basic_auth,
        "follow_redirects": False,
        "metrics_path": "/actuator/prometheus",
        "static_configs": [{"targets": [config_server.netloc], "labels": {"service": "config"}}],
    },
    {
        "job_name": "blackbox",
        "metrics_path": "/probe",
        "params": {"module": ["http_2xx"]},
        "static_configs": probe_targets,
        "relabel_configs": [
            {"source_labels": ["__address__"], "target_label": "__param_target"},
            {"source_labels": ["__param_target"], "target_label": "instance"},
            {"target_label": "__address__", "replacement": "blackbox_exporter:9115"},
        ],
    },
    {
        # This existing public JSON API checks DNS, TLS, the public proxy and
        # Gateway routing. Downstream readiness remains a separate signal.
        "job_name": "public-gateway",
        "metrics_path": "/probe",
        "params": {"module": ["http_public_gateway"]},
        "static_configs": [{"targets": [public_health_url], "labels": {"service": "gateway"}}],
        "relabel_configs": [
            {"source_labels": ["__address__"], "target_label": "__param_target"},
            {"source_labels": ["__param_target"], "target_label": "instance"},
            {"target_label": "__address__", "replacement": "blackbox_exporter:9115"},
        ],
    },
    {
        "job_name": "infrastructure",
        "static_configs": database_targets + [
            {"targets": [target], "labels": {"service": service}}
            for service, target in [
                ("redis", "redis_exporter:9121"), ("kafka", "kafka_exporter:9308"),
            ]
        ],
    },
    {
        "job_name": "monitoring",
        "static_configs": [
            {"targets": [target], "labels": {"service": service}}
            for service, target in [
                ("prometheus", "prometheus:9090"), ("alertmanager", "alertmanager:9093"),
                ("loki", "loki:3100"), ("blackbox", "blackbox_exporter:9115"),
            ]
        ],
    },
]
config = {
    "global": {"scrape_interval": "15s", "evaluation_interval": "15s", "external_labels": {"project": "cowork"}},
    "rule_files": ["/etc/prometheus/rules/*.yml", "/etc/prometheus/rules/*.json"],
    "alerting": {"alertmanagers": [{"static_configs": [{"targets": ["alertmanager:9093"]}]}]},
    "scrape_configs": scrape_configs,
}
# JSON is a YAML subset accepted by Prometheus. Escape all operator-supplied addresses.
Path(sys.argv[1]).write_text(json.dumps(config, indent=2) + "\n")
expected_rules = {
    "groups": [{"name": "cowork-expected-services", "rules": [
        {
            "alert": "coworkServiceMissing",
            "expr": f'absent(up{{job="cowork-services",service="{slot["service"]}"}})',
            "for": "3m",
            "labels": {"severity": "critical", "service": slot["service"]},
            "annotations": {"summary": "Declared service is missing from authenticated discovery"},
        }
        for slot in application_slots
    ]}]
}
health_metrics = {"mysql": "mysql_up", "postgres": "pg_up", "mongodb": "mongodb_up"}
database_rules = []
for module, database in sorted(database_catalog.items()):
    engine = database["engine"]
    selector = f'{{job="infrastructure",service="{engine}",module="{module}"}}'
    database_rules.append({
        "alert": "coworkDatabaseMetricsMissing",
        "expr": (
            f'absent(up{selector}) or '
            f'(up{selector} == 1 unless on (instance, job, service, module) '
            f'{health_metrics[engine]}{selector})'
        ),
        "for": "3m",
        "labels": {"severity": "critical", "service": engine, "module": module},
        "annotations": {
            "summary": "Declared module database metrics are missing",
            "description": f"Module {module} has no {engine} exporter target or database health metric.",
        },
    })
expected_rules["groups"].append({"name": "cowork-expected-databases", "rules": database_rules})
Path(sys.argv[1] + ".rules.json").write_text(json.dumps(expected_rules, indent=2) + "\n")
