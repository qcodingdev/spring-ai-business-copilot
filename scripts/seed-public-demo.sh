#!/usr/bin/env sh
# 幂等初始化公网虚构数据，并等待索引任务创建完成。

set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_dir/public-demo-admin-lib.sh"

require_public_demo_tools
public_demo_login

seed_response="$(public_demo_post "/api/admin/demo-data/initialize" "{}")"
seed_job_id="$(printf '%s' "$seed_response" | jq -r '.data.id // empty')"
if [ -z "$seed_job_id" ]; then
  printf '%s\n' "$seed_response" | jq .
  echo "初始化任务创建失败。" >&2
  exit 1
fi

echo "初始化任务：$seed_job_id"
seed_attempt=0
while [ "$seed_attempt" -lt 60 ]; do
  seed_job="$(public_demo_get "/api/admin/demo-data/jobs/$seed_job_id")"
  seed_status="$(printf '%s' "$seed_job" | jq -r '.data.status // empty')"
  case "$seed_status" in
    COMPLETED)
      printf '%s\n' "$seed_job" | jq '.data | {id, status, summaryJson, finishedAt}'
      exit 0
      ;;
    FAILED)
      printf '%s\n' "$seed_job" | jq '.data | {id, status, errorCategory, finishedAt}'
      exit 1
      ;;
  esac
  seed_attempt=$((seed_attempt + 1))
  sleep 2
done

echo "等待初始化超时，可稍后通过任务接口继续检查：$seed_job_id" >&2
exit 1
