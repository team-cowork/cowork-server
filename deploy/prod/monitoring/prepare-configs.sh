#!/bin/sh
set -eu
umask 077

# Clear generated configuration only. Each destination is a dedicated config volume.
set -- /generated/loki /generated/blackbox /generated/prometheus /generated/alertmanager
for destination do
  find "$destination" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
done

cp /source/loki/loki-config.yml /generated/loki/local-config.yaml
cp /source/blackbox/blackbox.yml /generated/blackbox/config.yml
cp -R /source/prometheus/rules /generated/prometheus/rules
cp /source-expected-rules.json /generated/prometheus/rules/expected-services.json
cp /source/alertmanager/alertmanager.yml /generated/alertmanager/alertmanager.yml

# The archive can arrive with directories 0700 and files 0600. Only these copies
# of public Git configuration become readable by the service image users.
find "$@" -type d -exec chmod 0755 {} +
find "$@" -type f -exec chmod 0444 {} +

# Keep credentials out of other services' mounts, including those with the same UID.
cp /source-prometheus.json /generated/prometheus/prometheus.yml
printf '%s' "${DISCORD_WEBHOOK_URL:?DISCORD_WEBHOOK_URL is required}" > /generated/alertmanager/discord-webhook-url
chown 65534:65534 /generated/prometheus/prometheus.yml /generated/alertmanager/discord-webhook-url
chmod 0400 /generated/prometheus/prometheus.yml /generated/alertmanager/discord-webhook-url
