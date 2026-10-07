#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

CALLER_TEST_RUN_ID="${TEST_RUN_ID:-}"
CALLER_K6_BIN="${K6_BIN:-}"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

if [[ -n "$CALLER_TEST_RUN_ID" ]]; then
  export TEST_RUN_ID="$CALLER_TEST_RUN_ID"
fi
if [[ -n "$CALLER_K6_BIN" ]]; then
  export K6_BIN="$CALLER_K6_BIN"
fi

: "${BASE_URL:?BASE_URL is required}"
if [[ "${CONFIRM_STAGING:-}" != "true" ]]; then
  echo "CONFIRM_STAGING=true is required" >&2
  exit 1
fi
if [[ "${ALLOW_WRITE_TESTS:-}" != "true" ]]; then
  echo "ALLOW_WRITE_TESTS=true is required for the LT-02 baseline" >&2
  exit 1
fi
if [[ "${CONFIRM_COMPLETION_RESET:-}" != "true" ]]; then
  echo "CONFIRM_COMPLETION_RESET=true is required to restore LT-02 data" >&2
  exit 1
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v node >/dev/null || { echo "Node.js is required" >&2; exit 1; }

if [[ -z "${K6_BIN:-}" ]]; then
  if [[ -x .bin/k6-sse ]]; then
    export K6_BIN="$ROOT_DIR/.bin/k6-sse"
  elif command -v k6 >/dev/null 2>&1; then
    export K6_BIN="$(command -v k6)"
  else
    echo "SSE-enabled k6 is required. Run ./scripts/test/setup-k6-sse.sh first." >&2
    exit 1
  fi
fi

