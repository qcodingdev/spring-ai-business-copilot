#!/usr/bin/env sh
# 双确认恢复公网虚构数据。不会清除额度、Admin 操作记录或系统审计。

set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$script_dir/public-demo-admin-lib.sh"

require_public_demo_tools
public_demo_login

reset_intent_response="$(public_demo_post "/api/admin/demo-data/reset-intents" "{}")"
printf '%s\n' "$reset_intent_response" | jq '.data | {
  willDelete,
  expiresAt,
  requiredConfirmationText
}'
reset_token="$(printf '%s' "$reset_intent_response" | jq -r '.data.resetToken // empty')"
required_text="$(printf '%s' "$reset_intent_response" | jq -r '.data.requiredConfirmationText // empty')"

if [ -z "$reset_token" ] || [ -z "$required_text" ]; then
  echo "无法创建一次性恢复凭证。" >&2
  exit 1
fi

if [ -t 0 ]; then
  printf '请输入固定确认文案“%s”：' "$required_text"
  IFS= read -r reset_confirmation
else
  : "${PUBLIC_DEMO_RESET_CONFIRM:?非交互运行请设置 PUBLIC_DEMO_RESET_CONFIRM}"
  reset_confirmation="$PUBLIC_DEMO_RESET_CONFIRM"
fi

if [ "$reset_confirmation" != "$required_text" ]; then
  echo "确认文案不匹配，已取消恢复。" >&2
  exit 1
fi

reset_body="$(jq -n \
  --arg resetToken "$reset_token" \
  --arg confirmationText "$reset_confirmation" \
  '{resetToken: $resetToken, confirmationText: $confirmationText}')"
reset_response="$(public_demo_post "/api/admin/demo-data/reset" "$reset_body")"
printf '%s\n' "$reset_response" | jq '.data | {
  id,
  status,
  summaryJson,
  errorCategory,
  finishedAt
}'
test "$(printf '%s' "$reset_response" | jq -r '.data.status')" = "COMPLETED"
