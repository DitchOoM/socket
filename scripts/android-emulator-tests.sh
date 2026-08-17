#!/usr/bin/env bash
#
# Body of the `run tests` step in .github/workflows/android_integration.yaml.
#
# WHY THIS IS A FILE AND NOT AN INLINE `script:` BLOCK
# ---------------------------------------------------
# `reactivecircus/android-emulator-runner@v2` does NOT hand its `script:` input to one shell.
# It splits the input on newlines and runs each line separately as `/usr/bin/sh -c '<line>'`.
# Two consequences, both of which bit run 31057524722 (both API lanes, after the tests had
# already passed):
#
#   * a multi-line construct is a syntax error — `if [ "$TEST_EXIT" != "0" ]; then` on its own
#     line died with `sh: 1: Syntax error: end of file unexpected (expecting "fi")` and failed
#     the step with exit 2;
#   * shell state does not survive to the next line, so `LOGCAT_FILE=` / `LOGCAT_PID=$!` /
#     `set +e` / `TEST_EXIT=$?` were all no-ops against a fresh shell.
#
# Invoking this file is a single line, so the whole body runs in ONE shell: variables persist,
# the backgrounded logcat streamer is a real child that `kill` can reach, and control flow works.
#
# Usage: android-emulator-tests.sh <api-level>

set -euo pipefail

API_LEVEL="${1:?usage: android-emulator-tests.sh <api-level>}"

adb root || true
adb wait-for-device

# Logcat is the diagnostics transport on Android. An instrumented test runs in the app process,
# so its stdout goes to logcat and NEVER reaches the Gradle-side test XML — which is why the
# API-35 AndroidHttp3LoopbackTest failure in run 31027926910 left a bare
# `QuicCloseException: connection closed` and nothing else. AndroidHttp3LoopbackTest
# (emitDiagnostics) writes its failure report to logcat under the `H3Loopback` tag.
#
# STREAMED to a file, not dumped at the end: :socket-http3 runs early and three more modules'
# instrumented suites log for minutes after it, so a report sitting in the ring buffer can be
# evicted before any end-of-run `logcat -d`. A reader started here cannot lose it. The buffer is
# still grown + cleared as insurance for the streamer's own startup window.
adb logcat -G 64M || true
adb logcat -c || true
mkdir -p emulator-diagnostics
LOGCAT_FILE="emulator-diagnostics/logcat-api${API_LEVEL}.txt"
adb logcat -v threadtime > "$LOGCAT_FILE" &
LOGCAT_PID=$!

# There is no `adb reverse tcp:<quic port>` here: QUIC is UDP and adb reverse only handles TCP.
# This lane runs on an EMULATOR, which is the one device kind with a built-in `10.0.2.2` alias for
# the host's loopback, so it can address the docker-published quic-echo container directly. That
# pairing — the compose file's fixed published port plus the emulator alias — is the only case where
# a constant is the contract rather than a guess; both halves are absent on a physical device, where
# `:socket-quic-quiche:androidQuicIntegrationTest` computes a reachable host address, probes it, and
# carries it down instead. No argument is passed for the quic-echo endpoint below precisely so the
# device takes that documented docker fallback. See HarnessEndpoints.kt.
#
# Start the host-side NetworkControlServer BEFORE the tests so AndroidQuicMigrationTests' netem /
# resilience suite actually RUNS instead of recording a skip. The server binds an OS-ASSIGNED port
# (not the legacy 9998) and `adb reverse`s it — TCP, so the mapping genuinely applies — which puts it
# on the device's own loopback. The port is therefore unknowable in advance and must be carried to
# the device as an instrumentation argument; without that the suite resolved nothing and skipped, on
# the very lane that had just started the server. The server drives `adb shell su 0
# iptables/tc/settings` on the rooted emulator (the `adb root` above) to toggle UDP / latency /
# airplane-mode. This is issue #72 Task 1 — the docker quic-echo stays up for the connect + migration
# tests. Run as a separate Gradle invocation so its detached host JVM is alive before
# connectedAndroidTest starts.
./gradlew :socket-quic-quiche:startNetworkControlServer

