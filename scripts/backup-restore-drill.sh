#!/usr/bin/env bash
set -Eeuo pipefail

# 只在一次性容器中恢复备份并核对核心表，绝不连接或覆盖现有业务数据库。
backup_file="${1:-}"
if [[ -z "$backup_file" || ! -f "$backup_file" ]]; then
  echo "用法：./scripts/backup-restore-drill.sh <备份文件.dump>" >&2
  exit 1
fi

drill_container="business-copilot-restore-drill-$$"
cleanup() {
  docker rm -f "$drill_container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach --name "$drill_container" \
  --env POSTGRES_DB=restore_drill --env POSTGRES_USER=drill --env POSTGRES_PASSWORD=drill-only \
  pgvector/pgvector:pg16 >/dev/null

ready=false
for _attempt in $(seq 1 30); do
  if docker exec "$drill_container" pg_isready -U drill -d restore_drill >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "恢复演练失败：一次性 PostgreSQL 未在 30 秒内就绪。" >&2
  exit 1
fi

docker exec -i "$drill_container" pg_restore --exit-on-error --no-owner --no-privileges \
  --username drill --dbname restore_drill <"$backup_file"

verification="$(docker exec "$drill_container" psql --set ON_ERROR_STOP=1 -U drill -d restore_drill -Atc \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true; SELECT COUNT(*) FROM customers;")"
if [[ "$(sed -n '1p' <<<"$verification")" -lt 1 ]]; then
  echo "恢复演练失败：未找到成功的 Flyway 迁移记录。" >&2
  exit 1
fi
if [[ ! "$(sed -n '2p' <<<"$verification")" =~ ^[0-9]+$ ]]; then
  echo "恢复演练失败：核心业务表无法读取。" >&2
  exit 1
fi
echo "数据库恢复演练通过：Flyway 与核心业务表可读取；一次性容器将在退出时删除。"
