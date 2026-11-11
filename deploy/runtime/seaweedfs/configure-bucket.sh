#!/bin/sh
set -eu
umask 077
: "${S3_BUCKET:?S3_BUCKET is required}"
: "${S3_INTERNAL_ENDPOINT:?S3_INTERNAL_ENDPOINT is required}"
case "$S3_BUCKET" in ''|*[!a-z0-9.-]*) echo 'Invalid S3_BUCKET' >&2; exit 1;; esac
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
sh "$script_dir/render-cors.sh" > "$work_dir/cors.json"
if [ -n "${S3_BUCKET_POLICY_FILE:-}" ]; then
    cp "$S3_BUCKET_POLICY_FILE" "$work_dir/policy.json"
    cp "${S3_BUCKET_LIFECYCLE_FILE:?S3_BUCKET_LIFECYCLE_FILE is required}" "$work_dir/lifecycle.json"
else
cat > "$work_dir/policy.json" <<POLICY
{"Version":"2012-10-17","Statement":[
  {"Sid":"PublicTeamIconRead","Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::$S3_BUCKET/team-icons/*"},
  {"Sid":"DenyServiceAclChanges","Effect":"Deny","Principal":{"AWS":["arn:aws:iam::000000000000:user/cowork-team","arn:aws:iam::000000000000:user/cowork-user","arn:aws:iam::000000000000:user/cowork-chat"]},"Action":["s3:PutObjectAcl","s3:PutBucketAcl"],"Resource":["arn:aws:s3:::$S3_BUCKET","arn:aws:s3:::$S3_BUCKET/*"]}
]}
POLICY
cat > "$work_dir/lifecycle.json" <<'LIFECYCLE'
{"Rules":[
  {"ID":"ExpireTemporaryTeamIcons","Status":"Enabled","Filter":{"Prefix":"team-icon-uploads/"},"Expiration":{"Days":1}},
  {"ID":"ExpireTemporaryProfiles","Status":"Enabled","Filter":{"Prefix":"profile-uploads/"},"Expiration":{"Days":1}},
  {"ID":"ExpireTemporaryChatAttachments","Status":"Enabled","Filter":{"Prefix":"chat-uploads/"},"Expiration":{"Days":1}}
]}
LIFECYCLE
fi
s3api() { aws --endpoint-url "$S3_INTERNAL_ENDPOINT" s3api "$@"; }
if ! s3api head-bucket --bucket "$S3_BUCKET" >/dev/null 2>&1; then
    s3api create-bucket --bucket "$S3_BUCKET"
fi
s3api put-bucket-acl --bucket "$S3_BUCKET" --acl private
if ! s3api put-public-access-block --bucket "$S3_BUCKET" --public-access-block-configuration \
    'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=false,RestrictPublicBuckets=false' \
    > "$work_dir/public-access-block.out" 2> "$work_dir/public-access-block.err"; then
    # SeaweedFS 4.47 implements this endpoint as an explicit NotImplemented.
    # Do not turn authentication, transport, or other server failures into success.
    if ! grep -Fq '(NotImplemented) when calling the PutPublicAccessBlock operation' "$work_dir/public-access-block.err"; then
        cat "$work_dir/public-access-block.err" >&2
        exit 1
    fi
    echo 'PublicAccessBlock is unsupported; enforce the private bucket ACL, prefix identities, and bucket policy.' >&2
fi
s3api put-bucket-policy --bucket "$S3_BUCKET" --policy "file://$work_dir/policy.json"
s3api put-bucket-cors --bucket "$S3_BUCKET" --cors-configuration "file://$work_dir/cors.json"
s3api put-bucket-lifecycle-configuration --bucket "$S3_BUCKET" --lifecycle-configuration "file://$work_dir/lifecycle.json"
