#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT="${1:?Usage: ./scripts/test/run-completion-with-reset.sh scenarios/completion/<scenario>.js [k6 options]}"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

case "$SCRIPT" in
  scenarios/completion/constant-arrival.js|scenarios/completion/ramping-arrival.js|scenarios/completion/toggle.js|scenarios/p02/constant-mix.js|scenarios/p02/ramping-spike.js) ;;
  *)
    echo "Automatic completion reset only supports completion or P-02 scenarios: $SCRIPT" >&2
    exit 1
    ;;
esac

CALLER_TEST_RUN_ID="${TEST_RUN_ID:-}"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
if [[ -n "$CALLER_TEST_RUN_ID" ]]; then export TEST_RUN_ID="$CALLER_TEST_RUN_ID"; fi
: "${BASE_URL:?BASE_URL is required}"
: "${CONFIRM_STAGING:?CONFIRM_STAGING=true is required}"
: "${ALLOW_WRITE_TESTS:?ALLOW_WRITE_TESTS=true is required}"
: "${CONFIRM_COMPLETION_RESET:?CONFIRM_COMPLETION_RESET=true is required}"
if [[ "$CONFIRM_STAGING" != true || "$ALLOW_WRITE_TESTS" != true || "$CONFIRM_COMPLETION_RESET" != true ]]; then
  echo 'CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true are required' >&2
  exit 1
fi

# 독립 run ID를 만들어 성능 결과와 복구 로그를 같은 디렉터리에 보관한다.
export TEST_RUN_ID="${TEST_RUN_ID:-$(TZ=Asia/Seoul date +%Y-%m-%d-%H-%M-%S)-completion-reset-$$}"
RESULT_DIR="results/$TEST_RUN_ID"
SCENARIO_NAME="${SCRIPT#scenarios/}"
SCENARIO_NAME="${SCENARIO_NAME%.js}"
SCENARIO_NAME="${SCENARIO_NAME//\//-}"
SUMMARY_FILE="$RESULT_DIR/${SCENARIO_NAME}-summary.json"
mkdir -p "$RESULT_DIR"
if [[ -e "$SUMMARY_FILE" ]]; then
  echo "Result already exists and will not be overwritten: $SUMMARY_FILE" >&2
  exit 1
fi

set +e
./scripts/test/run-k6.sh "$SCRIPT" "$@" 2>&1 | tee "$RESULT_DIR/performance.log"
K6_STATUS=${PIPESTATUS[0]}
set -e

RESET_LOG="$RESULT_DIR/completion-reset.log"
if [[ -f "$SUMMARY_FILE" ]]; then
  echo "Resetting items selected from $SUMMARY_FILE" | tee "$RESET_LOG"
  set +e
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true TEST_RUN_ID="$TEST_RUN_ID-reset" \
    ./scripts/reset-completions.sh --summary "$SUMMARY_FILE" 2>&1 | tee -a "$RESET_LOG"
  RESET_STATUS=${PIPESTATUS[0]}
  set -e
  if (( RESET_STATUS != 0 )); then
    echo 'Summary did not identify reset items; resetting every configured test itinerary item (safe fallback).' | tee -a "$RESET_LOG"
    set +e
    CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true TEST_RUN_ID="$TEST_RUN_ID-reset-fallback" \
      ./scripts/reset-completions.sh 2>&1 | tee -a "$RESET_LOG"
    RESET_STATUS=${PIPESTATUS[0]}
    set -e
  fi
else
  echo 'No summary was created; resetting every configured test itinerary item (safe fallback).' | tee "$RESET_LOG"
  set +e
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true TEST_RUN_ID="$TEST_RUN_ID-reset-fallback" \
    ./scripts/reset-completions.sh 2>&1 | tee -a "$RESET_LOG"
  RESET_STATUS=${PIPESTATUS[0]}
  set -e
fi

if (( RESET_STATUS != 0 )); then
  echo "Completion reset failed; do not start another LT-02 run. log=$RESET_LOG" >&2
  exit 1
fi
if (( K6_STATUS != 0 )); then
  echo "k6 execution failed with exit code $K6_STATUS; reset completed. results=$RESULT_DIR" >&2
  exit "$K6_STATUS"
fi
echo "Performance and completion reset completed. results=$RESULT_DIR"
