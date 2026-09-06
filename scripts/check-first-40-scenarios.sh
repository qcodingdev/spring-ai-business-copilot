#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
catalog="$project_root/platform/evaluation-harness/src/test/resources/first-40-scenarios.tsv"

cd "$project_root"
./mvnw -B -ntp test

tail -n +2 "$catalog" | while IFS=$'\t' read -r scenario_id module layer test_class test_method; do
  report="$(find "$project_root" -path "*/target/surefire-reports/TEST-${test_class}.xml" -print -quit)"
  if [[ -z "$report" ]]; then
    echo "[$scenario_id] missing Surefire report for $test_class" >&2
    exit 1
  fi
  if ! rg -F "<testcase name=\"${test_method}\"" "$report" >/dev/null; then
    echo "[$scenario_id] mapped test did not execute: ${test_class}#${test_method}" >&2
    exit 1
  fi
done

echo "First 40 scenario gate passed: D/K/S/R/H (30) + X (10)."
