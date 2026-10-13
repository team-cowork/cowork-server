#!/bin/sh
set -eu
: "${S3_BUCKET:?S3_BUCKET is required}"
: "${S3_INTERNAL_ENDPOINT:?S3_INTERNAL_ENDPOINT is required}"
case "$S3_BUCKET" in ''|*[!a-z0-9.-]*) echo 'Invalid S3_BUCKET' >&2; exit 1;; esac
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
sh "$script_dir/render-cors.sh" > "$work_dir/cors.json"
cat > "$work_dir/policy.json" <<POLICY
{"Version":"2012-10-17","Statement":[{"Sid":"PublicTeamIconRead","Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::$S3_BUCKET/team-icons/*"}]}
POLICY
cat > "$work_dir/lifecycle.json" <<'LIFECYCLE'
{"Rules":[
  {"ID":"ExpireTemporaryTeamIcons","Status":"Enabled","Filter":{"Prefix":"team-icon-uploads/"},"Expiration":{"Days":1}},
  {"ID":"ExpireTemporaryProfiles","Status":"Enabled","Filter":{"Prefix":"profile-uploads/"},"Expiration":{"Days":1}},
  {"ID":"ExpireTemporaryChatAttachments","Status":"Enabled","Filter":{"Prefix":"chat-uploads/"},"Expiration":{"Days":1}}
]}
LIFECYCLE
s3api() { aws --endpoint-url "$S3_INTERNAL_ENDPOINT" s3api "$@"; }
if ! s3api head-bucket --bucket "$S3_BUCKET" >/dev/null 2>&1; then
    s3api create-bucket --bucket "$S3_BUCKET"
fi
s3api put-bucket-acl --bucket "$S3_BUCKET" --acl private
s3api put-public-access-block --bucket "$S3_BUCKET" --public-access-block-configuration \
    'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=false,RestrictPublicBuckets=false'
s3api put-bucket-policy --bucket "$S3_BUCKET" --policy "file://$work_dir/policy.json"
s3api put-bucket-cors --bucket "$S3_BUCKET" --cors-configuration "file://$work_dir/cors.json"
s3api put-bucket-lifecycle-configuration --bucket "$S3_BUCKET" --lifecycle-configuration "file://$work_dir/lifecycle.json"
