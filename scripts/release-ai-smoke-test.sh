#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${BUSINESS_COPILOT_BASE_URL:-http://localhost:8080}"
username="${BUSINESS_COPILOT_SMOKE_USERNAME:-operator}"
password="${BUSINESS_COPILOT_SMOKE_PASSWORD:-operator-change-me}"
reviewer_username="${BUSINESS_COPILOT_SMOKE_REVIEWER_USERNAME:-reviewer}"
reviewer_password="${BUSINESS_COPILOT_SMOKE_REVIEWER_PASSWORD:-reviewer-change-me}"
health_attempts="${BUSINESS_COPILOT_SMOKE_HEALTH_ATTEMPTS:-30}"
health_interval_seconds="${BUSINESS_COPILOT_SMOKE_HEALTH_INTERVAL_SECONDS:-2}"
index_attempts="${BUSINESS_COPILOT_SMOKE_INDEX_ATTEMPTS:-45}"
index_interval_seconds="${BUSINESS_COPILOT_SMOKE_INDEX_INTERVAL_SECONDS:-2}"
cookie_file="$(mktemp)"
reviewer_cookie_file="$(mktemp)"
login_page="$(mktemp)"
response_file="$(mktemp)"
run_id="$(date +%s)-$$"

cleanup() {
  rm -f "$cookie_file" "$reviewer_cookie_file" "$login_page" "$response_file"
}
trap cleanup EXIT

for command in curl jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "发布 AI 冒烟测试依赖命令：${command}。" >&2
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

api_json_with_session() {
  local session_cookie="$1"
  local session_csrf="$2"
  local method="$3"
  local path="$4"
  local payload="${5:-}"
  local status

  if [[ "$method" == "GET" ]]; then
    status="$(curl --silent --show-error \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --cookie "$session_cookie" \
      "$base_url$path")"
  else
    status="$(curl --silent --show-error \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --cookie "$session_cookie" \
      --header "Content-Type: application/json" \
      --header "X-XSRF-TOKEN: $session_csrf" \
      --request "$method" \
      --data "$payload" \
      "$base_url$path")"
  fi

  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "发布 AI 冒烟测试失败：$method $path 返回 HTTP ${status}。" >&2
    jq '{success, errorCode, message}' "$response_file" 2>/dev/null >&2 || true
    exit 1
  fi

  local body
  body="$(<"$response_file")"
  assert_json "$body" '.success == true' "$method $path 返回了失败的 API 响应"
  printf '%s' "$body"
}

api_json() {
  api_json_with_session "$cookie_file" "$csrf_token" "$@"
}

reviewer_api_json() {
  api_json_with_session "$reviewer_cookie_file" "$reviewer_csrf_token" "$@"
}

login_session() {
  local session_username="$1"
  local session_password="$2"
  local session_cookie="$3"
  local login_csrf_token
  local login_status
  local session_csrf_token

  curl --fail --silent --show-error --cookie-jar "$session_cookie" "$base_url/login" >"$login_page"
  curl --fail --silent --show-error --cookie "$session_cookie" --cookie-jar "$session_cookie" \
    "$base_url/api/session" >/dev/null
  login_csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$session_cookie")"
  if [[ -z "$login_csrf_token" ]]; then
    echo "发布 AI 冒烟测试失败：匿名会话未签发 CSRF Cookie。" >&2
    return 1
  fi

  login_status="$(curl --silent --show-error \
    --output /dev/null \
    --write-out '%{http_code}' \
    --cookie "$session_cookie" \
    --cookie-jar "$session_cookie" \
    --data-urlencode "username=$session_username" \
    --data-urlencode "password=$session_password" \
    --data-urlencode "_csrf=$login_csrf_token" \
    "$base_url/login")"
  if [[ "$login_status" != "302" ]]; then
    echo "发布 AI 冒烟测试失败：登录应返回重定向，实际为 HTTP ${login_status}。" >&2
    return 1
  fi

  curl --fail --silent --show-error --cookie "$session_cookie" --cookie-jar "$session_cookie" \
    "$base_url/api/session" >/dev/null
  session_csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$session_cookie")"
  if [[ -z "$session_csrf_token" ]]; then
    echo "发布 AI 冒烟测试失败：应用未签发 API CSRF Cookie。" >&2
    return 1
  fi
  printf '%s' "$session_csrf_token"
}

