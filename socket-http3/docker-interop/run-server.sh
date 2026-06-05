#!/usr/bin/env bash
# Build + run the aioquic HTTP/3 echo server for the gated Http3DockerInteropTests.
#
#   ./run-server.sh            # build (if needed) and run in the foreground on udp/4433
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

if [[ "${1:-}" == "stop" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "stopped $NAME"
  exit 0
fi

docker build -t "$IMAGE" "$DIR"
docker rm -f "$NAME" >/dev/null 2>&1 || true
exec docker run --rm --name "$NAME" \
  -p "${PORT}:4433/udp" \
  -e "QPACK_MAX_TABLE_CAPACITY=${QPACK_MAX_TABLE_CAPACITY:-4096}" \
  -e "QPACK_BLOCKED_STREAMS=${QPACK_BLOCKED_STREAMS:-16}" \
  "$IMAGE"
