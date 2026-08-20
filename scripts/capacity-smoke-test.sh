#!/usr/bin/env bash
set -Eeuo pipefail

# 对已启动应用执行不触发模型费用的认证读请求基线，适合发布前快速发现线程池或连接池退化。
base_url="${BUSINESS_COPILOT_BASE_URL:-http://localhost:8080}"
username="${BUSINESS_COPILOT_SMOKE_USERNAME:-operator}"
password="${BUSINESS_COPILOT_SMOKE_PASSWORD:-operator-change-me}"
request_count="${BUSINESS_COPILOT_CAPACITY_REQUESTS:-50}"
concurrency="${BUSINESS_COPILOT_CAPACITY_CONCURRENCY:-5}"
p95_limit_seconds="${BUSINESS_COPILOT_CAPACITY_P95_SECONDS:-1.5}"
cookie_file="$(mktemp)"
results_file="$(mktemp)"

cleanup() {
  rm -f "$cookie_file" "$results_file"
}
trap cleanup EXIT

for command in curl awk sed sort xargs; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "容量冒烟缺少命令：${command}。" >&2
    exit 1
  fi
done

# 防止变量填写错误后意外打出过大流量；更高规模应使用正式压测工具和独立环境。
if [[ ! "$request_count" =~ ^[1-9][0-9]*$ ]] || (( request_count > 10000 )); then
  echo "容量冒烟参数错误：请求数必须是 1～10000 的整数。" >&2
  exit 1
fi
if [[ ! "$concurrency" =~ ^[1-9][0-9]*$ ]] || (( concurrency > 100 )); then
  echo "容量冒烟参数错误：并发数必须是 1～100 的整数。" >&2
  exit 1
fi
if ! awk -v limit="$p95_limit_seconds" 'BEGIN { exit !(limit ~ /^[0-9]+([.][0-9]+)?$/ && limit > 0) }'; then
  echo "容量冒烟参数错误：P95 阈值必须是大于 0 的秒数。" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie-jar "$cookie_file" "$base_url/login" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  "$base_url/api/session" >/dev/null
csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$cookie_file")"
if [[ -z "$csrf_token" ]]; then
  echo "容量冒烟失败：匿名会话未生成 CSRF cookie。" >&2
  exit 1
fi

login_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  --data-urlencode "username=$username" --data-urlencode "password=$password" \
  --data-urlencode "_csrf=$csrf_token" "$base_url/login")"
if [[ "$login_status" != "302" ]]; then
  echo "容量冒烟失败：登录返回 HTTP ${login_status}。" >&2
  exit 1
fi

export base_url cookie_file
awk -v count="$request_count" 'BEGIN { for (i = 1; i <= count; i++) print i }' |
  xargs -P "$concurrency" -I '{}' sh -c \
    'curl --silent --output /dev/null --write-out "%{http_code} %{time_total}\n" --cookie "$cookie_file" "$base_url/api/data-copilot/schema"' \
    >>"$results_file"

failed="$(awk '$1 != 200 { count++ } END { print count + 0 }' "$results_file")"
if (( failed > 0 )); then
  echo "容量冒烟失败：${failed} 个请求未返回 HTTP 200。" >&2
  exit 1
fi

p95_index=$(((request_count * 95 + 99) / 100))
p95="$(awk '{ print $2 }' "$results_file" | sort -n | sed -n "${p95_index}p")"
if ! awk -v actual="$p95" -v limit="$p95_limit_seconds" 'BEGIN { exit !(actual <= limit) }'; then
  echo "容量冒烟失败：P95=${p95}s，超过阈值 ${p95_limit_seconds}s。" >&2
  exit 1
fi

echo "容量冒烟通过：请求数=${request_count}，并发=${concurrency}，失败=0，P95=${p95}s。"