NET_CTRL_PORT_FILE=socket-quic-quiche/build/network-control-server.port
NET_CTRL_PORT=$(cat "$NET_CTRL_PORT_FILE" 2>/dev/null || true)
if [ -z "$NET_CTRL_PORT" ]; then
  echo "::error::startNetworkControlServer recorded no port at $NET_CTRL_PORT_FILE" >&2
  exit 1
fi
echo "Network control server reachable from the device at 127.0.0.1:$NET_CTRL_PORT (adb reverse tcp)"

# An instrumented test process is forked from zygote and inherits the DEVICE's environment, so a
# SOCKET_REQUIRE_ALL_TESTS set on this runner never reaches it. Forward it as an instrumentation
# argument — the one channel that crosses — so the skip gate can actually fire on this lane if it is
# ever switched on. (Unset today: this lane's skips are still being inventoried, not gated.)
REQUIRE_ALL_ARG=""
if [ -n "${SOCKET_REQUIRE_ALL_TESTS:-}" ]; then
  REQUIRE_ALL_ARG="-Pandroid.testInstrumentationRunnerArguments.SOCKET_REQUIRE_ALL_TESTS=${SOCKET_REQUIRE_ALL_TESTS}"
fi

# Keep going past a test failure so the logcat dump below still happens: the emulator is torn down
# the moment this script exits, so a later workflow step cannot reach it. The test outcome is
# preserved in TEST_EXIT and re-raised as this script's status.
set +e
./gradlew connectedAndroidTest :socket-quic-quiche:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.deviceKind=emulator \
  -Pandroid.testInstrumentationRunnerArguments.netCtrlHost=127.0.0.1 \
  -Pandroid.testInstrumentationRunnerArguments.netCtrlPort="$NET_CTRL_PORT" \
  ${REQUIRE_ALL_ARG}
TEST_EXIT=$?
set -e

./gradlew :socket-quic-quiche:stopNetworkControlServer || true

# Stop the streamer and let it flush before anything reads the file.
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

# Prove the transport on EVERY run, including green ones. The report is only READ on failure and
# the artifact is only uploaded on failure, so a streamer that silently captured nothing would go
# unnoticed until the next occurrence of the ~1-in-120 flake this lane exists to diagnose — burning
# the occurrence, which is the exact outcome this whole change is meant to prevent. A green run that
# prints 0 lines here says the transport is broken, immediately and for free.
#
# Guarded with `[ -s ]` rather than `$(wc -l <"$file" 2>/dev/null)`: under this script's
# `set -euo pipefail` a missing file makes the redirect fail, which fails the assignment and exits
# the script — turning a run whose tests PASSED red. That is the same class of bug as the `if` that
# broke run 31057524722, so it is spelled out instead of being rediscovered.
LOGCAT_LINES=0
if [ -s "$LOGCAT_FILE" ]; then
  LOGCAT_LINES=$(wc -l <"$LOGCAT_FILE" | tr -d ' ')
fi
echo "logcat capture (API ${API_LEVEL}): ${LOGCAT_LINES} lines -> ${LOGCAT_FILE}"
if [ "$LOGCAT_LINES" = "0" ]; then
  echo "::warning::logcat capture is EMPTY — the Android H3Loopback failure-diagnostics transport is broken"
fi

if [ "$TEST_EXIT" != "0" ]; then
  # Inline in the job log too — a failure should be readable without downloading artifacts.
  echo "=== H3Loopback failure diagnostics (logcat, API ${API_LEVEL}) ==="
  grep -F "H3Loopback" "$LOGCAT_FILE" ||
    echo "(no H3Loopback report — the failing test was not AndroidHttp3LoopbackTest; see the uploaded logcat + test reports)"
  echo "=== end H3Loopback failure diagnostics ==="
fi

exit "$TEST_EXIT"
