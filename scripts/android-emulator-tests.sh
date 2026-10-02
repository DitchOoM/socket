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

# NO TEST RUNS ON AN EMULATOR WITHOUT A NETWORK
# ---------------------------------------------
# Every suite here needs the device's network, and one of them asserts on it directly. An emulator
# can finish booting and never attach one: run 36919027096 booted cold, its netsim Wi-Fi and its
# modem never brought a network up in the 2.5 minutes before the tests, and the first suite failed
# `defaultReportsTheEmulatorsNetworkState` — an environment fault reported as a library defect.
# So the device must report an active default network first. If it does not within the budget, its
# radios are cycled once (`svc wifi`/`svc data`, which re-attach a network within seconds); if it
# still has none, no test runs and the step fails as what it is, with the device's own connectivity
# and Wi-Fi state captured beside the logcat.
NETWORK_BUDGET_S="${EMULATOR_NETWORK_BUDGET_S:-60}"
NETWORK_FILE="emulator-diagnostics/no-network-api${API_LEVEL}.txt"

# 0 once `dumpsys connectivity` names an active default network, 1 if none within $1 seconds.
await_default_network() {
  local deadline=$((SECONDS + $1))
  until adb shell dumpsys connectivity 2>/dev/null | grep -qE '^Active default network: [0-9]+'; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      return 1
    fi
    sleep 1
  done
}

NETWORK_READY=1
if ! await_default_network "$NETWORK_BUDGET_S"; then
  echo "::warning::emulator (API ${API_LEVEL}) had no active default network ${NETWORK_BUDGET_S}s after boot — cycling Wi-Fi and mobile data once"
  adb shell svc wifi disable || true
  adb shell svc data disable || true
  sleep 2
  adb shell svc wifi enable || true
  adb shell svc data enable || true
  if ! await_default_network "$NETWORK_BUDGET_S"; then
    NETWORK_READY=0
    {
      echo "Emulator (API ${API_LEVEL}) never attached a network: none ${NETWORK_BUDGET_S}s after boot, none ${NETWORK_BUDGET_S}s after cycling Wi-Fi and mobile data."
      echo "--- dumpsys connectivity ---"
      adb shell dumpsys connectivity 2>&1 | sed -n '1,120p' || true
      echo "--- dumpsys wifi (head) ---"
      adb shell dumpsys wifi 2>&1 | sed -n '1,80p' || true
    } >"$NETWORK_FILE"
    echo "::error title=Emulator has no network (API ${API_LEVEL})::No test ran: the emulator never attached a network, before or after cycling its radios. This is the emulator, not a test result. Capture: ${NETWORK_FILE}"
  fi
fi
echo "Emulator (API ${API_LEVEL}) default network: $(adb shell dumpsys connectivity 2>/dev/null | grep -E '^Active default network' || echo 'unknown')"

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
TEST_EXIT=1
if [ "$NETWORK_READY" = "1" ]; then
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
  # at the end of this script, so a later workflow step cannot reach it. The test outcome is
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
fi

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

# EMULATOR SHUTDOWN, BOUNDED
# --------------------------
# The emulator inherits this step's stdout, so the step cannot finish while any emulator process is
# alive; after this script the action only sends `adb emu kill` and waits. An emulator whose console
# acknowledges that kill but whose main loop never runs the shutdown (#665) then holds the step until
# its timeout. So the shutdown happens here: `emu kill`, a grace period, and if qemu is still alive,
# a capture of where it is stuck followed by SIGKILL of every emulator process.
#
# A wedge after the tests is a host-side emulator fault, not a test result: it leaves the exit status
# alone, and is reported as a ::warning:: carrying the capture, which is also written to
# $WEDGE_FILE (uploaded by the workflow whenever it exists).
QEMU_PATTERN='qemu-system'
# Every process the emulator starts: launcher, qemu, crashpad_handler, netsimd.
EMULATOR_FAMILY_PATTERN="qemu-system|crashpad_handler|netsimd|${ANDROID_HOME:-/nonexistent}/emulator/"
# ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL=1 on this step: a healthy emulator is gone ~1 s after the ack.
EMU_KILL_GRACE_S=15
SIGKILL_GRACE_S=10
WEDGE_FILE="emulator-diagnostics/emulator-shutdown-wedge-api${API_LEVEL}.txt"

gh_escape() {
  local s="$1"
  s="${s//'%'/%25}"
  s="${s//$'\r'/%0D}"
  s="${s//$'\n'/%0A}"
  printf '%s' "$s"
}

pids_matching() { pgrep -d ' ' -f "$1" || true; }

