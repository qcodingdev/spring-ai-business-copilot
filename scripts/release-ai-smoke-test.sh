#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${BUSINESS_COPILOT_BASE_URL:-http://localhost:8080}"
username="${BUSINESS_COPILOT_SMOKE_USERNAME:-operator}"
password="${BUSINESS_COPILOT_SMOKE_PASSWORD:-operator-change-me}"
health_attempts="${BUSINESS_COPILOT_SMOKE_HEALTH_ATTEMPTS:-30}"
health_interval_seconds="${BUSINESS_COPILOT_SMOKE_HEALTH_INTERVAL_SECONDS:-2}"
index_attempts="${BUSINESS_COPILOT_SMOKE_INDEX_ATTEMPTS:-45}"
index_interval_seconds="${BUSINESS_COPILOT_SMOKE_INDEX_INTERVAL_SECONDS:-2}"
cookie_file="$(mktemp)"
login_page="$(mktemp)"
response_file="$(mktemp)"
run_id="$(date +%s)-$$"

cleanup() {
  rm -f "$cookie_file" "$login_page" "$response_file"
}
trap cleanup EXIT

for command in curl jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "发布 AI 冒烟测试依赖命令：$command。" >&2
    exit 1
  fi
done

assert_json() {
  local payload="$1"
  local filter="$2"
  local label="$3"
  if ! jq -e "$filter" >/dev/null 2>&1 <<<"$payload"; then
    echo "发布 AI 冒烟测试失败：$label" >&2
    jq '{success, errorCode, message, data}' <<<"$payload" >&2
    exit 1
  fi
}

api_json() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  local status

  if [[ "$method" == "GET" ]]; then
    status="$(curl --silent --show-error \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --cookie "$cookie_file" \
      "$base_url$path")"
  else
    status="$(curl --silent --show-error \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --cookie "$cookie_file" \
      --header "Content-Type: application/json" \
      --header "X-XSRF-TOKEN: $csrf_token" \
      --request "$method" \
      --data "$payload" \
      "$base_url$path")"
  fi

  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "发布 AI 冒烟测试失败：$method $path 返回 HTTP $status。" >&2
    jq '{success, errorCode, message}' "$response_file" 2>/dev/null >&2 || true
    exit 1
  fi

  local body
  body="$(<"$response_file")"
  assert_json "$body" '.success == true' "$method $path 返回了失败的 API 响应"
  printf '%s' "$body"
}

healthy=false
for ((attempt = 1; attempt <= health_attempts; attempt++)); do
  if curl --fail --silent "$base_url/actuator/health" >/dev/null 2>&1; then
    healthy=true
    break
  fi
  sleep "$health_interval_seconds"
done
if [[ "$healthy" != "true" ]]; then
  echo "发布 AI 冒烟测试失败：健康检查在 $health_attempts 次尝试后仍未就绪。" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie-jar "$cookie_file" "$base_url/login" >"$login_page"
curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  "$base_url/api/session" >/dev/null
login_csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$cookie_file")"
if [[ -z "$login_csrf_token" ]]; then
  echo "发布 AI 冒烟测试失败：匿名会话未签发 CSRF Cookie。" >&2
  exit 1
fi

login_status="$(curl --silent --show-error \
  --output /dev/null \
  --write-out '%{http_code}' \
  --cookie "$cookie_file" \
  --cookie-jar "$cookie_file" \
  --data-urlencode "username=$username" \
  --data-urlencode "password=$password" \
  --data-urlencode "_csrf=$login_csrf_token" \
  "$base_url/login")"
if [[ "$login_status" != "302" ]]; then
  echo "发布 AI 冒烟测试失败：登录应返回重定向，实际为 HTTP $login_status。" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  "$base_url/api/session" >/dev/null
csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$cookie_file")"
if [[ -z "$csrf_token" ]]; then
  echo "发布 AI 冒烟测试失败：应用未签发 API CSRF Cookie。" >&2
  exit 1
fi

echo "[1/5] Data Copilot：结构化 SQL 生成、确认和只读执行"
data_candidate="$(api_json POST "/api/data-copilot/sql-candidates" \
  '{"question":"查询价格最高的三个商品，返回编号、名称和价格。"}')"
