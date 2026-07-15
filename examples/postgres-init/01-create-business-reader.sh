#!/bin/bash
set -Eeuo pipefail

# This script is sourced by the official PostgreSQL image only when a new data
# volume is initialized. psql's identifier/literal quoting keeps env input safe.
reader_user="${BUSINESS_READER_USERNAME:-business_reader}"
reader_password="${BUSINESS_READER_PASSWORD:-business-reader-change-me}"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=reader_user="$reader_user" \
  --set=reader_password="$reader_password" \
  --set=owner_user="$POSTGRES_USER" <<'EOSQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT',
  :'reader_user', :'reader_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'reader_user') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'reader_user') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', :'reader_user') \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON TABLES TO %I',
  :'owner_user', :'reader_user'
) \gexec
EOSQL
