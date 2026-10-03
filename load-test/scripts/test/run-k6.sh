#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT="${1:?Usage: ./scripts/test/run-k6.sh scenarios/<scenario>.js [k6 options]}"
shift

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

# 명령 앞에서 지정한 실행 ID는 .env의 빈 기본값보다 우선한다. P-02처럼 여러
# 시나리오를 같은 실행 ID로 동시에 실행할 때 결과와 서버 로그를 묶기 위함이다.
CALLER_TEST_RUN_ID="${TEST_RUN_ID:-}"
CALLER_K6_BIN="${K6_BIN:-}"
CALLER_K6_WEB_DASHBOARD="${K6_WEB_DASHBOARD:-}"
CALLER_K6_WEB_DASHBOARD_HOST="${K6_WEB_DASHBOARD_HOST:-}"
CALLER_K6_WEB_DASHBOARD_PORT="${K6_WEB_DASHBOARD_PORT:-}"
CALLER_K6_WEB_DASHBOARD_PERIOD="${K6_WEB_DASHBOARD_PERIOD:-}"
CALLER_K6_WEB_DASHBOARD_OPEN="${K6_WEB_DASHBOARD_OPEN:-}"
CALLER_K6_WEB_DASHBOARD_EXPORT="${K6_WEB_DASHBOARD_EXPORT:-}"

if [[ ! -f "$SCRIPT" ]]; then
  echo "Scenario file not found: $SCRIPT" >&2
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
if [[ -n "$CALLER_K6_BIN" ]]; then
  export K6_BIN="$CALLER_K6_BIN"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD" ]]; then
  export K6_WEB_DASHBOARD="$CALLER_K6_WEB_DASHBOARD"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD_HOST" ]]; then
  export K6_WEB_DASHBOARD_HOST="$CALLER_K6_WEB_DASHBOARD_HOST"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD_PORT" ]]; then
  export K6_WEB_DASHBOARD_PORT="$CALLER_K6_WEB_DASHBOARD_PORT"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD_PERIOD" ]]; then
  export K6_WEB_DASHBOARD_PERIOD="$CALLER_K6_WEB_DASHBOARD_PERIOD"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD_OPEN" ]]; then
  export K6_WEB_DASHBOARD_OPEN="$CALLER_K6_WEB_DASHBOARD_OPEN"
fi
if [[ -n "$CALLER_K6_WEB_DASHBOARD_EXPORT" ]]; then
  export K6_WEB_DASHBOARD_EXPORT="$CALLER_K6_WEB_DASHBOARD_EXPORT"
fi

# 모든 표준 부하테스트는 로컬 k6 Web Dashboard를 기본 제공한다. 자동 브라우저
# 열기는 창을 닫을 때까지 k6 종료가 지연될 수 있으므로 명시적으로 요청할 때만 켠다.
export K6_WEB_DASHBOARD="${K6_WEB_DASHBOARD:-true}"
export K6_WEB_DASHBOARD_HOST="${K6_WEB_DASHBOARD_HOST:-127.0.0.1}"
export K6_WEB_DASHBOARD_PORT="${K6_WEB_DASHBOARD_PORT:-5665}"
export K6_WEB_DASHBOARD_PERIOD="${K6_WEB_DASHBOARD_PERIOD:-1s}"

