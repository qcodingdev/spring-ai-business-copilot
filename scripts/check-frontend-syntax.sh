#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
files=("$repo_root"/app/business-copilot-app/src/main/resources/static/js/*.js)

if [[ ${#files[@]} -eq 0 ]]; then
  echo "Frontend syntax check failed: no JavaScript files were found." >&2
  exit 1
fi

for file in "${files[@]}"; do
  node --check "$file"
done

echo "Frontend syntax check passed for ${#files[@]} JavaScript files."
