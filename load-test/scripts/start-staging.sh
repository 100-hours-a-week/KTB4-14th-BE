#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${AWS_REGION:?AWS_REGION is required}"
: "${STAGING_INSTANCE_ID:?STAGING_INSTANCE_ID is required}"
: "${HEALTH_URL:?HEALTH_URL is required}"

if [[ "${CONFIRM_STAGING_CONTROL:-}" != "true" ]]; then
  echo "Set CONFIRM_STAGING_CONTROL=true in load-test/.env to start Staging." >&2
  exit 1
fi

command -v aws >/dev/null || { echo "AWS CLI is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

aws ec2 start-instances --region "$AWS_REGION" --instance-ids "$STAGING_INSTANCE_ID" >/dev/null
aws ec2 wait instance-running --region "$AWS_REGION" --instance-ids "$STAGING_INSTANCE_ID"

if [[ -n "${ECS_CLUSTER:-}" && -n "${ECS_SERVICES:-}" ]]; then
  IFS=',' read -r -a services <<< "$ECS_SERVICES"
  aws ecs wait services-stable --region "$AWS_REGION" --cluster "$ECS_CLUSTER" --services "${services[@]}"
fi

max_attempts="${HEALTH_CHECK_ATTEMPTS:-60}"
interval_seconds="${HEALTH_CHECK_INTERVAL_SECONDS:-10}"
for ((attempt = 1; attempt <= max_attempts; attempt += 1)); do
  if curl --fail --silent --show-error --max-time 5 "$HEALTH_URL" >/dev/null; then
    echo "Staging is healthy: $HEALTH_URL"
    exit 0
  fi
  echo "Waiting for Staging health ($attempt/$max_attempts)..." >&2
  sleep "$interval_seconds"
done

echo "Staging did not become healthy: $HEALTH_URL" >&2
exit 1
