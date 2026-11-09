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

# A workflow-command message is one line; encode the rest (GitHub renders %0A as a newline).
gh_escape() {
  local s="$1"
  s="${s//'%'/%25}"
  s="${s//$'\r'/%0D}"
  s="${s//$'\n'/%0A}"
  printf '%s' "$s"
}

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

# WATCHDOG: A HANG IS RECORDED AND ENDED HERE, NOT BY THE STEP TIMEOUT
# ------------------------------------------------------------------------
# When the step's own timeout ends a hung run, the runner kills this script with it, and nothing
# says what was hung. The job log stops at a `> Task ...:connectedDebugAndroidTest` header, because
# AGP 9.5's test engine prints no per-test progress. That happened in runs 42324622569,
# 42382561575 and 42434499783 (API 24, each at the :socket-quic-quiche test APK's install).
#
# So the Gradle run is watched. It is declared hung when either:
#   * an APK install has run for INSTALL_STALL_S. A green install takes about 10 s here, dex2oat
#     included. The engine gives `adb install` no deadline: AGP 9.5.1 writes the install timeout
#     as `android-test.install-timeout-ms[<serial>]`, and the engine reads the key without the
#     serial, so `android.experimental.testOptions.installApkTimeout` has no effect. The ADB
#     INSTALL GUARD below handles a stalled install first (record, kill, one pushed retry, at most
#     ~3 min); this threshold is the backstop for when that did not end it.
#   * the script has run for TEST_BUDGET_S. Green runs of this whole step take 5.6-8.3 min,
#     emulator boot included.
# Then the device, host and test engine are captured to $HANG_FILE, a ::error:: annotation says
# what was hung, and Gradle is stopped. The script then shuts the emulator down as usual and fails.
INSTALL_STALL_S="${ANDROID_INSTALL_STALL_S:-300}"
TEST_BUDGET_S="${ANDROID_TEST_BUDGET_S:-660}"
HANG_FILE="emulator-diagnostics/test-run-hang-api${API_LEVEL}.txt"
GRADLE_OUT_FILE="emulator-diagnostics/gradle-connected-api${API_LEVEL}.txt"
TEST_RUN_HUNG=0

# "<seconds> <command line>" of each APK install adb is running, oldest first. Only processes that
# ARE adb (or the adb install guard script, run by bash) count: a shell whose own command line
# merely mentions `adb ... install` is not an install. `etime` rather than `etimes` (Linux-only)
# so the script also runs against a local emulator on macOS.
running_installs() {
  ps -eo etime=,args= | awk '
    ($2 ~ /(^|\/)adb(\.real)?$/ || ($2 ~ /(^|\/)bash$/ && $3 ~ /(^|\/)adb$/)) &&
    / install( |-multiple )/ {
      n = split($1, t, /[-:]/)
      secs = t[n] + 60 * t[n - 1]
      if (n >= 3) secs += 3600 * t[n - 2]
      if (n >= 4) secs += 86400 * t[n - 3]
      $1 = ""
      print secs $0
    }' | sort -rn || true
}

# The last test the instrumentation started and has not finished, from the streamed logcat.
running_test() {
  awk '
    /TestRunner: started: / { sub(/.*TestRunner: started: /, ""); current = $0 }
    /TestRunner: finished: / { sub(/.*TestRunner: finished: /, ""); if ($0 == current) current = "" }
    END { if (current != "") print current }
  ' "$LOGCAT_FILE" 2>/dev/null || true
}

# One section of the capture: a heading, then the command's output, bounded so a wedged adb cannot
# hang the capture itself.
capture_section() {
  local title="$1" limit="$2"
  shift 2
  echo
  echo "--- $title ---"
  timeout "$limit" "$@" 2>&1 || echo "(exit $? — 124 means no answer within ${limit}s)"
}