# Detect transitive local imports too: P-02 imports the shared SSE flow rather
# than importing k6/x/sse directly.
scenario_requires_sse() {
  node - "$SCRIPT" <<'NODE'
const fs = require('fs');
const path = require('path');
const root = process.cwd();
const visited = new Set();

function visit(file) {
  file = path.resolve(file);
  if (visited.has(file) || !fs.existsSync(file)) return false;
  visited.add(file);
  const source = fs.readFileSync(file, 'utf8');
  if (/['"]k6\/x\/sse['"]/.test(source)) return true;
  const imports = source.matchAll(/(?:import|export)\s+(?:[\s\S]*?\s+from\s+)?['"]([^'"]+)['"]/g);
  for (const match of imports) {
    const specifier = match[1];
    if (!specifier.startsWith('.')) continue;
    let child = path.resolve(path.dirname(file), specifier);
    if (!path.extname(child)) child += '.js';
    if (visit(child)) return true;
  }
  return false;
}

process.stdout.write(visit(path.resolve(root, process.argv[2])) ? 'true' : 'false');
NODE
}

SCENARIO_REQUIRES_SSE="$(scenario_requires_sse)"

select_k6_binary() {
  if [[ -n "${K6_BIN:-}" ]]; then
    printf '%s' "$K6_BIN"
  elif [[ -x .bin/k6-sse ]]; then
    # Once the project-local binary exists, use the same k6 version for every
    # scenario so results from one execution set remain comparable.
    printf '%s' "$ROOT_DIR/.bin/k6-sse"
  elif command -v k6 >/dev/null 2>&1; then
    command -v k6
  else
    return 1
  fi
}

if ! SELECTED_K6="$(select_k6_binary)"; then
  echo "k6 CLI is required. Install it from https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 1
fi
if [[ "$SELECTED_K6" == */* && ! -x "$SELECTED_K6" ]]; then
  echo "K6_BIN is not an executable file: $SELECTED_K6" >&2
  exit 1
fi
if [[ "$SELECTED_K6" != */* ]] && ! command -v "$SELECTED_K6" >/dev/null 2>&1; then
  echo "K6_BIN command was not found: $SELECTED_K6" >&2
  exit 1
fi
SELECTED_K6_VERSION="$("$SELECTED_K6" version 2>&1)"
SELECTED_K6_VERSION_LINE="${SELECTED_K6_VERSION%%$'\n'*}"

if [[ "$SCENARIO_REQUIRES_SSE" == "true" ]]; then
  # `inspect` loads imports but makes no HTTP requests.  Do this before auth
  # preflight so a stock binary cannot accidentally begin an SSE load run.
  if ! INSPECT_ERROR="$("$SELECTED_K6" inspect --include-system-env-vars "$SCRIPT" 2>&1)"; then
    if grep -Eq 'unknown dependency.*k6/x/sse|unknown module.*k6/x/sse|cannot find.*k6/x/sse' <<<"$INSPECT_ERROR"; then
      cat >&2 <<EOF
The selected k6 binary cannot inspect this SSE scenario.
SSE scenarios require the project-local xk6-sse binary.
Build it with: ./scripts/test/setup-k6-sse.sh
Or set K6_BIN to an SSE-enabled k6 executable.
Selected binary: $SELECTED_K6

k6 inspect output:
$INSPECT_ERROR
EOF
    else
      cat >&2 <<EOF
SSE scenario validation failed before authentication or load execution.
Check BASE_URL, safety flags, test data, and scenario options.
Selected binary: $SELECTED_K6

k6 inspect output:
$INSPECT_ERROR
EOF
    fi
    exit 1
  fi
fi

if [[ "$SCRIPT" == scenarios/api/completion-ramping-arrival.js || "$SCRIPT" == scenarios/api/travel-plan-create-ramping-arrival.js ]]; then
  export TEST_RUN_ID="${TEST_RUN_ID:-$(TZ=Asia/Seoul date +%Y-%m-%d-%H-%M-%S)-single-api-$$}"
else
  export TEST_RUN_ID="${TEST_RUN_ID:-$(TZ=Asia/Seoul date +%Y-%m-%d-%H-%M)}"
fi
if [[ ! "$TEST_RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "TEST_RUN_ID may contain only letters, numbers, dot, underscore, and hyphen: $TEST_RUN_ID" >&2
  exit 1
fi

SCENARIO_NAME="${SCRIPT#scenarios/}"
SCENARIO_NAME="${SCENARIO_NAME%.js}"
SCENARIO_NAME="${SCENARIO_NAME//\//-}"
RESULT_DIR="results/$TEST_RUN_ID"
SUMMARY_FILE="$RESULT_DIR/${SCENARIO_NAME}-summary.json"
if [[ "$SCRIPT" == scenarios/api/completion-ramping-arrival.js || "$SCRIPT" == scenarios/api/travel-plan-create-ramping-arrival.js ]]; then
  if [[ -e "$SUMMARY_FILE" || -e "$RESULT_DIR/completion-items.json" || -e "$RESULT_DIR/generation-results.json" ]]; then
    echo "Single API artifacts already exist in $RESULT_DIR; choose a new TEST_RUN_ID" >&2
    exit 1
  fi
fi
mkdir -p "$RESULT_DIR"
export SINGLE_API_ARTIFACT_DIR="$RESULT_DIR"

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
echo "k6_bin=$SELECTED_K6"
echo "k6_version=$SELECTED_K6_VERSION_LINE"
if [[ "$K6_WEB_DASHBOARD" == "true" ]]; then
  echo "web_dashboard=http://${K6_WEB_DASHBOARD_HOST}:${K6_WEB_DASHBOARD_PORT}"
fi

# 측정 전 인증을 1회 확인한다. 401/403이면 고부하 요청을 시작하지 않는다.
node scripts/test/preflight-auth.mjs

# k6 validates BASE_URL, CONFIRM_STAGING, credentials, and write/external guards
# while loading the scenario before the first network request.
"$SELECTED_K6" run "$@" --summary-export "$SUMMARY_FILE" "$SCRIPT"
