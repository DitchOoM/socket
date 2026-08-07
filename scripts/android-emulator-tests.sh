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

# There is no `adb reverse tcp:4433 tcp:4433` here: QUIC is UDP and adb reverse only handles TCP.
# The emulator reaches the host's docker-published 127.0.0.1:14433/udp via its built-in 10.0.2.2
# host alias — no port forwarding needed. AndroidQuicConnectivityTests + AndroidQuicMigrationTests
# point at 10.0.2.2:14433 (matches QuicHarnessConfig.quicEchoPort).
#
# Start the host-side NetworkControlServer (TCP :9998 + `adb reverse tcp:9998`) BEFORE the tests so
# AndroidQuicMigrationTests' netem / resilience suite actually RUNS instead of skipping on
# `NetworkControl.isAvailable() == false`. The server drives `adb shell su 0 iptables/tc/settings`
# on the rooted emulator (the `adb root` above) to toggle UDP / latency / airplane-mode while the
# device-side TCP control channel (10.0.2.2:9998) stays up. This is issue #72 Task 1 — the docker
# quic-echo (14433/udp) stays up for the connect + migration tests. Run as a separate Gradle
# invocation so its detached host JVM is alive before connectedAndroidTest starts.
./gradlew :socket-quic-quiche:startNetworkControlServer

# Keep going past a test failure so the logcat dump below still happens: the emulator is torn down
# the moment this script exits, so a later workflow step cannot reach it. The test outcome is
# preserved in TEST_EXIT and re-raised as this script's status.
set +e
./gradlew connectedAndroidTest :socket-quic-quiche:connectedAndroidTest
TEST_EXIT=$?
set -e

./gradlew :socket-quic-quiche:stopNetworkControlServer || true

# Stop the streamer and let it flush before anything reads the file.
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

if [ "$TEST_EXIT" != "0" ]; then
  # Inline in the job log too — a failure should be readable without downloading artifacts.
  echo "=== H3Loopback failure diagnostics (logcat, API ${API_LEVEL}) ==="
  grep -F "H3Loopback" "$LOGCAT_FILE" ||
    echo "(no H3Loopback report — the failing test was not AndroidHttp3LoopbackTest; see the uploaded logcat + test reports)"
  echo "=== end H3Loopback failure diagnostics ==="
fi

exit "$TEST_EXIT"
