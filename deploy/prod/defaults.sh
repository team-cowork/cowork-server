#!/bin/bash
# Protocol defaults only. Addresses, profiles, credentials and VM identity come from Vault.
MYSQL_PORT=${MYSQL_PORT:-3306}
POSTGRES_PORT=${POSTGRES_PORT:-5432}
MONGO_PORT=${MONGO_PORT:-27017}
REDIS_PORT=${REDIS_PORT:-6379}
S3_BUCKET=${S3_BUCKET:-cowork-bucket}
