#!/usr/bin/env bash
set -Eeuo pipefail

# 生成权限为 600 的 PostgreSQL 自定义格式备份；不会修改数据库。
output_file="${1:-}"
if [[ -z "$output_file" ]]; then
  echo "用法：./scripts/backup-postgres.sh <备份文件.dump>" >&2
  exit 1
fi
if [[ -e "$output_file" || -L "$output_file" ]]; then
    echo "备份失败：目标文件已存在，不会覆盖：$output_file" >&2
    exit 1
fi

output_dir="$(dirname "$output_file")"
mkdir -p "$output_dir"
umask 077
temporary_file="${output_file}.partial"
if [[ -e "$temporary_file" || -L "$temporary_file" ]]; then
  echo "备份失败：临时文件已存在，不会覆盖：$temporary_file" >&2
  exit 1
fi
trap 'rm -f "$temporary_file"' EXIT

docker compose -f examples/docker-compose.yml exec -T postgres \
  pg_dump --format=custom --no-owner --no-privileges \
  --username "${POSTGRES_USER:-copilot}" --dbname "${POSTGRES_DB:-business_copilot}" \
  >"$temporary_file"
mv -n "$temporary_file" "$output_file"
if [[ -e "$temporary_file" ]]; then
  echo "备份失败：生成过程中目标文件被其他进程创建，不会覆盖：$output_file" >&2
  exit 1
fi
chmod 600 "$output_file"
echo "数据库备份完成：$output_file"
