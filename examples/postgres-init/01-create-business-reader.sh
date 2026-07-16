#!/bin/bash
set -Eeuo pipefail

# This script is sourced by the official PostgreSQL image only when a new data
# volume is initialized. psql's identifier/literal quoting keeps env input safe.
reader_user="${BUSINESS_READER_USERNAME:-business_reader}"
reader_password="${BUSINESS_READER_PASSWORD:-business-reader-change-me}"
reader_group="business_copilot_reader"

if [[ "$reader_user" == "$POSTGRES_USER" || "$reader_user" == "$reader_group" ]]; then
  echo "BUSINESS_READER_USERNAME must differ from the database owner and reader group" >&2
  exit 1
fi

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=reader_user="$reader_user" \
  --set=reader_password="$reader_password" \
  --set=reader_group="$reader_group" \
  --set=owner_user="$POSTGRES_USER" <<'EOSQL'
SELECT format(
  'CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
  :'reader_group'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'reader_group') \gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS',
  :'reader_user', :'reader_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'reader_user') \gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS',
  :'reader_user', :'reader_password'
) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader_user') \gexec
SELECT format('REVOKE CREATE ON SCHEMA public FROM %I', :'reader_user') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM %I', :'reader_user') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM %I', :'reader_user') \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL PRIVILEGES ON TABLES FROM %I',
  :'owner_user', :'reader_user'
) \gexec
SELECT format('GRANT %I TO %I', :'reader_group', :'reader_user') \gexec
EOSQL
