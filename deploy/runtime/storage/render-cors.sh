#!/bin/sh
set -eu
: "${PUBLIC_WEB_ORIGINS:?PUBLIC_WEB_ORIGINS is required}"
printf '{"CORSRules":[{"AllowedOrigins":['
comma=''
old_ifs=$IFS
IFS=,
for origin in $PUBLIC_WEB_ORIGINS; do
    printf '%s' "$origin" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$' || { echo 'Invalid CORS origin' >&2; exit 1; }
    printf '%s"%s"' "$comma" "$origin"
    comma=,
done
IFS=$old_ifs
[ -n "$comma" ] || exit 1
printf '],"AllowedMethods":["GET","PUT","HEAD"],"AllowedHeaders":["Content-Type","Range","x-amz-*"],"ExposeHeaders":["ETag","Content-Length","Content-Range","Accept-Ranges"],"MaxAgeSeconds":600}]}'
