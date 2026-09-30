#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT="${1:?Usage: ./scripts/run-k6.sh scenarios/<scenario>.js [k6 options]}"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f "$SCRIPT" ]]; then
  echo "Scenario file not found: $SCRIPT" >&2
  exit 1
fi

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 CLI is required. Install it from https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 1
fi

if [[ -f .env ]]; then
  set -a
  # .env is a local operator-controlled file and is gitignored.
  # shellcheck disable=SC1091
  source .env
  set +a
fi

export TEST_RUN_ID="${TEST_RUN_ID:-loadtest-$(date -u +%Y%m%dT%H%M%SZ)}"
SCENARIO_NAME="$(basename "$SCRIPT" .js)"
RESULT_DIR="results/$TEST_RUN_ID"
SUMMARY_FILE="$RESULT_DIR/${SCENARIO_NAME}-summary.json"
mkdir -p "$RESULT_DIR"

echo "run_id=$TEST_RUN_ID"
echo "scenario=$SCRIPT"
echo "summary=$SUMMARY_FILE"

# k6 validates BASE_URL, CONFIRM_STAGING, credentials, and write/external guards
# while loading the scenario before the first network request.
k6 run "$@" --summary-export "$SUMMARY_FILE" "$SCRIPT"
