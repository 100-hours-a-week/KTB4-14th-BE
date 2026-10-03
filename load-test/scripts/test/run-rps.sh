#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: CONFIRM_STAGING=true ./scripts/test/run-rps.sh <target> <http-rps> [duration]

Targets:
  itinerary               LT-01: upcoming + recent + itinerary (HTTP 3 requests per flow)
  generation-polling      LT-05: generation job status (HTTP 1 request per flow)
  my-travels              GET /api/travel-plans/me
  user-me                 GET /api/users/me
  notifications           GET /api/notifications
  notification-settings   GET /api/notification-settings
  travel-plan-status      GET /api/travel-plans/{travelPlanId}/status
  nickname                PATCH /api/users/me/nickname (requires ALLOW_WRITE_TESTS=true)

Optional overrides:
  LOADTEST_RPS_PRE_ALLOCATED_VUS=<n>
  LOADTEST_RPS_MAX_VUS=<n>
EOF
  exit 2
}

TARGET="${1:-}"
RPS="${2:-}"
DURATION="${3:-5m}"

[[ -n "$TARGET" && -n "$RPS" ]] || usage
[[ "$RPS" =~ ^[1-9][0-9]*$ ]] || { echo "http-rps must be a positive integer: $RPS" >&2; exit 2; }
[[ "$DURATION" =~ ^[0-9]+(ms|s|m|h)$ ]] || { echo "duration must be a k6 duration such as 30s or 5m: $DURATION" >&2; exit 2; }

# 50 RPS에서 기존 LT-01 기본 VU 설정(10)을 유지하는 단순 초기값이다.
# 응답이 느려 dropped_iterations가 나타나면 이 값보다 큰 VU 값을 명시적으로 전달한다.
DEFAULT_PRE_ALLOCATED_VUS=$(( (RPS + 4) / 5 ))
if (( DEFAULT_PRE_ALLOCATED_VUS < 5 )); then
  DEFAULT_PRE_ALLOCATED_VUS=5
fi
PRE_ALLOCATED_VUS="${LOADTEST_RPS_PRE_ALLOCATED_VUS:-$DEFAULT_PRE_ALLOCATED_VUS}"
[[ "$PRE_ALLOCATED_VUS" =~ ^[1-9][0-9]*$ ]] || { echo "LOADTEST_RPS_PRE_ALLOCATED_VUS must be a positive integer" >&2; exit 2; }

DEFAULT_MAX_VUS=$(( PRE_ALLOCATED_VUS * 2 ))
if (( DEFAULT_MAX_VUS < 100 )); then
  DEFAULT_MAX_VUS=100
fi
MAX_VUS="${LOADTEST_RPS_MAX_VUS:-$DEFAULT_MAX_VUS}"
[[ "$MAX_VUS" =~ ^[1-9][0-9]*$ ]] || { echo "LOADTEST_RPS_MAX_VUS must be a positive integer" >&2; exit 2; }
if (( MAX_VUS < PRE_ALLOCATED_VUS )); then
  echo "LOADTEST_RPS_MAX_VUS must be at least LOADTEST_RPS_PRE_ALLOCATED_VUS" >&2
  exit 2
fi

case "$TARGET" in
  itinerary)
    # LT-01 flow 하나는 HTTP 요청 3개이므로 RPS × 60 ÷ 3 = RPS × 20 flow/min이다.
    FLOW_PER_MINUTE=$(( RPS * 20 ))
    echo "target=itinerary http_rps=$RPS flow_per_minute=$FLOW_PER_MINUTE duration=$DURATION vus=$PRE_ALLOCATED_VUS-$MAX_VUS"
    exec env \
      ITINERARY_FLOW_RATE_PER_MINUTE="$FLOW_PER_MINUTE" \
      ITINERARY_READ_DURATION="$DURATION" \
      ITINERARY_READ_PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" \
      ITINERARY_READ_MAX_VUS="$MAX_VUS" \
      ./scripts/test/run-k6.sh scenarios/itinerary/constant-arrival.js
    ;;
  generation-polling)
    REQUESTS_PER_MINUTE=$(( RPS * 60 ))
    echo "target=generation-polling http_rps=$RPS requests_per_minute=$REQUESTS_PER_MINUTE duration=$DURATION vus=$PRE_ALLOCATED_VUS-$MAX_VUS"
    exec env \
      GENERATION_POLL_RATE_PER_MINUTE="$REQUESTS_PER_MINUTE" \
      GENERATION_POLL_DURATION="$DURATION" \
      GENERATION_POLL_PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" \
      GENERATION_POLL_MAX_VUS="$MAX_VUS" \
      ./scripts/test/run-k6.sh scenarios/generation/polling.js
    ;;
  my-travels|user-me|notifications|notification-settings|travel-plan-status|nickname)
    echo "target=$TARGET http_rps=$RPS duration=$DURATION vus=$PRE_ALLOCATED_VUS-$MAX_VUS"
    exec env \
      API_TARGET="$TARGET" \
      API_RPS="$RPS" \
      API_DURATION="$DURATION" \
      API_PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" \
      API_MAX_VUS="$MAX_VUS" \
      ./scripts/test/run-k6.sh scenarios/api/constant-arrival.js
    ;;
  *)
    echo "Unknown target: $TARGET" >&2
    usage
    ;;
esac