capture_hang() {
  local why="$1" serial console_port pkg pid
  serial=$(adb get-serialno 2>/dev/null || true)
  console_port=${serial#emulator-}
  # The package of the instrumentation adb is running, if any: `am instrument ... <pkg>/<runner>`.
  pkg=$(ps -eo args= | awk '/[a]m instrument/ { n = split($NF, a, "/"); print a[1]; exit }')
  {
    echo "Instrumented test run hung (API ${API_LEVEL}): $why"
    echo "captured $(date -u +%FT%TZ), ${SECONDS}s into the run-tests script"
    # Gradle starts these as soon as their APKs are built; each then waits for the device, so the
    # last one started is not necessarily the one on the device.
    echo "connected-test tasks Gradle has started, in order:" \
      $(grep -E '^> Task :[^ ]*connectedDebugAndroidTest$' "$GRADLE_OUT_FILE" 2>/dev/null | sed 's/^> Task //')
    echo "running test (from the streamed logcat): $(running_test)"
    echo "instrumentation package: ${pkg:-none running}"
    echo "last streamed logcat line: $(tail -1 "$LOGCAT_FILE" 2>/dev/null)"

    echo
    echo "--- host: adb, Java and emulator processes ---"
    ps -eo pid,ppid,etime,stat,args | awk 'NR == 1 || /adb|java|qemu-system|gradle/' | cut -c1-400

    # The emulator console is served by qemu itself, outside adb: a banner here with no answer from
    # `adb shell` below puts the fault in adb or the guest, not in a dead emulator.
    if [ -n "$console_port" ] && [ "$console_port" != "$serial" ]; then
      capture_section "emulator console banner (port $console_port)" 5 \
        bash -c "exec 3<>/dev/tcp/127.0.0.1/$console_port; head -c 200 <&3"
    fi
    capture_section "adb devices" 10 adb devices -l
    echo
    echo "--- device answers adb shell ---"
    if timeout 15 adb shell echo alive 2>&1; then
      capture_section "device processes" 15 adb shell ps
      # A streamed install that stopped leaves its session directory (vmdl*.tmp) with a partial APK.
      capture_section "install sessions in /data/app" 15 adb shell 'ls -la /data/app; ls -la /data/app/vmdl*.tmp 2>/dev/null'
      capture_section "device logcat, last 400 lines (read directly, not from the stream)" 20 \
        adb logcat -d -t 400 -v threadtime
      if [ -n "$pkg" ]; then
        # SIGQUIT makes ART write every thread's Java stack. API 24 writes it to
        # /data/anr/traces.txt, but only into an existing file the app may write ("Unable to open
        # stack trace file ... Permission denied" otherwise), hence the touch. Later releases write
        # a new file in /data/anr themselves.
        pid=$(timeout 10 adb shell pidof "$pkg" 2>/dev/null | tr -d '\r' || true)
        if [ -n "$pid" ]; then
          capture_section "kill -3 $pkg (pid $pid)" 10 adb shell \
            "rm -f /data/anr/traces.txt; touch /data/anr/traces.txt && chmod 666 /data/anr/traces.txt; kill -3 $pid"
          sleep 3
          capture_section "Java stacks of $pkg" 20 adb shell 'cat "$(ls -t /data/anr/* | head -1)"'
        fi
      fi
    else
      # Every later device query would wait out its own timeout the same way.
      echo "(no answer within 15s: the remaining device queries are skipped)"
    fi

    # Where the test engine is: installing, waiting on `am instrument`, or collecting results.
    local engine
    for engine in $(pgrep -f 'android-test[.]adb-path' || true); do
      capture_section "test engine JVM $engine: thread stacks" 30 "${JAVA_HOME:-/usr}/bin/jstack" "$engine"
    done
  } >"$HANG_FILE" 2>&1
}

# Watches Gradle (pid $1) until it exits or is declared hung. On a hang: capture, annotate, stop it.
watch_test_run() {
  local gradle_pid="$1" install_age install_cmd why test
  while kill -0 "$gradle_pid" 2>/dev/null; do
    why=""
    read -r install_age install_cmd < <(running_installs | head -1) || true
    if [ -n "${install_age:-}" ] && [ "$install_age" -ge "$INSTALL_STALL_S" ]; then
      why="an APK install has not finished after ${install_age}s: ${install_cmd##* }"
    elif [ "$SECONDS" -ge "$TEST_BUDGET_S" ]; then
      test=$(running_test)
      why="still running after ${SECONDS}s (budget ${TEST_BUDGET_S}s). Running test: ${test:-none}"
    fi
    if [ -n "$why" ]; then
      TEST_RUN_HUNG=1
      capture_hang "$why"
      echo "::group::Hung test run capture (API ${API_LEVEL}) -> ${HANG_FILE}"
      cat "$HANG_FILE"
      echo "::endgroup::"
      echo "::error title=Instrumented tests hung (API ${API_LEVEL})::$(gh_escape "$why
Full capture: ${HANG_FILE} (artifact android-test-reports-${API_LEVEL})")"
      # Cancels the build in the daemon. SIGKILL only if the client ignores that.
      kill "$gradle_pid" 2>/dev/null || true
      local deadline=$((SECONDS + 30))
      while kill -0 "$gradle_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
        sleep 1
      done
      kill -9 "$gradle_pid" 2>/dev/null || true
      return
    fi
    sleep 5
  done
}

# A MODULE THAT RAN NO TESTS: WHAT THE ENGINE, THE DEVICE AND THE RUNNER LOOKED LIKE
# -----------------------------------------------------------------------------------
# Run 42650305746 (API 24): :socket-http3 printed "Running tests on devices", then 0.8 s later
# "Could not find test-result.pb", and nothing else. The emulator was alive (it answered the
# shutdown below), no install or instrumentation reached it, and the log had no "AndroidTestRunner
# failed": the engine failed before the device ran anything, and AGP 9.5.1 discards that
# exception (neither of its JUnit Platform listeners logs a failed engine). The job's later
# "error: no emulator detected" came from the cleanup step, after this script had shut it down.
# So this capture records what can still be seen: every adb command the engine ran and how each
# ended (ADB JOURNAL, scripts/adb-install-guard.sh), whether the device still answers, and the
# runner's memory and kernel log (an OOM kill would show there).
NO_TESTS_FILE="emulator-diagnostics/no-tests-ran-api${API_LEVEL}.txt"
ADB_JOURNAL="emulator-diagnostics/adb-journal.txt"

failed_adb_commands() {
  grep -F ' FAILED ' "$ADB_JOURNAL" 2>/dev/null || echo "(none recorded in $ADB_JOURNAL)"
}

capture_no_tests_ran() {
  {
    echo "Modules with instrumented tests that ran none (API ${API_LEVEL}), captured $(date -u +%FT%TZ):"
    echo "$1"
    echo "--- Gradle lines about test results ---"
    grep -nE 'Running tests on devices|test-result.pb|AndroidTestRunner|connectedDebugAndroidTest|Failed to|FAILED' \
      "$GRADLE_OUT_FILE" 2>/dev/null | tail -60 || true
    echo
    echo "--- adb commands that failed ---"
    failed_adb_commands
    echo
    echo "--- adb journal, last 150 entries ---"
    tail -150 "$ADB_JOURNAL" 2>/dev/null || echo "(no journal at $ADB_JOURNAL)"
    capture_section "adb devices" 10 adb devices -l
    capture_section "device answers adb shell" 15 adb shell 'echo alive; getprop sys.boot_completed; getprop ro.build.version.sdk'
    capture_section "device storage" 15 adb shell df
    capture_section "device memory" 15 adb shell cat /proc/meminfo
    capture_section "device packages (test packages)" 15 adb shell pm list packages com.ditchoom
    capture_section "runner memory" 5 free -m
    capture_section "runner load" 5 cat /proc/loadavg
    capture_section "runner kernel log: OOM kills and segfaults" 10 \
      bash -c "sudo -n dmesg -T 2>&1 | grep -iE 'oom|killed process|out of memory|segfault|qemu' | tail -40"
    capture_section "runner processes by memory" 5 bash -c "ps -eo pid,rss,etime,args --sort=-rss | head -15 | cut -c1-300"
  } >"$NO_TESTS_FILE" 2>&1
}

# ADB INSTALL GUARD: A STALLED TEST-APK INSTALL IS RECORDED, THEN RETRIED PUSHED
# ------------------------------------------------------------------------------
# The streamed `adb install` AGP's test engine runs has stalled on the API-24 emulator four times
# since the AGP 9.5.1 bump (runs 42324622569, 42382561575, 42434499783, 42513600114), and the
# engine cannot be given a deadline or install options (WATCHDOG above). So on CI the SDK's `adb`,
# which is the binary the engine runs, is replaced by scripts/adb-install-guard.sh. It passes every
# other command to the real adb, recording each in $ADB_JOURNAL. An install with no result after ADB_GUARD_STALL_S is
# recorded to emulator-diagnostics/install-stall-<n>.txt, killed, and retried once with
# `--no-streaming -r`. A recovered stall is reported as a ::warning:: below and its capture uploaded
# even on a green run, so every occurrence adds evidence.
#
# Only on GitHub Actions: on a workstation this would rewrite the developer's own SDK.
ADB_GUARD_STALL_S="${ANDROID_ADB_GUARD_STALL_S:-60}"
ADB_GUARD_RETRY_S="${ANDROID_ADB_GUARD_RETRY_S:-120}"
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  SDK_ADB="${ANDROID_HOME:?ANDROID_HOME is not set}/platform-tools/adb"
  if [ ! -e "$SDK_ADB.real" ]; then
    mv "$SDK_ADB" "$SDK_ADB.real"
  fi
  sed -e "s#@REAL_ADB@#$SDK_ADB.real#" \
    -e "s#@DIAG_DIR@#$PWD/emulator-diagnostics#" \
    -e "s#@STALL_S@#$ADB_GUARD_STALL_S#" \
    -e "s#@RETRY_S@#$ADB_GUARD_RETRY_S#" \
    scripts/adb-install-guard.sh >"$SDK_ADB"
  chmod +x "$SDK_ADB"
  echo "adb install guard in place: $SDK_ADB -> $SDK_ADB.real (stall ${ADB_GUARD_STALL_S}s, retry ${ADB_GUARD_RETRY_S}s)"
fi

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
  #
  # The run is watched rather than waited on (see WATCHDOG above), so a hang is recorded and ended
  # by this script instead of by the step timeout. Gradle's output is also kept in a file, so the
  # capture can list the connected-test tasks it had started.
  set +e
  ./gradlew connectedAndroidTest :socket-quic-quiche:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.deviceKind=emulator \
    -Pandroid.testInstrumentationRunnerArguments.netCtrlHost=127.0.0.1 \
    -Pandroid.testInstrumentationRunnerArguments.netCtrlPort="$NET_CTRL_PORT" \
    ${REQUIRE_ALL_ARG} > >(tee "$GRADLE_OUT_FILE") 2>&1 &
  GRADLE_PID=$!
  watch_test_run "$GRADLE_PID"
  wait "$GRADLE_PID"
  TEST_EXIT=$?
  set -e

  # A MODULE THAT RAN NO TESTS IS A FAILURE, NOT A PASS
  # ----------------------------------------------------
  # AGP 9.5's test engine reports a module whose test APK did not install as a pass with zero tests.
  # Measured on a local API-24 emulator: `adb install` failed with INSTALL_FAILED_ALREADY_EXISTS, the
  # engine printed "AndroidTestRunner failed on emulator-5584" and a stack trace, wrote
  # `<testsuites tests="0">` and test-result-exit-code.txt = 0, and the build was SUCCESSFUL. Every
  # module with instrumented-test sources must therefore have a result file that counts at least
  # one test.
  if [ "$TEST_RUN_HUNG" = "0" ]; then
    RAN_NOTHING=""
    while IFS= read -r module; do
      tests=""
      for xml in "$module"/build/outputs/androidTest-results/connected/debug/TEST-*.xml; do
        [ -e "$xml" ] || continue
        tests=$(grep -o '<testsuites tests="[0-9]*"' "$xml" | head -1 | grep -o '[0-9][0-9]*' || true)
      done
      if [ -z "$tests" ] || [ "$tests" = "0" ]; then
        RAN_NOTHING="${RAN_NOTHING}${module} (tests=${tests:-no result file})"$'\n'
      fi
    done < <(git ls-files -- 'src/androidInstrumentedTest/*' '*/src/androidInstrumentedTest/*' |
      sed -E 's#src/androidInstrumentedTest/.*##; s#/$##; s#^$#.#' | sort -u)
    if [ -n "$RAN_NOTHING" ]; then
      TEST_EXIT=1
      capture_no_tests_ran "$RAN_NOTHING"
      echo "::group::Capture: modules that ran no tests (API ${API_LEVEL}) -> ${NO_TESTS_FILE}"
      cat "$NO_TESTS_FILE"
      echo "::endgroup::"
      echo "::error title=Instrumented tests did not run (API ${API_LEVEL})::$(gh_escape "These modules have instrumented tests but ran none:
${RAN_NOTHING}
If the log has 'AndroidTestRunner failed', the run on the device failed (an install, for example). If not, the test engine failed before the device ran anything and AGP discarded the exception. Failed adb commands (newest last):
$(failed_adb_commands | tail -20)
Full capture: ${NO_TESTS_FILE} (artifact android-test-reports-${API_LEVEL})")"
    fi
  fi

  if [ "$TEST_RUN_HUNG" = "1" ]; then
    TEST_EXIT=1
    # The Gradle daemon is still cancelling the hung build, so a second Gradle invocation would queue
    # behind it or start a cold daemon. Stop the control server the way stopNetworkControlServer does:
    # by the PID it recorded. Its `adb reverse` mapping dies with the emulator.
    NET_CTRL_PID=$(cat socket-quic-quiche/build/network-control-server.pid 2>/dev/null || true)
    if [ -n "$NET_CTRL_PID" ]; then
      kill "$NET_CTRL_PID" 2>/dev/null || true
    fi
  else
    ./gradlew :socket-quic-quiche:stopNetworkControlServer || true
  fi
fi

# Every install the guard recorded, recovered or not.
for stall in emulator-diagnostics/install-stall-*.txt; do
  [ -e "$stall" ] || continue
  echo "::warning title=Test APK install stalled (API ${API_LEVEL})::$(gh_escape "$(grep -m1 '^apk: ' "$stall")
$(grep -m1 '^wchar ' "$stall")
$(grep -m1 '^=> the device received\|^all .* blocks match' "$stall")
$(grep -m1 '^RESULT: ' "$stall")
Capture: $stall (artifact install-stall-${API_LEVEL})")"
done

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

# EMULATOR SHUTDOWN, BOUNDED: scripts/emulator-shutdown.sh (shared with android-benchmark.sh).
bash scripts/emulator-shutdown.sh "$API_LEVEL" "$TEST_EXIT"

exit "$TEST_EXIT"
