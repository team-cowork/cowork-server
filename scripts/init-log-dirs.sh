#!/bin/bash
set -e

SERVICES=(gateway config user team notification channel project authorization voice chat preference)

for svc in "${SERVICES[@]}"; do
    sudo mkdir -p /var/log/cowork/$svc
    sudo chmod 755 /var/log/cowork/$svc
done

echo "Log directories initialized at /var/log/cowork/"