approve_subject() {
  local subject_type="$1"
  local subject_id="$2"
  local review_queue
  local review_task_id
  local review_decision

  review_queue="$(reviewer_api_json GET "/api/reviews/queue?subjectType=$subject_type")"
  review_task_id="$(jq -r --arg subject "$subject_id" \
    '[.data[] | select(.subjectId == $subject) | .id][0] // empty' <<<"$review_queue")"
  if [[ -z "$review_task_id" ]]; then
    echo "发布 AI 冒烟测试失败：独立复核队列中未找到 $subject_type/${subject_id}。" >&2
    exit 1
  fi
  review_decision="$(reviewer_api_json POST "/api/reviews/$review_task_id/decision" \
    '{"decision":"APPROVE","note":"发布冒烟测试：独立复核通过。"}')"
  assert_json "$review_decision" \
    '.data.status == "APPROVED" and (.data.reviewerActorId | length > 0)' \
    "$subject_type 未完成独立复核"
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

csrf_token="$(login_session "$username" "$password" "$cookie_file")"
reviewer_csrf_token="$(login_session "$reviewer_username" "$reviewer_password" "$reviewer_cookie_file")"

echo "[1/5] Data Copilot：结构化 SQL 生成、确认和只读执行"
data_candidate="$(api_json POST "/api/data-copilot/sql-candidates" \
  '{"question":"查询价格最高的三个商品，返回编号、名称和价格。"}')"
assert_json "$data_candidate" \
  '.data.executable == true and (.data.candidateId | length > 0) and (.data.confirmationToken | length > 0)' \
  'Data Copilot 未生成可执行的 SQL 候选'
candidate_id="$(jq -r '.data.candidateId' <<<"$data_candidate")"
candidate_token="$(jq -r '.data.confirmationToken' <<<"$data_candidate")"
approve_subject "DATA_SQL_CANDIDATE" "$candidate_id"
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
knowledge_answer_id="$(jq -r '.data.answerId' <<<"$knowledge_answer")"
knowledge_feedback="$(api_json POST "/api/knowledge-copilot/answers/$knowledge_answer_id/feedback" \
  '{"rating":"NOT_HELPFUL","reason":"UNCLEAR","comment":"发布验证构造的质量复核样本。"}')"
assert_json "$knowledge_feedback" '.data.rating == "NOT_HELPFUL"' \
  'Knowledge Copilot 未记录负反馈'
knowledge_quality_queue="$(reviewer_api_json GET "/api/knowledge-copilot/quality-queue?page=0&size=100")"
knowledge_quality_item="$(jq -c --argjson answerId "$knowledge_answer_id" \
  '[.data.content[] | select(.answerId == $answerId)][0] // empty' <<<"$knowledge_quality_queue")"
if [[ -z "$knowledge_quality_item" ]]; then
  echo "发布 AI 冒烟测试失败：复核员质量队列中未找到知识负反馈。" >&2
  exit 1
fi
knowledge_issue_version="$(jq -r '.issueVersion' <<<"$knowledge_quality_item")"
knowledge_issue_updated_at="$(jq -r '.issueUpdatedAt' <<<"$knowledge_quality_item")"
knowledge_quality_review="$(reviewer_api_json POST "/api/knowledge-copilot/quality-queue/$knowledge_answer_id/review" \
  "$(jq -nc --argjson version "$knowledge_issue_version" --arg updatedAt "$knowledge_issue_updated_at" \
    '{decision:"DISMISSED",evidenceAssessment:"SUFFICIENT",answerAssessment:"ACCURATE",remediationAction:"NONE",reviewNote:"发布验证：引用和回答均可验证。",expectedIssueVersion:$version,expectedIssueUpdatedAt:$updatedAt}')")"
assert_json "$knowledge_quality_review" \
  '.data.decision == "DISMISSED" and (.data.reviewerActorId | length > 0)' \
  'Knowledge Copilot 未由独立复核员完成质量处置'
knowledge_quality_metrics="$(reviewer_api_json GET "/api/knowledge-copilot/quality-metrics")"
assert_json "$knowledge_quality_metrics" \
  '.data.feedbackCount >= 1 and .data.notHelpfulCount >= 1 and .data.dismissedCount >= 1' \
  'Knowledge Copilot 未更新反馈列表统计'

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
  tasks:[{title:"完成 v2.0 安全验证",status:"COMPLETED",assigneeAlias:"发布负责人",sourceDescription:"发布检查清单记录：已完成 v2.0 安全验证。"}],
  meetingNotes:[{title:"发布评审",content:"发布评审记录：团队已完成 v2.0 安全验证，最终确认前不会发布。",recordedAt:$now}]
}')"
report_draft="$(api_json POST "/api/report-copilot/reports/generate" "$report_payload")"
assert_json "$report_draft" \
  '.data.status == "DRAFTED" and (.data.confirmationToken | length > 0)' \
  'Report Copilot 未生成可确认的证据化草稿'
