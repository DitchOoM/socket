#!/usr/bin/env bash
# No-Gradle cross-IMPLEMENTATION WebTransport interop runner for CI (issue #173 limits this to streams).
#
# The sibling `cross-impl-interop.sh` drives the same NW-K/N ↔ quiche-JVM interop via `./gradlew` test
# tasks. This variant runs entirely off PRE-BUILT artifacts so a CI job can execute it in a couple of
# minutes with no Gradle daemon, no recompile, no Konan/quiche cache:
#   - NW side (Apple K/N): the self-contained `test.kexe` (macosArm64 appleTest binary). Config flows
#     through WT_INTEROP_* env vars (K/N has no JVM system properties); `--ktest_filter` selects the
#     single interop class. Verified self-contained — only macOS system frameworks.
#   - quiche side (JVM): the `webtransport-interop.jar` fat jar (QuicheInteropClient + BrowserInteropServer
#     + the macOS quiche dylibs under META-INF/native/). Run via JUnit4's runner so the @Test endpoints
#     stay unchanged. Config flows through `-Dwt.interop.*` system properties.
#
# Both directions are run sequentially over one held connection each (the Phase-4 DONE-bar shape: two
# WebTransport sessions, each a bidi round-trip + a uni). A shared ephemeral-port handshake is used: the
# server writes the config file (its existence = readiness) and serves until a stop file appears.
#
# Requires: JAVA_HOME=<JDK 21>; macOS arm64 host (the .kexe is native + uses real Network.framework).
# Env overrides: KEXE=<path to test.kexe>, JAR=<path to webtransport-interop.jar>.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21 (e.g. the provisioned Temurin 21)}"
JAVA="$JAVA_HOME/bin/java"

KEXE="${KEXE:-$ROOT/socket-webtransport/build/bin/macosArm64/debugTest/test.kexe}"
JAR="${JAR:-$ROOT/socket-webtransport/build/libs/webtransport-interop.jar}"
[ -x "$KEXE" ] || { echo "[interop] NW test binary not found/executable: $KEXE"; exit 3; }
[ -f "$JAR" ]  || { echo "[interop] interop jar not found: $JAR"; exit 3; }

WORK="$ROOT/socket-webtransport/build/wt-interop"
mkdir -p "$WORK"

JAVA_FLAGS=(--enable-native-access=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED)

# Launch the NW (Apple K/N) interop endpoint. $1 = server|client. Extra WT_INTEROP_* via env already set.
nw() {
  local role="$1"; shift
  local cls
  [ "$role" = server ] && cls=NwInteropServer || cls=NwInteropClient
  "$KEXE" --ktest_filter="*${cls}*" "$@"
}

# Launch the quiche (JVM) interop endpoint. $1 = server|client. Remaining args are -D props.
quiche() {
  local role="$1"; shift
  local cls
  [ "$role" = server ] && cls=BrowserInteropServer || cls=QuicheInteropClient
  "$JAVA" "${JAVA_FLAGS[@]}" "$@" -cp "$JAR" org.junit.runner.JUnitCore \
    "com.ditchoom.socket.webtransport.$cls"
}

# Run one direction. $1 = nw-server | quiche-server.
run_direction() {
  local dir="$1"
  local cfg="$WORK/$dir-config.properties"
  local stop="$WORK/$dir-stop"
  local slog="$WORK/$dir-server.log"
  rm -f "$cfg" "$stop"

  echo "::group::cross-impl $dir"
  echo "[interop] === direction: $dir ==="

  # Start the server (backgrounded; it blocks until $stop appears).
  if [ "$dir" = nw-server ]; then
    echo "[interop] starting NW server (test.kexe)..."
    WT_INTEROP_SERVER=true WT_INTEROP_CONFIG_FILE="$cfg" WT_INTEROP_STOP_FILE="$stop" WT_INTEROP_CERT=cert \
      nw server >"$slog" 2>&1 &
  else
    echo "[interop] starting quiche server (java -jar)..."
    quiche server \
      -Dwt.interop.server=true -Dwt.interop.cert=cert \
      -Dwt.interop.configFile="$cfg" -Dwt.interop.stopFile="$stop" >"$slog" 2>&1 &
  fi
  local server_pid=$!

  # Always stop the server + surface its log, however this direction ends.
  cleanup_dir() {
    touch "$stop" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    echo "[interop] $dir server log tail:"; tail -25 "$slog" 2>/dev/null || true
    echo "::endgroup::"
  }

  # Wait for server readiness (config file written), max ~3 min.
  echo "[interop] waiting for server readiness ($cfg)..."
  local ready=false
  for _ in $(seq 1 90); do
    if [ -f "$cfg" ]; then ready=true; break; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
      echo "[interop] $dir server exited before readiness; log tail:"; tail -40 "$slog" 2>/dev/null
      cleanup_dir; return 1
    fi
    sleep 2
  done
  if [ "$ready" != true ]; then
    echo "[interop] $dir server never wrote $cfg; log tail:"; tail -40 "$slog" 2>/dev/null
    cleanup_dir; return 1
  fi
  echo "[interop] $dir server ready: $(tr '\n' ' ' <"$cfg")"

  # Run the client (foreground; its exit code is the direction's verdict).
  local rc=0
  if [ "$dir" = nw-server ]; then
    echo "[interop] running quiche client (java -jar)..."
    quiche client -Dwt.interop.client=true -Dwt.interop.configFile="$cfg" || rc=$?
  else
    echo "[interop] running NW client (test.kexe)..."
    WT_INTEROP_CLIENT=true WT_INTEROP_CONFIG_FILE="$cfg" nw client || rc=$?
  fi

  cleanup_dir
  if [ "$rc" -eq 0 ]; then
    echo "[interop] $dir cross-impl interop PASSED"
  else
    echo "[interop] $dir cross-impl interop FAILED (client rc=$rc)"
  fi
  return "$rc"
}

# Run both directions; report a verdict per direction and overall.
OVERALL=0
NW_SERVER_RESULT=fail
QUICHE_SERVER_RESULT=fail

if run_direction nw-server; then NW_SERVER_RESULT=pass; else OVERALL=1; fi
if run_direction quiche-server; then QUICHE_SERVER_RESULT=pass; else OVERALL=1; fi

echo ""
echo "[interop] ===== cross-impl interop summary ====="
echo "[interop] nw-server     (NW K/N server  <- quiche JVM client): $NW_SERVER_RESULT"
echo "[interop] quiche-server (quiche JVM server <- NW K/N client):  $QUICHE_SERVER_RESULT"

# Machine-readable verdict for the CI summary step.
{
  echo "nw_server=$NW_SERVER_RESULT"
  echo "quiche_server=$QUICHE_SERVER_RESULT"
} >"$WORK/cross-impl-result.env"

[ "$OVERALL" -eq 0 ] && echo "[interop] ALL cross-impl directions PASSED" || echo "[interop] cross-impl interop had FAILURES"
exit "$OVERALL"
