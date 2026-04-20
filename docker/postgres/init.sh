#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
    SELECT 'CREATE DATABASE cowork_preference OWNER "${POSTGRES_USER}"'
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'cowork_preference')\gexec
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "cowork_preference" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS preference AUTHORIZATION ${POSTGRES_USER};
    ALTER DATABASE cowork_preference SET search_path TO preference, public;

    GRANT ALL ON SCHEMA preference TO ${POSTGRES_USER};
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA preference TO ${POSTGRES_USER};
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA preference TO ${POSTGRES_USER};
    ALTER DEFAULT PRIVILEGES IN SCHEMA preference
    GRANT ALL ON TABLES TO ${POSTGRES_USER};
    ALTER DEFAULT PRIVILEGES IN SCHEMA preference
    GRANT ALL ON SEQUENCES TO ${POSTGRES_USER};
EOSQL