report_draft_id="$(jq -r '.data.draftId' <<<"$report_draft")"
report_token="$(jq -r '.data.confirmationToken' <<<"$report_draft")"
approve_subject "REPORT_DRAFT" "$report_draft_id"
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
candidate_reference="release-smoke-candidate-$(date +%s)"
consent_reference="release-smoke-consent-$(date +%s)"
granted_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
expires_at="$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)"
consent="$(api_json POST "/api/resume-copilot/enterprise/consents" \
  "$(jq -nc --arg reference "$consent_reference" --arg candidate "$candidate_reference" \
    --arg granted "$granted_at" --arg expires "$expires_at" \
    '{consentReference:$reference,candidateReference:$candidate,purpose:"ASSESSMENT",grantedAt:$granted,expiresAt:$expires}')")"
assert_json "$consent" '.data.purpose == "ASSESSMENT"' \
  'Resume Copilot 未记录评估用途的候选人授权'
assessment="$(api_json POST "/api/resume-copilot/assessments" \
  "$(jq -nc --argjson jobId "$job_id" --arg candidate "$candidate_reference" \
    --arg consent "$consent_reference" --arg resume \
    '候选人简介：五年 Java 和 Spring Boot 服务开发经验，使用 Java 21、PostgreSQL 和自动化集成测试。' \
    '{jobId:$jobId,candidateReference:$candidate,consentReference:$consent,resumeText:$resume}')")"
assert_json "$assessment" \
  '.data.status == "DRAFTED" and (.data.evidence | length > 0) and (.data.reviewToken | length > 0)' \
  'Resume Copilot 未生成可复核的证据化评估'
assessment_id="$(jq -r '.data.assessmentId' <<<"$assessment")"
review_queue="$(reviewer_api_json GET "/api/resume-copilot/assessments/review-queue?limit=100")"
assert_json "$review_queue" \
  ".data | any(.assessmentId == $assessment_id and .ownerActorId == \"$username\")" \
  'Resume Copilot 未把评估交给独立复核员'
review_session="$(reviewer_api_json POST "/api/resume-copilot/assessments/$assessment_id/review-session" '{}')"
review_token="$(jq -r '.data.reviewToken' <<<"$review_session")"
review="$(reviewer_api_json POST "/api/resume-copilot/assessments/$assessment_id/review" \
  "$(jq -nc --arg token "$review_token" '{token:$token}')")"
assert_json "$review" '.data.status == "REVIEWED"' \
  'Resume Copilot 未记录人工复核结果'

echo "五个 Copilot 业务闭环的发布 AI 冒烟测试全部通过。"
