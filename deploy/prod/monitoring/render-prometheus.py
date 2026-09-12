#!/usr/bin/env python3
"""Render VM-reachable discovery addresses without requiring PyYAML on the VM."""
import json
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit


def http_url(name):
    value = os.environ[name]
    url = urlsplit(value)
    if url.scheme not in {"http", "https"} or not url.hostname:
        raise ValueError(f"{name} must be an absolute HTTP(S) URL")
    return value


eureka = http_url("EUREKA_SERVER_URL")
config_server = urlsplit(http_url("CONFIG_SERVER_URL"))
service_labels = [
    {"source_labels": ["__meta_eureka_app_name"], "target_label": "service", "regex": "COWORK-(.*)", "replacement": "${1}"},
    {"source_labels": ["service"], "target_label": "service", "action": "lowercase"},
]
scrape_configs = [
    {
        "job_name": "cowork-services",
        "eureka_sd_configs": [{"server": eureka, "refresh_interval": "30s"}],
        "relabel_configs": [
            {"source_labels": ["__meta_eureka_app_instance_metadata_prometheus_scrape"], "regex": "true", "action": "keep"},
            {"source_labels": ["__meta_eureka_app_instance_metadata_prometheus_path"], "target_label": "__metrics_path__", "regex": "(.+)"},
            *service_labels,
        ],
    },
    {
        "job_name": "cowork-external-services",
        "scheme": config_server.scheme,
        "metrics_path": "/actuator/prometheus",
        "static_configs": [{"targets": [config_server.netloc], "labels": {"service": "config"}}],
    },
    {
        "job_name": "blackbox",
        "metrics_path": "/probe",
        "params": {"module": ["http_2xx"]},
        "eureka_sd_configs": [{"server": eureka, "refresh_interval": "30s"}],
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
