#!/usr/bin/env sh
# 在首次部署前创建固定只读登录角色；V10 会将 6 张虚构业务表的 SELECT 权限授予该角色。

set -eu
command -v psql >/dev/null 2>&1 || {
  echo "缺少命令：psql" >&2
  exit 1
}
: "${PLATFORM_DATABASE_URL:?请设置 Railway PostgreSQL 的私有 DATABASE_URL}"
: "${BUSINESS_QUERY_DATASOURCE_PASSWORD:?请设置只读账号的随机强密码}"

psql "$PLATFORM_DATABASE_URL" \
  --set ON_ERROR_STOP=1 \
  --set reader_password="$BUSINESS_QUERY_DATASOURCE_PASSWORD" <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_copilot_reader') THEN
        CREATE ROLE business_copilot_reader
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'business_reader') THEN
        CREATE ROLE business_reader
            LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END
$$;
ALTER ROLE business_reader PASSWORD :'reader_password';
GRANT business_copilot_reader TO business_reader;
SQL

echo "只读账号 business_reader 已创建；应用迁移 V10 后仅能读取 6 张虚构业务表。"
