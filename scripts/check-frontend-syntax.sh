#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend_dir="$repo_root/frontend"

if [[ ! -f "$frontend_dir/package-lock.json" ]]; then
  echo "Frontend check failed: package-lock.json is missing." >&2
  exit 1
fi

npm --prefix "$frontend_dir" ci --no-audit --no-fund
npm --prefix "$frontend_dir" run check

echo "Vue frontend type, lint, unit, i18n and production build checks passed."