if [[ "$K6_BIN" == */* ]]; then
  [[ -x "$K6_BIN" ]] || { echo "K6_BIN is not executable: $K6_BIN" >&2; exit 1; }
elif ! command -v "$K6_BIN" >/dev/null 2>&1; then
  echo "K6_BIN command was not found: $K6_BIN" >&2
  exit 1
fi

if ! K6_VERSION_OUTPUT="$("$K6_BIN" version 2>&1)"; then
  echo "Could not run K6_BIN: $K6_BIN" >&2
  echo "$K6_VERSION_OUTPUT" >&2
  exit 1
fi
if ! grep -Fq 'k6/x/sse' <<<"$K6_VERSION_OUTPUT"; then
  echo "Baseline suite requires an SSE-enabled k6. Run ./scripts/test/setup-k6-sse.sh first." >&2
  echo "Selected binary: $K6_BIN" >&2
  exit 1
fi
K6_VERSION_LINE="$(head -n 1 <<<"$K6_VERSION_OUTPUT")"

export TEST_RUN_ID="${TEST_RUN_ID:-$(TZ=Asia/Seoul date +%Y-%m-%d-%H-%M)}"
if [[ ! "$TEST_RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Invalid TEST_RUN_ID: $TEST_RUN_ID" >&2
  exit 1
fi

RESULT_DIR="results/$TEST_RUN_ID"
if [[ -e "$RESULT_DIR" ]]; then
  echo "Suite result directory already exists: $RESULT_DIR" >&2
  echo "Wait until the next minute or set a new TEST_RUN_ID." >&2
  exit 1
fi
mkdir -p "$RESULT_DIR"

exec > >(tee "$RESULT_DIR/suite.log") 2>&1

export ITINERARY_FLOW_RATE_PER_MINUTE="${ITINERARY_FLOW_RATE_PER_MINUTE:-13}"
export ITINERARY_READ_DURATION="${ITINERARY_READ_DURATION:-5m}"
export GENERATION_POLL_RATE_PER_MINUTE="${GENERATION_POLL_RATE_PER_MINUTE:-30}"
export GENERATION_POLL_DURATION="${GENERATION_POLL_DURATION:-5m}"
export COMPLETION_FLOW_RATE_PER_MINUTE="${COMPLETION_FLOW_RATE_PER_MINUTE:-1}"
export COMPLETION_DURATION="${COMPLETION_DURATION:-5m}"
export SSE_CONNECTIONS="${SSE_CONNECTIONS:-1}"
export SSE_DURATION="${SSE_DURATION:-1m}"

cat > "$RESULT_DIR/suite-config.txt" <<EOF
run_id=$TEST_RUN_ID
started_at_kst=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S %Z')
k6_bin=$K6_BIN
k6_version=$K6_VERSION_LINE
itinerary_flow_rate_per_minute=$ITINERARY_FLOW_RATE_PER_MINUTE
itinerary_read_duration=$ITINERARY_READ_DURATION
generation_poll_rate_per_minute=$GENERATION_POLL_RATE_PER_MINUTE
generation_poll_duration=$GENERATION_POLL_DURATION
completion_flow_rate_per_minute=$COMPLETION_FLOW_RATE_PER_MINUTE
completion_duration=$COMPLETION_DURATION
sse_connections=$SSE_CONNECTIONS
sse_duration=$SSE_DURATION
EOF

completion_cleanup_required=false
cleanup_on_exit() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$completion_cleanup_required" == "true" ]]; then
    echo "Completion run was interrupted; resetting all configured completion items..." >&2
    set +e
    CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
      ./scripts/reset-completions.sh
    local reset_status=$?
    set -e
    if ((reset_status != 0)); then
      echo "Emergency completion reset failed; do not start another LT-02 run." >&2
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup_on_exit EXIT INT TERM

assert_standard() {
  node scripts/test/assert-k6-summary.mjs --summary "$1"
}

echo "=== Audigo baseline suite ==="
echo "run_id=$TEST_RUN_ID"
echo "result_dir=$RESULT_DIR"

node scripts/test/validate-test-data.mjs --min-token-ttl-seconds 1800

if [[ "$BASE_URL" == "https://api.audigo.kr" || "$BASE_URL" == "https://api.audigo.kr/" ]]; then
  echo "Production BASE_URL is blocked" >&2
  exit 1
fi
curl --fail --silent --show-error --max-time 10 "${BASE_URL%/}/health" >/dev/null
echo "Staging health check passed."

echo "=== 1/6 read smoke ==="
./scripts/test/run-k6.sh scenarios/smoke.js
assert_standard "$RESULT_DIR/smoke-summary.json"

echo "=== 2/6 LT-01 itinerary read baseline ==="
./scripts/test/run-k6.sh scenarios/itinerary/constant-arrival.js
assert_standard "$RESULT_DIR/itinerary-constant-arrival-summary.json"

echo "=== 3/6 LT-05 generation polling baseline ==="
./scripts/test/run-k6.sh scenarios/generation/polling.js
assert_standard "$RESULT_DIR/generation-polling-summary.json"

echo "=== Preparing LT-02 completion data ==="
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
  ./scripts/reset-completions.sh

echo "=== 4/6 LT-02 completion baseline ==="
completion_cleanup_required=true
completion_exit=0
CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true \
  ./scripts/test/run-k6.sh scenarios/completion/constant-arrival.js || completion_exit=$?

COMPLETION_SUMMARY="$RESULT_DIR/completion-constant-arrival-summary.json"
if [[ -f "$COMPLETION_SUMMARY" ]]; then
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
    ./scripts/reset-completions.sh --summary "$COMPLETION_SUMMARY"
else
  CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true \
    ./scripts/reset-completions.sh
fi
completion_cleanup_required=false

if ((completion_exit != 0)); then
  echo "LT-02 k6 execution failed with exit code $completion_exit" >&2
  exit "$completion_exit"
fi
assert_standard "$COMPLETION_SUMMARY"

echo "=== 5/6 LT-06 SSE handshake smoke ==="
./scripts/test/run-k6.sh scenarios/sse/handshake-smoke.js
node scripts/test/assert-k6-summary.mjs \
  --summary "$RESULT_DIR/sse-handshake-smoke-summary.json" \
  --mode sse-handshake \
  --min-sse-attempts 1

echo "=== 6/6 LT-06 SSE connection baseline ==="
./scripts/test/run-k6.sh scenarios/sse/constant-vus.js
node scripts/test/assert-k6-summary.mjs \
  --summary "$RESULT_DIR/sse-constant-vus-summary.json" \
  --mode sse-load \
  --min-sse-attempts "$SSE_CONNECTIONS"

cat >> "$RESULT_DIR/suite-config.txt" <<EOF
finished_at_kst=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S %Z')
status=passed
EOF

echo "=== Baseline suite passed ==="
echo "results=$RESULT_DIR"
trap - EXIT INT TERM
