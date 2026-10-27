#!/usr/bin/env python3
"""Publish explicitly bound GitHub secrets; output only immutable KV versions."""
import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

from vault_client import client, read, write


def publish(service):
    catalog = json.loads(Path(__file__).with_name("services.json").read_text())
    spec = catalog[service]
    mount = os.environ.get("VAULT_KV_MOUNT", "secret")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", mount):
        raise ValueError("Invalid Vault KV mount")
    vault = client("prod-settings-" + service)
    optional = set(spec["optional_secrets"])

    def values(bindings):
        result = {}
        for destination, source in bindings.items():
            value = os.environ.get("GH_SECRET_" + source, "")
            if not value and source not in optional:
                raise ValueError("Missing GitHub secret: " + source)
            if "\0" in value:
                raise ValueError("Secret contains a NUL byte: " + source)
            if source.startswith("DB_") and any(c in value for c in "\r\n"):
                raise ValueError("Database passwords must be single-line: " + source)
            if value:
                result[destination] = value
        return result

    app = values(spec["secret_bindings"])
    data = values(spec["data_secret_bindings"])
    if principal := spec.get("kafka_principal"):
        credentials = json.loads(os.environ["GH_SECRET_KAFKA_CREDENTIALS_JSON"])
        if not isinstance(credentials, dict) or not all(
            re.fullmatch(r"[a-z][a-z0-9-]+", k) and isinstance(v, str) and len(v) >= 32
            and not any(c in v for c in "\0\r\n") for k, v in credentials.items()
        ):
            raise ValueError("Invalid Kafka credentials map")
        if principal not in credentials:
            raise ValueError("Kafka principal is missing: " + principal)
        if service == "kafka":
            principals = {entry["kafka_principal"] for entry in catalog.values() if entry.get("kafka_principal")}
            if missing := principals - credentials.keys():
                raise ValueError("Kafka principals are missing: " + ", ".join(sorted(missing)))
            data["KAFKA_CREDENTIALS_JSON"] = json.dumps({name: credentials[name] for name in sorted(principals)})
        else:
            app.update(KAFKA_SASL_USERNAME=principal, KAFKA_SASL_PASSWORD=credentials[principal])
    if service == "chat":
        search = urlsplit(data["ELASTICSEARCH_URL"])
        if not search.username or not search.password:
            raise ValueError("ELASTICSEARCH_URL must contain the application credentials")
        if unquote(search.username) != "cowork_chat":
            raise ValueError("Elasticsearch application username must be cowork_chat")
        data.update(ELASTICSEARCH_USERNAME=unquote(search.username), ELASTICSEARCH_PASSWORD=unquote(search.password))
    for left, right in (("S3_ADMIN_ACCESS_KEY", "S3_ACCESS_KEY"),
                        ("S3_ADMIN_SECRET_KEY", "S3_SECRET_KEY"),
                        ("DB_ADMIN_PASSWORD", "DB_PASSWORD"),
                        ("DB_ADMIN_PASSWORD", "DB_MONITORING_PASSWORD"),
                        ("DB_PASSWORD", "DB_MONITORING_PASSWORD"),
                        ("ELASTICSEARCH_ADMIN_PASSWORD", "ELASTICSEARCH_PASSWORD")):
        if left in data and right in data and data[left] == data[right]:
            raise ValueError("Administrative and application credentials must differ: " + left + ", " + right)
    previous_app = read(vault, "nomad/apps/" + service, mount)
    previous_data = read(vault, "nomad/data/" + service, mount)
    for new, previous, bindings in [(app, previous_app[0], spec["secret_bindings"]), (data, previous_data[0], spec["data_secret_bindings"])]:
        for destination, source in bindings.items():
            if source in spec["immutable_secrets"] and destination in previous and previous[destination] != new.get(destination):
                raise ValueError("Immutable secret changed: " + source)
    if service == "monitoring":
        # This token is issued by platform bootstrap, not an application GitHub secret.
        app["CONSUL_HTTP_TOKEN"] = previous_app[0]["CONSUL_HTTP_TOKEN"]
    return {
        "secret_version": write(vault, "nomad/apps/" + service, app, mount, previous_app),
        "data_secret_version": write(vault, "nomad/data/" + service, data, mount, previous_data),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = publish(args.service)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    except Exception as error:
        raise SystemExit(f"Secret synchronization failed ({type(error).__name__}); response bodies withheld") from None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for key, value in result.items():
                output.write(f"{key}={value}\n")
    print(f"Published version references for {args.service}")
