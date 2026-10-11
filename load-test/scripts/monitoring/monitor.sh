#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# Keep caller environment overrides. Credentials stay in shell memory, not a temp file.
if [[ "${MONITORING_SKIP_DOTENV:-false}" != true && -f .env ]]; then
  CALLER_EXPORTS="$(export -p)"
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  eval "$CALLER_EXPORTS"
  unset CALLER_EXPORTS
fi
exec node "$ROOT_DIR/scripts/monitoring/monitor.mjs" "$@"
