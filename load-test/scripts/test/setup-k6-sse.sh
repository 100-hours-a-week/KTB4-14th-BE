#!/usr/bin/env bash
set -Eeuo pipefail

# Build a project-local k6 binary with the SSE extension.  The binary is
# intentionally gitignored so every operator can build it for their platform.
#
# Preferred order:
#   1. a locally installed xk6 (XK6_BIN can override its command/path)
#   2. the pinned grafana/xk6 Docker image, when its daemon is available
#
# The extension version is pinned because the SSE load scenarios import
# `k6/x/sse`; the stock k6 binary does not provide that module.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT="$ROOT_DIR/.bin/k6-sse"
TEMP_OUTPUT="${OUTPUT}.tmp.$$"
K6_VERSION="${K6_SSE_K6_VERSION:-v1.1.0}"
XK6_SSE_VERSION="${XK6_SSE_VERSION:-v0.1.11}"
XK6_IMAGE="${XK6_IMAGE:-grafana/xk6:1.1.0}"
XK6_COMMAND="${XK6_BIN:-xk6}"

case "$(uname -s)" in
  Darwin) TARGET_OS="darwin" ;;
  Linux) TARGET_OS="linux" ;;
  *)
    echo "Unsupported host OS for the SSE k6 build: $(uname -s)" >&2
    exit 1
    ;;
esac

case "$(uname -m)" in
  arm64|aarch64) TARGET_ARCH="arm64" ;;
  x86_64|amd64) TARGET_ARCH="amd64" ;;
  *)
    echo "Unsupported host architecture for the SSE k6 build: $(uname -m)" >&2
    exit 1
    ;;
esac

mkdir -p "$(dirname "$OUTPUT")"
trap 'rm -f "$TEMP_OUTPUT"' EXIT

build_with_local_xk6() {
  echo "Building k6 SSE binary with local xk6: $XK6_COMMAND"
  "$XK6_COMMAND" build "$K6_VERSION" \
    --with "github.com/phymbert/xk6-sse@$XK6_SSE_VERSION" \
    --os "$TARGET_OS" \
    --arch "$TARGET_ARCH" \
    --output "$TEMP_OUTPUT"
}

build_with_docker() {
  echo "Building k6 SSE binary with Docker image: $XK6_IMAGE"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    --env GOCACHE=/tmp/go-build \
    --env GOMODCACHE=/tmp/go-mod \
    --volume "$ROOT_DIR:/xk6" \
    --workdir /xk6 \
    "$XK6_IMAGE" \
    build "$K6_VERSION" \
    --with "github.com/phymbert/xk6-sse@$XK6_SSE_VERSION" \
    --os "$TARGET_OS" \
    --arch "$TARGET_ARCH" \
    --output "/xk6/.bin/$(basename "$TEMP_OUTPUT")"
}

if command -v "$XK6_COMMAND" >/dev/null 2>&1; then
  build_with_local_xk6
elif command -v docker >/dev/null 2>&1; then
  if ! docker info >/dev/null 2>&1; then
    cat >&2 <<'EOF'
Docker is installed but its daemon is not available.
Start Docker Desktop (or the Docker daemon), then run this command again.
Alternatively install xk6 and rerun with XK6_BIN=/path/to/xk6.
EOF
    exit 1
  fi
  build_with_docker
else
  cat >&2 <<'EOF'
Unable to build the SSE-enabled k6 binary: neither xk6 nor Docker is available.
Install xk6, or install/start Docker, then rerun this script.
EOF
  exit 1
fi

if [[ ! -x "$TEMP_OUTPUT" ]]; then
  echo "k6 SSE build did not create an executable: $TEMP_OUTPUT" >&2
  exit 1
fi

if ! VERSION_OUTPUT="$("$TEMP_OUTPUT" version 2>&1)"; then
  echo "Built k6 SSE candidate could not run on this host: $TEMP_OUTPUT" >&2
  echo "$VERSION_OUTPUT" >&2
  exit 1
fi
if ! grep -Fq 'k6/x/sse' <<<"$VERSION_OUTPUT"; then
  cat >&2 <<EOF
The built k6 candidate does not contain the k6/x/sse module.
The existing project-local binary was not replaced.

Candidate version output:
$VERSION_OUTPUT
EOF
  exit 1
fi

mv -f "$TEMP_OUTPUT" "$OUTPUT"
trap - EXIT

echo "Built SSE-enabled k6: $OUTPUT"
"$OUTPUT" version
echo "run-k6.sh will automatically prefer this binary for all scenarios."
