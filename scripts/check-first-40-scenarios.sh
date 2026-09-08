#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
catalog="$project_root/platform/evaluation-harness/src/test/resources/first-40-scenarios.tsv"
report_marker="$(mktemp)"
trap 'rm -f "$report_marker"' EXIT

command -v python3 >/dev/null || {
  echo "python3 is required to validate Surefire reports" >&2
  exit 1
}

cd "$project_root"
./mvnw -B -ntp test

tail -n +2 "$catalog" | while IFS=$'\t' read -r scenario_id module layer test_class test_method; do
  report="$(find "$project_root" -path "*/target/surefire-reports/TEST-${test_class}.xml" -newer "$report_marker" -print -quit)"
  if [[ -z "$report" ]]; then
    echo "[$scenario_id] missing fresh Surefire report for $test_class" >&2
    exit 1
  fi
  if ! python3 - "$report" "$test_method" <<'PY'
import sys
import xml.etree.ElementTree as ET

report, method = sys.argv[1:]
root = ET.parse(report).getroot()
executed = any(
    case.get("name") == method
    and case.find("skipped") is None
    and case.find("failure") is None
    and case.find("error") is None
    for case in root.iter("testcase")
)
raise SystemExit(0 if executed else 1)
PY
  then
    echo "[$scenario_id] mapped test did not pass: ${test_class}#${test_method}" >&2
    exit 1
  fi
done

echo "First 40 scenario gate passed: D/K/S/R/H (30) + X (10)."
