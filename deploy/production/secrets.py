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
            if value:
                result[destination] = value
        return result

    app = values(spec["secret_bindings"])
    data = values(spec["data_secret_bindings"])
    if principal := spec.get("kafka_principal"):
        credentials = json.loads(os.environ["GH_SECRET_KAFKA_CREDENTIALS_JSON"])
        principals = ({entry["kafka_principal"] for entry in catalog.values() if entry.get("kafka_principal")}
                      if service == "kafka" else {principal})
        selected = {name: credentials[name] for name in sorted(principals)}
        if any(not isinstance(value, str) or not value or "\0" in value for value in selected.values()):
            raise ValueError("Kafka passwords must be non-empty environment strings")
        if service == "kafka":
            data["KAFKA_CREDENTIALS_JSON"] = json.dumps(selected)
        else:
            app.update(KAFKA_SASL_USERNAME=principal, KAFKA_SASL_PASSWORD=selected[principal])
    if service == "chat":
        search = urlsplit(app["ELASTICSEARCH_URL"])
        if not search.username or not search.password:
            raise ValueError("ELASTICSEARCH_URL must contain the application credentials")
        if unquote(search.username) != "cowork_chat":
            raise ValueError("Elasticsearch application username must be cowork_chat")
        data["ELASTICSEARCH_PASSWORD"] = unquote(search.password)
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
    groups = {name: {key: data.pop(key) for key in keys}
              for name, keys in spec.get("data_secret_groups", {}).items()}
    previous_groups = {name: read(vault, f"nomad/data/{service}/{name}", mount) for name in groups}
    payloads = [(app, previous_app[0], spec["secret_bindings"]),
                (data, previous_data[0], spec["data_secret_bindings"])]
    payloads += [(values, {**previous_data[0], **previous_groups[name][0]}, spec["data_secret_bindings"])
                 for name, values in groups.items()]
    for new, previous, bindings in payloads:
        for destination, source in bindings.items():
            if (source in spec["immutable_secrets"] and destination in new and destination in previous
                    and previous[destination] != new[destination]):
                raise ValueError("Immutable secret changed: " + source)
    if service == "monitoring":
        # This token is issued by platform bootstrap, not an application GitHub secret.
        app["CONSUL_HTTP_TOKEN"] = previous_app[0]["CONSUL_HTTP_TOKEN"]
    # Preserve the old immutable values until every split path is safely written.
    group_versions = {
        f"{service}/{name}": write(vault, f"nomad/data/{service}/{name}", values, mount, previous_groups[name])
        for name, values in groups.items()
    }
    return {
        "secret_version": write(vault, "nomad/apps/" + service, app, mount, previous_app),
        "data_secret_version": write(vault, "nomad/data/" + service, data, mount, previous_data),
        "data_secret_versions": group_versions,
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
                output.write(f"{key}={json.dumps(value)}\n")
    print(f"Published version references for {args.service}")
