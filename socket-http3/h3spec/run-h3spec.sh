#!/usr/bin/env bash
# Run the h3spec HTTP/3 conformance suite (github.com/kazu-yamamoto/h3spec) against our PRODUCTION
# withHttp3Server, externally proving the Phase-0 / STEP-1 typed Http3Violation error codes — the
# inverse of socket-http3/docker-interop (there WE are the client against aioquic; here h3spec is the
# client against US).
#
#   socket-http3/h3spec/run-h3spec.sh                  # build h3spec, start our server, run, tear down
#   H3SPEC_PORT=4567 socket-http3/h3spec/run-h3spec.sh
#   H3SPEC_ARGS='-v'  socket-http3/h3spec/run-h3spec.sh # extra flags passed to h3spec
#
# The exit code is h3spec's, so once wired live this lane GATES on conformance. It is currently
# STAGED-BUT-DORMANT: the build-linux `run-h3spec` input defaults false and the whole redesign branch
# is buffer-blocked until buffer 5.13.2 publishes to Central. Needs Docker and a built
# :socket-quic-quiche quiche native (the server links it via the FFM backend).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
PORT="${H3SPEC_PORT:-4433}"
IMAGE="socket-http3-h3spec"
READY_MARKER="h3spec server ready on udp/"
CERT="${H3SPEC_CERT:-$ROOT/socket-http3/testcerts/cert.crt}"
KEY="${H3SPEC_KEY:-$ROOT/socket-http3/testcerts/cert.key}"

[ -f "$CERT" ] && [ -f "$KEY" ] || { echo "ERROR: server cert/key not found ($CERT, $KEY)" >&2; exit 1; }

# 1. Build the h3spec client image (slow on a cold cache — Haskell from source).
docker build -t "$IMAGE" "$DIR"

# 2. Start our server (gradle JavaExec) in its own process group so the whole gradle+JVM tree can be
#    torn down on exit. setsid (util-linux) gives a fresh group whose leader PID == PGID; without it
#    (e.g. a bare macOS dev box) fall back to killing the gradle PID with --no-daemon.
LOG="$(mktemp "${TMPDIR:-/tmp}/h3spec-server.XXXXXX.log")"
start_server() {
  local cmd=(env "H3SPEC_PORT=$PORT" "H3SPEC_CERT=$CERT" "H3SPEC_KEY=$KEY" \
    ./gradlew --no-daemon --console=plain -q :socket-http3:h3specServer)
  if command -v setsid >/dev/null 2>&1; then
    ( cd "$ROOT" && exec setsid "${cmd[@]}" ) >"$LOG" 2>&1 &
    SERVER_GROUP=1
  else
    ( cd "$ROOT" && exec "${cmd[@]}" ) >"$LOG" 2>&1 &
    SERVER_GROUP=0
  fi
  SERVER_PID=$!
}

cleanup() {
  if [ "${SERVER_GROUP:-0}" = 1 ]; then
    kill -TERM "-$SERVER_PID" 2>/dev/null || true
  else
    kill -TERM "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$LOG"
}
trap cleanup EXIT

start_server
echo "[h3spec] starting withHttp3Server on udp/$PORT (log: $LOG)"
ready=0
for _ in $(seq 1 180); do
  if grep -q "$READY_MARKER" "$LOG"; then ready=1; echo "[h3spec] server ready"; break; fi
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "ERROR: server exited before becoming ready" >&2; cat "$LOG" >&2; exit 1; }
  sleep 1
done
[ "$ready" = 1 ] || { echo "ERROR: server not ready within 180s" >&2; tail -40 "$LOG" >&2; exit 1; }

# 3. Run h3spec against the host server. --network host lets the container reach the host UDP port.
echo "[h3spec] running: h3spec ${H3SPEC_ARGS:-} 127.0.0.1 $PORT"
# shellcheck disable=SC2086
docker run --rm --network host "$IMAGE" ${H3SPEC_ARGS:-} 127.0.0.1 "$PORT"
