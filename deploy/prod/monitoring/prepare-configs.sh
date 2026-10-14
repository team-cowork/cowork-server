#!/bin/sh
set -eu
umask 077

# Clear generated configuration only. Each destination is a dedicated config volume.
set -- /generated/loki /generated/blackbox /generated/prometheus /generated/alertmanager /generated/grafana-proxy
for destination do
  find "$destination" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
done

cp /source/loki/loki-config.yml /generated/loki/local-config.yaml
cp /source/blackbox/blackbox.yml /generated/blackbox/config.yml
cp -R /source/prometheus/rules /generated/prometheus/rules
cp /source-expected-rules.json /generated/prometheus/rules/expected-services.json
cp /source/alertmanager/alertmanager.yml /generated/alertmanager/alertmanager.yml
cp /source/grafana/proxy.conf /generated/grafana-proxy/default.conf

# Grafana polls these files while the previous container is still running.
# Replace complete files before pruning retired files, without emptying the directory.
sync_grafana_config() {
  source_directory=$1
  target_directory=$2
  mkdir -p "$target_directory"
  find "$source_directory" -type f | while IFS= read -r source_file; do
    relative_path=${source_file#"$source_directory"/}
    target_file="$target_directory/$relative_path"
    mkdir -p "$(dirname "$target_file")"
    cp "$source_file" "$target_file.tmp"
    chmod 0444 "$target_file.tmp"
    mv -f "$target_file.tmp" "$target_file"
  done
  find "$target_directory" -type f | while IFS= read -r target_file; do
    relative_path=${target_file#"$target_directory"/}
    [ -f "$source_directory/$relative_path" ] || rm -f "$target_file"
  done
}
sync_grafana_config /source/grafana/provisioning /generated/grafana/provisioning
sync_grafana_config /source/grafana/dashboards /generated/grafana/dashboards

# The archive can arrive with directories 0700 and files 0600. Only these copies
# of public Git configuration become readable by the service image users.
find "$@" /generated/grafana -type d -exec chmod 0755 {} +
find "$@" /generated/grafana -type f -exec chmod 0444 {} +

# Keep credentials out of other services' mounts, including those with the same UID.
cp /source-prometheus.json /generated/prometheus/prometheus.yml
printf '%s' "${DISCORD_MONITORING_ALERT_WEBHOOK:?DISCORD_MONITORING_ALERT_WEBHOOK is required}" > /generated/alertmanager/discord-webhook-url
chown 65534:65534 /generated/prometheus/prometheus.yml /generated/alertmanager/discord-webhook-url
chmod 0400 /generated/prometheus/prometheus.yml /generated/alertmanager/discord-webhook-url
