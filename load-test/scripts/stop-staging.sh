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

if [[ "${CONFIRM_STAGING_CONTROL:-}" != "true" ]]; then
  echo "Set CONFIRM_STAGING_CONTROL=true in load-test/.env to stop Staging." >&2
  exit 1
fi

command -v aws >/dev/null || { echo "AWS CLI is required" >&2; exit 1; }

aws ec2 stop-instances --region "$AWS_REGION" --instance-ids "$STAGING_INSTANCE_ID" >/dev/null
aws ec2 wait instance-stopped --region "$AWS_REGION" --instance-ids "$STAGING_INSTANCE_ID"
echo "Staging instance stopped: $STAGING_INSTANCE_ID"