# Returns 0 once no qemu-system process remains, 1 if one is still alive after $1 seconds.
wait_qemu_gone() {
  local deadline=$((SECONDS + $1))
  while [ -n "$(pids_matching "$QEMU_PATTERN")" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      return 1
    fi
    sleep 0.5
  done
}

ps_family() {
  local pids
  pids=$(pids_matching "$EMULATOR_FAMILY_PATTERN")
  if [ -n "$pids" ]; then
    ps -o pid,ppid,stat,wchan:32,etime,cmd -p "${pids// /,}" || true
  else
    echo "(no emulator processes)"
  fi
}

# Where each qemu-system thread is: state, kernel wait channel, syscall; kernel stacks of the main
# thread and of any thread in D state; userspace backtraces when gdb is present.
capture_qemu() {
  local pid task tid comm state
  echo "--- emulator processes ---"
  ps_family
  for pid in $(pids_matching "$QEMU_PATTERN"); do
    echo
    echo "--- qemu pid $pid: /proc/$pid/status ---"
    grep -E '^(Name|State|Threads|SigPnd|ShdPnd|SigBlk|SigCgt)' "/proc/$pid/status" 2>/dev/null || echo "(unreadable)"
    echo "--- qemu pid $pid: threads (tid comm state wchan | syscall) ---"
    for task in /proc/"$pid"/task/*; do
      [ -d "$task" ] || continue
      tid=${task##*/}
      comm=$(cat "$task/comm" 2>/dev/null || echo '?')
      state=$(sed -E 's/^.*\) (.).*$/\1/' "$task/stat" 2>/dev/null || echo '?')
      printf '%s %s %s %s | %s\n' "$tid" "$comm" "$state" \
        "$(cat "$task/wchan" 2>/dev/null || echo '?')" \
        "$(sudo -n cat "$task/syscall" 2>/dev/null || echo '?')"
      if [ "$tid" = "$pid" ] || [ "$state" = "D" ]; then
        echo "  kernel stack of $tid:"
        sudo -n cat "$task/stack" 2>&1 | sed 's/^/    /' || true
      fi
    done
    if command -v gdb >/dev/null 2>&1; then
      echo "--- qemu pid $pid: userspace backtraces (gdb) ---"
      sudo -n timeout 60 gdb -p "$pid" -batch -ex 'thread apply all bt' 2>&1 || true
    else
      echo "--- qemu pid $pid: no userspace backtraces (gdb not installed) ---"
    fi
  done
}

echo "Emulator shutdown: qemu-system pids before 'adb emu kill': $(pids_matching "$QEMU_PATTERN")"
EMU_KILL_EXIT=0
timeout 15 adb emu kill || EMU_KILL_EXIT=$?
if [ "$EMU_KILL_EXIT" != "0" ]; then
  echo "'adb emu kill' exited $EMU_KILL_EXIT (124 = no answer within 15 s)"
fi

if wait_qemu_gone "$EMU_KILL_GRACE_S"; then
  echo "Emulator shutdown: qemu-system exited within ${EMU_KILL_GRACE_S}s of 'adb emu kill'"
  LEFTOVER=$(pids_matching "$EMULATOR_FAMILY_PATTERN")
  if [ -n "$LEFTOVER" ]; then
    echo "Emulator shutdown: other emulator processes still running (left to the action / job cleanup):"
    ps_family
  fi
else
  CAPTURE=$(capture_qemu 2>&1 || true)
  {
    echo "Emulator shutdown wedge (API ${API_LEVEL}): qemu-system still alive ${EMU_KILL_GRACE_S}s after 'adb emu kill' (exit ${EMU_KILL_EXIT})"
    echo "captured $(date -u +%FT%TZ), before SIGKILL"
    echo
    echo "$CAPTURE"
  } >"$WEDGE_FILE"
  echo "::group::Emulator shutdown wedge capture (API ${API_LEVEL}) -> ${WEDGE_FILE}"
  cat "$WEDGE_FILE"
  echo "::endgroup::"

  pkill -9 -f "$QEMU_PATTERN" || true
  if wait_qemu_gone "$SIGKILL_GRACE_S"; then
    KILL_RESULT="SIGKILLed qemu-system; it exited."
  else
    KILL_RESULT="qemu-system SURVIVED SIGKILL for ${SIGKILL_GRACE_S}s (uninterruptible sleep?) — this step will hang until its timeout."
    echo "::error title=Emulator unkillable (API ${API_LEVEL})::$(gh_escape "$KILL_RESULT
$(ps_family)")"
  fi
  # Any other emulator process also holds the step's stdout open.
  pkill -9 -f "$EMULATOR_FAMILY_PATTERN" || true
  echo "Emulator shutdown: $KILL_RESULT Remaining emulator processes:"
  ps_family
  # The annotation carries the head of the capture; the file has all of it.
  WARN_CAPTURE=$(printf '%s\n' "$CAPTURE" | sed -n '1,150p')
  echo "::warning title=Emulator shutdown wedge (API ${API_LEVEL}, tests exit ${TEST_EXIT})::$(gh_escape "Host-side emulator fault after the tests finished, not a test result. qemu-system ignored 'adb emu kill' for ${EMU_KILL_GRACE_S}s. ${KILL_RESULT} Full capture: ${WEDGE_FILE} (artifact emulator-shutdown-wedge-${API_LEVEL}).
$WARN_CAPTURE")"
fi

exit "$TEST_EXIT"
