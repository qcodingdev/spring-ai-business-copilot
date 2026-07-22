#!/usr/bin/env bash
set -Eeuo pipefail

# 固定评测集是发布门禁的一部分。这里先检查规模，具体每条样例的期望结果由 JUnit 执行。
datasets=(
  "platform/ai-guardrails/src/test/resources/evals/data-sql-safety.tsv"
  "modules/knowledge-copilot/src/test/resources/evals/citation-grounding.tsv"
  "modules/support-copilot/src/test/resources/evals/reply-guardrails.tsv"
  "modules/report-copilot/src/test/resources/evals/report-grounding.tsv"
  "modules/resume-copilot/src/test/resources/evals/job-compliance.tsv"
)
minimum_cases=(15 10 10 10 10)

total=0
for ((index = 0; index < ${#datasets[@]}; index++)); do
  dataset="${datasets[$index]}"
  if [[ ! -f "$dataset" ]]; then
    echo "评测门禁失败：缺少数据集 ${dataset}。" >&2
    exit 1
  fi
  count="$(awk 'NF && $1 !~ /^#/ { count++ } END { print count + 0 }' "$dataset")"
  minimum="${minimum_cases[$index]}"
  if (( count < minimum )); then
    echo "评测门禁失败：${dataset} 只有 ${count} 条，至少需要 ${minimum} 条。" >&2
    exit 1
  fi
  total=$((total + count))
  echo "评测数据集通过：${dataset}，共 ${count} 条。"
done

echo "五模块固定评测集规模门禁通过，共 ${total} 条。"