assert_json "$data_candidate" \
  '.data.executable == true and (.data.candidateId | length > 0) and (.data.confirmationToken | length > 0)' \
  'Data Copilot 未生成可执行的 SQL 候选'
candidate_id="$(jq -r '.data.candidateId' <<<"$data_candidate")"
candidate_token="$(jq -r '.data.confirmationToken' <<<"$data_candidate")"
data_execution="$(api_json POST "/api/data-copilot/sql-candidates/$candidate_id/execute" \
  "$(jq -nc --arg token "$candidate_token" '{confirmationToken:$token}')")"
assert_json "$data_execution" '.data.table.rows != null' 'Data Copilot 未返回查询结果行'

echo "[2/5] Knowledge Copilot：向量化、检索、引用和有依据回答"
knowledge_content="发布验证 ${run_id}。虚构 Acme 报告导出流程：打开“报告”，选择“导出”，选择 CSV，然后下载生成的文件。"
knowledge_upload="$(api_json POST "/api/knowledge-copilot/documents" \
  "$(jq -nc --arg file "release-smoke-$run_id.txt" --arg content "$knowledge_content" \
    '{fileName:$file,content:$content,category:"release-smoke"}')")"
assert_json "$knowledge_upload" \
  '.data.documentId != null and .data.indexJobId != null and .data.indexStatus == "PENDING"' \
  'Knowledge Copilot 未创建异步索引任务'
knowledge_document_id="$(jq -r '.data.documentId' <<<"$knowledge_upload")"
knowledge_job_id="$(jq -r '.data.indexJobId' <<<"$knowledge_upload")"
knowledge_indexed=false
for ((attempt = 1; attempt <= index_attempts; attempt++)); do
  knowledge_job="$(api_json GET "/api/knowledge-copilot/index-jobs/$knowledge_job_id")"
  knowledge_job_status="$(jq -r '.data.status' <<<"$knowledge_job")"
  if [[ "$knowledge_job_status" == "COMPLETED" ]]; then
    assert_json "$knowledge_job" '.data.chunkCount > 0' \
      'Knowledge Copilot 索引完成但没有生成分块'
    knowledge_indexed=true
    break
  fi
  if [[ "$knowledge_job_status" == "FAILED" || "$knowledge_job_status" == "CANCELED" ]]; then
    echo "发布 AI 冒烟测试失败：知识库索引任务以 $knowledge_job_status 结束。" >&2
    jq '{success, errorCode, message, data}' <<<"$knowledge_job" >&2
    exit 1
  fi
  sleep "$index_interval_seconds"
done
if [[ "$knowledge_indexed" != "true" ]]; then
  echo "发布 AI 冒烟测试失败：知识库索引任务在 $index_attempts 次轮询后仍未完成。" >&2
  exit 1
fi
knowledge_documents="$(api_json GET "/api/knowledge-copilot/documents")"
assert_json "$knowledge_documents" \
  ".data | any(.id == $knowledge_document_id and .enabled == true and .indexStatus == \"INDEXED\")" \
  'Knowledge Copilot 未启用已索引的当前文档版本'
knowledge_answer="$(api_json POST "/api/knowledge-copilot/questions" \
  '{"question":"文档中记录的 Acme 报告导出流程是什么？"}')"
assert_json "$knowledge_answer" \
  '.data.status == "ANSWERED" and (.data.citations | length > 0)' \
  'Knowledge Copilot 未返回带引用的回答'

echo "[3/5] Support Copilot：有依据回复草稿和人工确认"
support_analysis="$(api_json POST "/api/support-copilot/tickets/analyze" \
  '{"customerMessage":"如何将 Acme 报告导出为 CSV？请提供文档中记录的步骤。","channel":"release-smoke"}')"
assert_json "$support_analysis" \
  '.data.draft != null and .data.draft.needsHuman == false and (.data.draft.citations | length > 0) and (.data.draft.confirmationToken | length > 0)' \
  'Support Copilot 未生成有依据且可确认的回复草稿'
support_draft_id="$(jq -r '.data.draft.draftId' <<<"$support_analysis")"
support_token="$(jq -r '.data.draft.confirmationToken' <<<"$support_analysis")"
support_confirmation="$(api_json POST "/api/support-copilot/reply-drafts/$support_draft_id/confirm" \
  "$(jq -nc --arg token "$support_token" '{confirmationToken:$token}')")"
