#!/bin/sh
set -eu
umask 077
: "${S3_BUCKET:?S3_BUCKET is required}"
case "$S3_BUCKET" in ''|*[!a-z0-9.-]*) echo 'Invalid S3_BUCKET' >&2; exit 1;; esac
source_template=${1:?template path is required}
output_file=${2:?output path is required}
work_file=$(mktemp)
trap 'rm -f "$work_file" "$work_file.next"' EXIT
cp "$source_template" "$work_file"
seen_keys='|'
for name in S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY S3_TEAM_ACCESS_KEY S3_TEAM_SECRET_KEY S3_USER_ACCESS_KEY S3_USER_SECRET_KEY S3_CHAT_ACCESS_KEY S3_CHAT_SECRET_KEY; do
    value=$(printenv "$name")
    case "$value" in ''|*[!A-Za-z0-9_+=/.-]*) echo "Invalid or missing $name (use base64url credentials)" >&2; exit 1;; esac
    if [ "${APP_PROFILE:-local}" = prod ]; then
        case "$value" in seaweedfs*|local-*) echo "Development credential is not allowed for $name" >&2; exit 1;; esac
    fi
    case "$name" in *_ACCESS_KEY)
        case "$seen_keys" in *"|$value|"*) echo 'Storage identities must use distinct access keys' >&2; exit 1;; esac
        seen_keys="$seen_keys$value|";;
    esac
    sed "s|\${$name}|$value|g" "$work_file" > "$work_file.next"
    mv "$work_file.next" "$work_file"
done
install -m 600 "$work_file" "$output_file"
