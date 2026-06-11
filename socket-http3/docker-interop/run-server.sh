#!/usr/bin/env bash
# Build + run the aioquic HTTP/3 echo server for the gated Http3DockerInteropTests.
#
#   ./run-server.sh            # build (if needed) and run in the FOREGROUND on udp/4433 (local dev)
#   ./run-server.sh start      # build + run DETACHED, block until ready, then return (CI)
#   ./run-server.sh stop       # stop + remove the container
#   QPACK_MAX_TABLE_CAPACITY=256 ./run-server.sh   # force eviction with a smaller table
#
# Then, in another shell: ./gradlew :socket-http3:jvmTest --tests '*Http3DockerInteropTests*'
# The test skips itself if the server is not reachable on 127.0.0.1:4433, so it never flaky-fails CI.
set -euo pipefail

IMAGE="socket-http3-interop"
NAME="socket-http3-interop"
PORT="${HTTP3_PORT:-4433}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The line server.py prints (flush=True) once it is bound and serving — our readiness signal.
READY_MARKER="h3 echo server on udp/"

if [[ "${1:-}" == "stop" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "stopped $NAME"
  exit 0
fi

docker build -t "$IMAGE" "$DIR"
docker rm -f "$NAME" >/dev/null 2>&1 || true

run_args=(
  --name "$NAME"
  -p "${PORT}:4433/udp"
  -e "QPACK_MAX_TABLE_CAPACITY=${QPACK_MAX_TABLE_CAPACITY:-4096}"
  -e "QPACK_BLOCKED_STREAMS=${QPACK_BLOCKED_STREAMS:-16}"
)

if [[ "${1:-}" == "start" ]]; then
  # Detached: start, then block until the server logs it is serving so the caller (CI) can run the
  # test against a server that is provably up — making Http3DockerInteropTests actually RUN, not skip.
  docker run -d --rm "${run_args[@]}" "$IMAGE" >/dev/null
  for _ in $(seq 1 30); do
    if docker logs "$NAME" 2>&1 | grep -q "$READY_MARKER"; then
      echo "interop server ready on udp/${PORT}"
      exit 0
    fi
    sleep 1
  done
  echo "ERROR: interop server did not become ready within 30s" >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  exit 1
fi

exec docker run --rm "${run_args[@]}" "$IMAGE"
