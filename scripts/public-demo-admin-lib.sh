#!/usr/bin/env sh
# 私有 Admin 脚本公共函数。凭据只从环境读取，不写入命令输出或仓库。

set -eu

require_public_demo_tools() {
  for tool_name in curl jq sed awk mktemp; do
    command -v "$tool_name" >/dev/null 2>&1 || {
      echo "缺少命令：$tool_name" >&2
      exit 1
    }
  done
  : "${ADMIN_BASE_URL:?请设置 ADMIN_BASE_URL，例如 https://example.up.railway.app}"
  : "${ADMIN_USERNAME:?请设置 ADMIN_USERNAME}"
  : "${ADMIN_PASSWORD:?请设置 ADMIN_PASSWORD}"
}

public_demo_login() {
  public_demo_cookie_jar="$(mktemp "${TMPDIR:-/tmp}/business-copilot-admin.XXXXXX")"
  trap 'rm -f "$public_demo_cookie_jar"' EXIT HUP INT TERM

  public_demo_login_page="$(curl --fail --silent --show-error \
    --cookie-jar "$public_demo_cookie_jar" \
    "${ADMIN_BASE_URL%/}/login")"
  public_demo_login_csrf="$(printf '%s' "$public_demo_login_page" \
    | sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' \
    | head -n 1)"
  if [ -z "$public_demo_login_csrf" ]; then
    echo "无法从登录页读取 CSRF 凭证。" >&2
    exit 1
  fi

  curl --fail --silent --show-error \
    --cookie "$public_demo_cookie_jar" \
    --cookie-jar "$public_demo_cookie_jar" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "_csrf=$public_demo_login_csrf" \
    "${ADMIN_BASE_URL%/}/login" >/dev/null

  curl --fail --silent --show-error \
    --cookie "$public_demo_cookie_jar" \
    --cookie-jar "$public_demo_cookie_jar" \
    "${ADMIN_BASE_URL%/}/admin" >/dev/null
  public_demo_csrf="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$public_demo_cookie_jar" | tail -n 1)"
  if [ -z "$public_demo_csrf" ]; then
    echo "Admin 登录失败或无法读取 CSRF Cookie。" >&2
    exit 1
  fi
}

public_demo_post() {
  public_demo_path="$1"
  if [ "$#" -ge 2 ]; then
    public_demo_body="$2"
  else
    public_demo_body='{}'
  fi
  curl --fail --silent --show-error \
    --cookie "$public_demo_cookie_jar" \
    -H "X-XSRF-TOKEN: $public_demo_csrf" \
    -H "Content-Type: application/json" \
    --data "$public_demo_body" \
    "${ADMIN_BASE_URL%/}${public_demo_path}"
}

public_demo_get() {
  curl --fail --silent --show-error \
    --cookie "$public_demo_cookie_jar" \
    "${ADMIN_BASE_URL%/}$1"
}
