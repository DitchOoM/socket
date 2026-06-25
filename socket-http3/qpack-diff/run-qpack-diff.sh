#!/usr/bin/env bash
# Build + run the ls-qpack (pylsqpack) QPACK differential oracle for QpackDifferentialInteropTests.
#
#   ./run-qpack-diff.sh            # build (if needed) and run in the FOREGROUND on tcp/4434 (local dev)
#   ./run-qpack-diff.sh start      # build + run DETACHED, block until ready, then return (CI)
#   ./run-qpack-diff.sh stop       # stop + remove the container
#
# Then, in another shell: ./gradlew :socket-http3:jvmTest --tests '*QpackDifferentialInteropTests*'
# The test skips itself if the oracle is not reachable on 127.0.0.1:4434, so it never flaky-fails CI.
#
# No Docker? A venv works too: python3 -m venv v && v/bin/pip install pylsqpack &&
#   v/bin/python qpack-diff-server.py
set -euo pipefail

IMAGE="socket-qpack-diff"
NAME="socket-qpack-diff"
PORT="${QPACK_DIFF_PORT:-4434}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The line the server prints (flush=True) once it is bound and serving — our readiness signal.
READY_MARKER="qpack-diff oracle"

if [[ "${1:-}" == "stop" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "stopped $NAME"
  exit 0
fi

docker build -t "$IMAGE" "$DIR"
docker rm -f "$NAME" >/dev/null 2>&1 || true

run_args=(
  --name "$NAME"
  -p "127.0.0.1:${PORT}:4434/tcp"
)

if [[ "${1:-}" == "start" ]]; then
  # Detached: start, then block until the server logs it is serving so the caller (CI) can run the
  # test against a server that is provably up — making the test actually RUN, not skip.
  docker run -d --rm "${run_args[@]}" "$IMAGE" >/dev/null
  for _ in $(seq 1 30); do
    if docker logs "$NAME" 2>&1 | grep -q "$READY_MARKER"; then
      echo "qpack-diff oracle ready on tcp/${PORT}"
      exit 0
    fi
    sleep 1
  done
  echo "ERROR: qpack-diff oracle did not become ready within 30s" >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  exit 1
fi

exec docker run --rm "${run_args[@]}" "$IMAGE"
