#!/usr/bin/env bash
# EC2에서 cron으로 실행한다. RDS를 덤프해 S3 백업 버킷에 올린다.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
source .env
set +a

: "${DB_URL:?DB_URL is required}"
DB_HOST="$(echo "$DB_URL" | sed -E 's#jdbc:mysql://([^:/]+).*#\1#')"
: "${BACKUP_BUCKET:?BACKUP_BUCKET is required}"

timestamp="$(date +'%Y%m%d-%H%M%S')"
backup_dir="backups"
file="$backup_dir/cowork-$timestamp.sql.gz"
mkdir -p "$backup_dir"

docker run --rm -e MYSQL_PWD="$DB_PASSWORD" mysql:8.0 \
  mysqldump --host="$DB_HOST" --user="$DB_USERNAME" --single-transaction --routines --set-gtid-purged=OFF cowork \
  | gzip > "$file"

aws s3 cp "$file" "s3://$BACKUP_BUCKET/mysql/$(basename "$file")" --only-show-errors
echo "Backup uploaded: $file"
