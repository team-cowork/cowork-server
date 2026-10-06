#!/usr/bin/env python3
"""Render VM-reachable discovery addresses without requiring PyYAML on the VM."""
import ipaddress
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit


def private_ipv4(host):
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.version == 4 and address.is_private and not address.is_loopback and not address.is_unspecified


def http_url(name):
    value = os.environ[name]
    url = urlsplit(value)
    if not url.hostname or url.username is not None or url.query or url.fragment:
        raise ValueError(f"{name} must be a URL without credentials")
    # Config Server는 TLS 없이 사설 IPv4 주소 리터럴로만 접근한다(deploy/prod/config-access.py와 같은 규칙).
    if url.scheme != "http" or not private_ipv4(url.hostname):
        raise ValueError(f"{name} must be an HTTP URL with a private IPv4 address")
    return value


eureka = http_url("EUREKA_SERVER_URL")
config_server = urlsplit(http_url("CONFIG_SERVER_URL"))
basic_auth = {"username": os.environ["CONFIG_CLIENT_USERNAME"], "password": os.environ["CONFIG_CLIENT_PASSWORD"]}
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
        "eureka_sd_configs": [{"server": eureka, "refresh_interval": "30s", "basic_auth": basic_auth, "follow_redirects": False}],
        "relabel_configs": [
            {"source_labels": ["__meta_eureka_app_instance_healthcheck_url"], "regex": "https?://.+", "action": "keep"},
            {"source_labels": ["__meta_eureka_app_instance_healthcheck_url"], "target_label": "__param_target"},
            {"source_labels": ["__param_target"], "target_label": "instance"},
            {"target_label": "__address__", "replacement": "blackbox_exporter:9115"},
            *service_labels,
        ],
    },
    {
        "job_name": "infrastructure",
        "static_configs": [
            {"targets": [target], "labels": {"service": service}}
            for service, target in [
                ("mysql", "mysqld_exporter:9104"), ("redis", "redis_exporter:9121"),
                ("kafka", "kafka_exporter:9308"), ("postgres", "postgres_exporter:9187"),
                ("mongodb", "mongodb_exporter:9216"),
            ]
        ],
    },
]
config = {
    "global": {"scrape_interval": "15s", "evaluation_interval": "15s", "external_labels": {"project": "cowork"}},
    "rule_files": ["/etc/prometheus/rules/*.yml"],
    "alerting": {"alertmanagers": [{"static_configs": [{"targets": ["alertmanager:9093"]}]}]},
    "scrape_configs": scrape_configs,
}
# JSON is a YAML subset accepted by Prometheus. Escape all operator-supplied addresses.
Path(sys.argv[1]).write_text(json.dumps(config, indent=2) + "\n")
