#!/usr/bin/env bash
set -euo pipefail

[[ "$DB_NAME" =~ ^cowork_[a-z][a-z0-9_]{0,55}$ && "$DB_USERNAME" =~ ^cowork_[a-z][a-z0-9_]{0,24}$ ]] || exit 1
export PGPASSWORD="$DB_ADMIN_PASSWORD"
ready=false
for ((attempt = 0; attempt < 120; attempt++)); do
  if psql -h 127.0.0.1 -U postgres -d postgres --no-password --no-psqlrc -c 'SELECT 1' >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo 'Database administrator connection did not become ready' >&2
  exit 1
fi

app=${DB_PASSWORD//\'/\'\'}
monitor=${DB_MONITORING_PASSWORD//\'/\'\'}
if ! psql -h 127.0.0.1 -U postgres -d postgres --no-password --no-psqlrc -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<SQL
SELECT 'CREATE DATABASE "$DB_NAME"' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='$DB_NAME')\gexec
SQL
then
  echo 'Database creation failed' >&2
  exit 1
fi
if ! psql -h 127.0.0.1 -U postgres -d "$DB_NAME" --no-password --no-psqlrc -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<SQL
BEGIN;
SET standard_conforming_strings=on;
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$DB_USERNAME') THEN CREATE ROLE "$DB_USERNAME" LOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='cowork_monitoring') THEN CREATE ROLE cowork_monitoring LOGIN; END IF;
END \$\$;
ALTER ROLE "$DB_USERNAME" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$app';
ALTER DATABASE "$DB_NAME" OWNER TO "$DB_USERNAME";
REVOKE ALL ON DATABASE "$DB_NAME" FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO "$DB_USERNAME";
CREATE SCHEMA IF NOT EXISTS preference AUTHORIZATION "$DB_USERNAME";
ALTER DATABASE "$DB_NAME" SET search_path TO preference, public;
ALTER ROLE cowork_monitoring WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$monitor' CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE "$DB_NAME" TO cowork_monitoring;
GRANT pg_monitor TO cowork_monitoring;
COMMIT;
SQL
then
  echo 'Database account initialization failed' >&2
  exit 1
fi
echo 'Database accounts initialized'
