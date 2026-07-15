#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${BUSINESS_COPILOT_BASE_URL:-http://localhost:8080}"
username="${BUSINESS_COPILOT_SMOKE_USERNAME:-operator}"
password="${BUSINESS_COPILOT_SMOKE_PASSWORD:-operator-change-me}"
health_attempts="${BUSINESS_COPILOT_SMOKE_HEALTH_ATTEMPTS:-30}"
health_interval_seconds="${BUSINESS_COPILOT_SMOKE_HEALTH_INTERVAL_SECONDS:-2}"
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
    echo "Release AI smoke test requires $command." >&2
    exit 1
  fi
done

assert_json() {
  local payload="$1"
  local filter="$2"
  local label="$3"
  if ! jq -e "$filter" >/dev/null 2>&1 <<<"$payload"; then
    echo "Release AI smoke test failed: $label" >&2
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
    echo "Release AI smoke test failed: $method $path returned HTTP $status." >&2
    jq '{success, errorCode, message}' "$response_file" 2>/dev/null >&2 || true
    exit 1
  fi

  local body
  body="$(<"$response_file")"
  assert_json "$body" '.success == true' "$method $path returned an unsuccessful API response"
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
  echo "Release AI smoke test failed: application health endpoint was not ready after $health_attempts attempts." >&2
  exit 1
fi

curl --fail --silent --show-error --cookie-jar "$cookie_file" "$base_url/login" >"$login_page"
login_csrf_token="$(sed -n 's/.*name="_csrf"[^>]*value="\([^"]*\)".*/\1/p' "$login_page" | head -n 1)"
if [[ -z "$login_csrf_token" ]]; then
  echo "Release AI smoke test failed: login CSRF token was not rendered." >&2
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
  echo "Release AI smoke test failed: expected login redirect, got HTTP $login_status." >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_file" --cookie-jar "$cookie_file" \
  "$base_url/" >/dev/null
csrf_token="$(awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$cookie_file")"
if [[ -z "$csrf_token" ]]; then
  echo "Release AI smoke test failed: API CSRF cookie was not issued." >&2
  exit 1
fi

echo "[1/5] Data Copilot: structured SQL generation and confirmed read-only execution"
data_candidate="$(api_json POST "/api/data-copilot/sql-candidates" \
  '{"question":"Return the three most expensive products with id, name, and price."}')"
assert_json "$data_candidate" \
  '.data.executable == true and (.data.candidateId | length > 0) and (.data.confirmationToken | length > 0)' \
  'Data Copilot did not create an executable candidate'
candidate_id="$(jq -r '.data.candidateId' <<<"$data_candidate")"
candidate_token="$(jq -r '.data.confirmationToken' <<<"$data_candidate")"
data_execution="$(api_json POST "/api/data-copilot/sql-candidates/$candidate_id/execute" \
  "$(jq -nc --arg token "$candidate_token" '{confirmationToken:$token}')")"
assert_json "$data_execution" '.data.table.rows != null' 'Data Copilot did not return query rows'

echo "[2/5] Knowledge Copilot: embedding, retrieval, citation, and grounded answer"
knowledge_content="Release validation $run_id. Acme report export procedure: open Reports, choose Export, select CSV, and download the generated file."
knowledge_upload="$(api_json POST "/api/knowledge-copilot/documents" \
  "$(jq -nc --arg file "release-smoke-$run_id.txt" --arg content "$knowledge_content" \
    '{fileName:$file,content:$content,category:"release-smoke"}')")"
assert_json "$knowledge_upload" '.data.indexed == true and .data.enabled == true' \
  'Knowledge Copilot did not index and enable the release document'
knowledge_answer="$(api_json POST "/api/knowledge-copilot/questions" \
  '{"question":"What is the documented Acme report export procedure?"}')"
assert_json "$knowledge_answer" \
  '.data.status == "ANSWERED" and (.data.citations | length > 0)' \
  'Knowledge Copilot did not return a cited answer'

echo "[3/5] Support Copilot: grounded draft and human confirmation"
support_analysis="$(api_json POST "/api/support-copilot/tickets/analyze" \
  '{"customerMessage":"How can I export an Acme report as CSV? Please provide the documented steps.","channel":"release-smoke"}')"
assert_json "$support_analysis" \
  '.data.draft != null and .data.draft.needsHuman == false and (.data.draft.citations | length > 0) and (.data.draft.confirmationToken | length > 0)' \
  'Support Copilot did not create a grounded confirmable draft'
support_draft_id="$(jq -r '.data.draft.draftId' <<<"$support_analysis")"
support_token="$(jq -r '.data.draft.confirmationToken' <<<"$support_analysis")"
support_confirmation="$(api_json POST "/api/support-copilot/reply-drafts/$support_draft_id/confirm" \
  "$(jq -nc --arg token "$support_token" '{confirmationToken:$token}')")"
assert_json "$support_confirmation" '.data.status == "CONFIRMED"' \
  'Support Copilot did not confirm the reply draft'

echo "[4/5] Report Copilot: evidence-grounded generation, confirmation, and Markdown export"
now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report_payload="$(jq -nc --arg now "$now" '{
  reportType:"TEAM_WEEKLY",
  period:{periodStart:"2026-07-06",periodEnd:"2026-07-10"},
  title:"Release validation weekly report",
  metrics:[{name:"Resolved tickets",value:42,unit:"tickets",periodStart:"2026-07-06",periodEnd:"2026-07-10",collectedAt:$now}],
  tasks:[{title:"Complete v1.1 security validation",status:"COMPLETED",assigneeAlias:"release-owner",sourceDescription:"Release checklist"}],
  meetingNotes:[{title:"Release review",content:"The team completed security validation and will not publish until final approval.",recordedAt:$now}]
}')"
report_draft="$(api_json POST "/api/report-copilot/reports/generate" "$report_payload")"
assert_json "$report_draft" \
  '.data.status == "DRAFTED" and (.data.confirmationToken | length > 0)' \
  'Report Copilot did not create a confirmable evidence-grounded draft'
report_draft_id="$(jq -r '.data.draftId' <<<"$report_draft")"
report_token="$(jq -r '.data.confirmationToken' <<<"$report_draft")"
report_confirmation="$(api_json POST "/api/report-copilot/reports/$report_draft_id/confirm" \
  "$(jq -nc --arg token "$report_token" '{confirmationToken:$token}')")"
assert_json "$report_confirmation" '.data.status == "CONFIRMED"' \
  'Report Copilot did not confirm the report draft'
curl --fail --silent --show-error --cookie "$cookie_file" \
  --output "$response_file" \
  "$base_url/api/report-copilot/reports/$report_draft_id/markdown"
if ! grep -q '[^[:space:]]' "$response_file"; then
  echo "Release AI smoke test failed: Report Copilot exported empty Markdown." >&2
  exit 1
fi

echo "[5/5] Resume Copilot: criteria confirmation, evidence assessment, and human review"
criteria="$(api_json POST "/api/resume-copilot/jobs/criteria" \
  '{"title":"Java Engineer","jobDescription":"Required: Java 21 and Spring Boot experience. Preferred: PostgreSQL experience."}')"
assert_json "$criteria" \
  '.data.status == "CRITERIA_DRAFTED" and (.data.criteria | length > 0) and (.data.confirmationToken | length > 0)' \
  'Resume Copilot did not create confirmable job criteria'
job_id="$(jq -r '.data.jobId' <<<"$criteria")"
criteria_token="$(jq -r '.data.confirmationToken' <<<"$criteria")"
criteria_confirmation="$(api_json POST "/api/resume-copilot/jobs/$job_id/criteria/confirm" \
  "$(jq -nc --arg token "$criteria_token" '{token:$token}')")"
assert_json "$criteria_confirmation" '.data.status == "CRITERIA_CONFIRMED"' \
  'Resume Copilot did not confirm job criteria'
assessment="$(api_json POST "/api/resume-copilot/assessments" \
  "$(jq -nc --argjson jobId "$job_id" --arg resume \
    'Candidate profile: five years building Java and Spring Boot services using Java 21, PostgreSQL, and automated integration tests.' \
    '{jobId:$jobId,resumeText:$resume}')")"
assert_json "$assessment" \
  '.data.status == "DRAFTED" and (.data.evidence | length > 0) and (.data.reviewToken | length > 0)' \
  'Resume Copilot did not create a reviewable evidence assessment'
assessment_id="$(jq -r '.data.assessmentId' <<<"$assessment")"
review_token="$(jq -r '.data.reviewToken' <<<"$assessment")"
review="$(api_json POST "/api/resume-copilot/assessments/$assessment_id/review" \
  "$(jq -nc --arg token "$review_token" '{token:$token}')")"
assert_json "$review" '.data.status == "REVIEWED"' \
  'Resume Copilot did not record the human review'

echo "Release AI smoke test passed for all five Copilot workflows."
