#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if [[ -f .env ]]; then
  set -a
  # .env is local, gitignored operator configuration.
  # shellcheck disable=SC1091
  source .env
  set +a
fi

exec node scripts/initData/seed-test-user.mjs "$@"
