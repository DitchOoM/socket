#!/usr/bin/env bash
# Browser WebTransport interop runner (handoff §3): start an external withHttp3Server (the
# BrowserInteropServer harness, JVM/quiche, `pinned` EC P-256 leaf), then run the jsBrowserTest in real
# headless Chrome via Karma against it. The harness binds an ephemeral port and writes its config
# (url + leaf SHA-256) to build/wt-interop/config.properties; generateBrowserInteropConfig bakes that
# into the compiled test. Requires JAVA_HOME=<JDK21> and Chrome on PATH (or set CHROME_BIN).
#
# The server build runs with --no-daemon (its own JVM, so it can block on the stop file without holding
# the shared Gradle daemon that jsBrowserTest needs). A stop file ends it; an EXIT trap always cleans up.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 (e.g. the provisioned Temurin 21)}"

WORK="$ROOT/socket-webtransport/build/wt-interop"
CFG="$WORK/config.properties"
STOP="$WORK/stop"
SERVER_LOG="$WORK/server.log"
mkdir -p "$WORK"
rm -f "$CFG" "$STOP"

echo "[interop] starting external WebTransport server (--no-daemon)..."
./gradlew --no-daemon :socket-webtransport:jvmTest \
  --tests 'com.ditchoom.socket.webtransport.BrowserInteropServer' \
  -Dwt.interop.server=true \
  -Dwt.interop.configFile="$CFG" \
  -Dwt.interop.stopFile="$STOP" \
  --rerun-tasks --console=plain >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
  echo "[interop] stopping server..."
  touch "$STOP" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "[interop] waiting for server readiness ($CFG)..."
for _ in $(seq 1 150); do
  [ -f "$CFG" ] && break
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "[interop] server process exited early; log tail:"; tail -30 "$SERVER_LOG"; exit 1
  fi
  sleep 2
done
[ -f "$CFG" ] || { echo "[interop] server never wrote $CFG; log tail:"; tail -30 "$SERVER_LOG"; exit 1; }
echo "[interop] server ready: $(tr '\n' ' ' <"$CFG")"

echo "[interop] running jsBrowserTest (headless Chrome via Karma)..."
./gradlew -PwtBrowserInterop :socket-webtransport:jsBrowserTest --rerun-tasks --console=plain
echo "[interop] jsBrowserTest PASSED"