assert_json "$support_confirmation" '.data.status == "CONFIRMED"' \
  'Support Copilot 未完成回复草稿确认'

echo "[4/5] Report Copilot：证据化生成、确认和 Markdown 导出"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report_payload="$(jq -nc --arg now "$now" '{
  reportType:"TEAM_WEEKLY",
  period:{periodStart:"2026-07-06",periodEnd:"2026-07-10"},
  title:"发布验证周报",
  metrics:[{name:"已解决工单",value:42,unit:"个",periodStart:"2026-07-06",periodEnd:"2026-07-10",collectedAt:$now}],
  tasks:[{title:"完成 v2.0 安全验证",status:"COMPLETED",assigneeAlias:"发布负责人",sourceDescription:"发布检查清单"}],
  meetingNotes:[{title:"发布评审",content:"团队已完成安全验证，最终确认前不会发布。",recordedAt:$now}]
}')"
report_draft="$(api_json POST "/api/report-copilot/reports/generate" "$report_payload")"
assert_json "$report_draft" \
  '.data.status == "DRAFTED" and (.data.confirmationToken | length > 0)' \
  'Report Copilot 未生成可确认的证据化草稿'
report_draft_id="$(jq -r '.data.draftId' <<<"$report_draft")"
report_token="$(jq -r '.data.confirmationToken' <<<"$report_draft")"
report_confirmation="$(api_json POST "/api/report-copilot/reports/$report_draft_id/confirm" \
  "$(jq -nc --arg token "$report_token" '{confirmationToken:$token}')")"
assert_json "$report_confirmation" '.data.status == "CONFIRMED"' \
  'Report Copilot 未完成报告草稿确认'
curl --fail --silent --show-error --cookie "$cookie_file" \
  --output "$response_file" \
  "$base_url/api/report-copilot/reports/$report_draft_id/markdown"
if ! grep -q '[^[:space:]]' "$response_file"; then
  echo "发布 AI 冒烟测试失败：Report Copilot 导出的 Markdown 为空。" >&2
  exit 1
fi

echo "[5/5] Resume Copilot：标准确认、证据化评估和人工复核"
criteria="$(api_json POST "/api/resume-copilot/jobs/criteria" \
  '{"title":"Java 工程师","jobDescription":"必选：具备 Java 21 和 Spring Boot 实践经验。加分：具备 PostgreSQL 实践经验。"}')"
assert_json "$criteria" \
  '.data.status == "CRITERIA_DRAFTED" and (.data.criteria | length > 0) and (.data.confirmationToken | length > 0)' \
  'Resume Copilot 未生成可确认的职位标准'
job_id="$(jq -r '.data.jobId' <<<"$criteria")"
criteria_token="$(jq -r '.data.confirmationToken' <<<"$criteria")"
criteria_confirmation="$(api_json POST "/api/resume-copilot/jobs/$job_id/criteria/confirm" \
  "$(jq -nc --arg token "$criteria_token" '{token:$token}')")"
assert_json "$criteria_confirmation" '.data.status == "CRITERIA_CONFIRMED"' \
  'Resume Copilot 未完成职位标准确认'
assessment="$(api_json POST "/api/resume-copilot/assessments" \
  "$(jq -nc --argjson jobId "$job_id" --arg resume \
    '候选人简介：五年 Java 和 Spring Boot 服务开发经验，使用 Java 21、PostgreSQL 和自动化集成测试。' \
    '{jobId:$jobId,resumeText:$resume}')")"
assert_json "$assessment" \
  '.data.status == "DRAFTED" and (.data.evidence | length > 0) and (.data.reviewToken | length > 0)' \
  'Resume Copilot 未生成可复核的证据化评估'
assessment_id="$(jq -r '.data.assessmentId' <<<"$assessment")"
review_token="$(jq -r '.data.reviewToken' <<<"$assessment")"
review="$(api_json POST "/api/resume-copilot/assessments/$assessment_id/review" \
  "$(jq -nc --arg token "$review_token" '{token:$token}')")"
assert_json "$review" '.data.status == "REVIEWED"' \
  'Resume Copilot 未记录人工复核结果'

echo "五个 Copilot 业务闭环的发布 AI 冒烟测试全部通过。"
