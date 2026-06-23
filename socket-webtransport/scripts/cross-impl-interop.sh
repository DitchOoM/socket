#!/usr/bin/env bash
# Cross-IMPLEMENTATION WebTransport interop runner (handoff "NEXT-PHASE PLAN", cell #1). Every
# WebTransportTestSuite subclass is SAME-platform loopback (server + client, one process, one impl); this
# drives a cross-PROCESS, cross-IMPL pair over localhost so the two native QUIC/H3 backends — quiche
# (JVM) and Network.framework (Apple K/N) — are exercised against each other, not just themselves.
#
# Two directions (arg $1):
#   nw-server     : NW server (macosArm64Test, K/N) ↔ quiche client (jvmTest, JVM)
#   quiche-server : quiche server (jvmTest, JVM)     ↔ NW client (macosArm64Test, K/N)
#
# Hard constraints (learned, see handoff): (a) server and client must be SEPARATE gradle processes —
# the server build blocks until stopped, so it can't share the daemon the client needs → both use
# --no-daemon. (b) JVM test workers don't inherit CLI -D; the module's afterEvaluate forwards `wt.*`
# props. (c) K/N test binaries have no system properties → config is passed via WT_INTEROP_* env vars
# (KGP's native test task inherits the parent env; --no-daemon avoids stale-daemon env). The server
# binds an ephemeral port and writes build/wt-interop/config.properties (its existence = readiness); the
# client reads it. A stop file ends the server; an EXIT trap always cleans up.
#
# Requires JAVA_HOME=<JDK21>. Cross-impl is STREAMS-ONLY (NW can't carry inbound streams + a datagram
# flow together; see issue #173), so the durable long-lived `cert` identity is used (no 13-day pinned).
set -euo pipefail

DIRECTION="${1:-}"
case "$DIRECTION" in
  nw-server|quiche-server) ;;
  *) echo "usage: $0 {nw-server|quiche-server}"; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 (e.g. the provisioned Temurin 21)}"

WORK="$ROOT/socket-webtransport/build/wt-interop"
CFG="$WORK/cross-impl-config.properties"
STOP="$WORK/cross-impl-stop"
SERVER_LOG="$WORK/cross-impl-server.log"
mkdir -p "$WORK"
rm -f "$CFG" "$STOP"

start_server() {
  if [ "$DIRECTION" = "nw-server" ]; then
    echo "[interop] starting NW server (macosArm64Test, --no-daemon)..."
    WT_INTEROP_SERVER=true \
    WT_INTEROP_CONFIG_FILE="$CFG" \
    WT_INTEROP_STOP_FILE="$STOP" \
    WT_INTEROP_CERT=cert \
    ./gradlew --no-daemon :socket-webtransport:macosArm64Test \
      --tests 'com.ditchoom.socket.webtransport.NwInteropServer' \
      --rerun-tasks --console=plain >"$SERVER_LOG" 2>&1 &
  else
    echo "[interop] starting quiche server (jvmTest, --no-daemon)..."
    ./gradlew --no-daemon :socket-webtransport:jvmTest \
      --tests 'com.ditchoom.socket.webtransport.BrowserInteropServer' \
      -Dwt.interop.server=true \
      -Dwt.interop.cert=cert \
      -Dwt.interop.configFile="$CFG" \
      -Dwt.interop.stopFile="$STOP" \
      --rerun-tasks --console=plain >"$SERVER_LOG" 2>&1 &
  fi
  SERVER_PID=$!
}

run_client() {
  if [ "$DIRECTION" = "nw-server" ]; then
    echo "[interop] running quiche client (jvmTest)..."
    ./gradlew :socket-webtransport:jvmTest \
      --tests 'com.ditchoom.socket.webtransport.QuicheInteropClient' \
      -Dwt.interop.client=true \
      -Dwt.interop.configFile="$CFG" \
      --rerun-tasks --console=plain
  else
    echo "[interop] running NW client (macosArm64Test)..."
    WT_INTEROP_CLIENT=true \
    WT_INTEROP_CONFIG_FILE="$CFG" \
    ./gradlew :socket-webtransport:macosArm64Test \
      --tests 'com.ditchoom.socket.webtransport.NwInteropClient' \
      --rerun-tasks --console=plain
  fi
}

start_server

cleanup() {
  echo "[interop] stopping server..."
  touch "$STOP" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  echo "[interop] server log tail:"; tail -20 "$SERVER_LOG" 2>/dev/null || true
}
trap cleanup EXIT

echo "[interop] waiting for server readiness ($CFG)..."
for _ in $(seq 1 180); do
  [ -f "$CFG" ] && break
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "[interop] server process exited early; log tail:"; tail -40 "$SERVER_LOG"; exit 1
  fi
  sleep 2
done
[ -f "$CFG" ] || { echo "[interop] server never wrote $CFG; log tail:"; tail -40 "$SERVER_LOG"; exit 1; }
echo "[interop] server ready: $(tr '\n' ' ' <"$CFG")"

run_client
echo "[interop] $DIRECTION cross-impl interop PASSED"
