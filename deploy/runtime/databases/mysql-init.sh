#!/usr/bin/env bash
set -euo pipefail

[[ "$DB_NAME" =~ ^cowork_[a-z][a-z0-9_]{0,55}$ && "$DB_USERNAME" =~ ^cowork_[a-z][a-z0-9_]{0,24}$ ]] || exit 1
export MYSQL_PWD="$DB_ADMIN_PASSWORD"
ready=false
for ((attempt = 0; attempt < 120; attempt++)); do
  if mysql --protocol=tcp -h 127.0.0.1 -u root --connect-timeout=3 -N -e 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo 'Database administrator connection did not become ready' >&2
  exit 1
fi

# NO_BACKSLASH_ESCAPES makes doubled quotes sufficient for SQL string literals.
app=${DB_PASSWORD//\'/\'\'}
monitor=${DB_MONITORING_PASSWORD//\'/\'\'}
if ! mysql --protocol=tcp -h 127.0.0.1 -u root --binary-mode >/dev/null 2>&1 <<SQL
SET SESSION sql_mode='NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USERNAME'@'%' IDENTIFIED BY '$app';
ALTER USER '$DB_USERNAME'@'%' IDENTIFIED BY '$app' ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$DB_USERNAME'@'%';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USERNAME'@'%';
CREATE USER IF NOT EXISTS 'cowork_monitoring'@'%' IDENTIFIED BY '$monitor';
ALTER USER 'cowork_monitoring'@'%' IDENTIFIED BY '$monitor' WITH MAX_USER_CONNECTIONS 3;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'cowork_monitoring'@'%';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'cowork_monitoring'@'%';
SQL
then
  echo 'Database account initialization failed' >&2
  exit 1
fi
echo 'Database accounts initialized'
