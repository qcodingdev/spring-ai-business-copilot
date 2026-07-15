#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${BUSINESS_COPILOT_BASE_URL:-http://localhost:8080}"
username="${BUSINESS_COPILOT_SMOKE_USERNAME:-operator}"
password="${BUSINESS_COPILOT_SMOKE_PASSWORD:-operator-change-me}"
cookie_file="$(mktemp)"
login_page="$(mktemp)"

cleanup() {
  rm -f "$cookie_file" "$login_page"
}
trap cleanup EXIT

curl --fail --silent --show-error "$base_url/actuator/health" >/dev/null
curl --fail --silent --show-error --cookie-jar "$cookie_file" "$base_url/login" >"$login_page"

csrf_token="$(sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' "$login_page" | head -n 1)"
if [[ -z "$csrf_token" ]]; then
  echo "Smoke test failed: login CSRF token was not rendered." >&2
  exit 1
fi

login_status="$(curl --silent --show-error \
  --output /dev/null \
  --write-out '%{http_code}' \
  --cookie "$cookie_file" \
  --cookie-jar "$cookie_file" \
  --data-urlencode "username=$username" \
  --data-urlencode "password=$password" \
  --data-urlencode "_csrf=$csrf_token" \
  "$base_url/login")"

if [[ "$login_status" != "302" ]]; then
  echo "Smoke test failed: expected login redirect, got HTTP $login_status." >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_file" "$base_url/" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_file" \
  "$base_url/api/data-copilot/schema" >/dev/null
echo "Smoke test passed: health, CSRF login, authenticated workbench, and Data schema are reachable."
