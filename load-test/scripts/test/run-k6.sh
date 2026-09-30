#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT="${1:?Usage: ./scripts/run-k6.sh scenarios/<scenario>.js [k6 options]}"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 명령 앞에서 지정한 실행 ID는 .env의 빈 기본값보다 우선한다. P-02처럼 여러
# 시나리오를 같은 실행 ID로 동시에 실행할 때 결과와 서버 로그를 묶기 위함이다.
CALLER_TEST_RUN_ID="${TEST_RUN_ID:-}"

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

if [[ -n "$CALLER_TEST_RUN_ID" ]]; then
  export TEST_RUN_ID="$CALLER_TEST_RUN_ID"
fi

export TEST_RUN_ID="${TEST_RUN_ID:-$(TZ=Asia/Seoul date +%Y-%m-%d-%H-%M)}"
if [[ ! "$TEST_RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "TEST_RUN_ID may contain only letters, numbers, dot, underscore, and hyphen: $TEST_RUN_ID" >&2
  exit 1
fi

SCENARIO_NAME="$(basename "$SCRIPT" .js)"
RESULT_DIR="results/$TEST_RUN_ID"
SUMMARY_FILE="$RESULT_DIR/${SCENARIO_NAME}-summary.json"
mkdir -p "$RESULT_DIR"

# 같은 분에 같은 시나리오를 여러 번 실행해도 기존 결과를 덮어쓰지 않는다.
# 첫 파일은 <scenario>-summary.json, 이후 파일은 -2, -3 순서로 저장한다.
if [[ -e "$SUMMARY_FILE" ]]; then
  sequence=2
  while [[ -e "$RESULT_DIR/${SCENARIO_NAME}-summary-${sequence}.json" ]]; do
    ((sequence += 1))
  done
  SUMMARY_FILE="$RESULT_DIR/${SCENARIO_NAME}-summary-${sequence}.json"
fi

echo "run_id=$TEST_RUN_ID"
echo "scenario=$SCRIPT"
echo "summary=$SUMMARY_FILE"

# k6 validates BASE_URL, CONFIRM_STAGING, credentials, and write/external guards
# while loading the scenario before the first network request.
k6 run "$@" --summary-export "$SUMMARY_FILE" "$SCRIPT"
