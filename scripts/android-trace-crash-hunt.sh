#!/usr/bin/env bash
#
# Loops the benchmark APKs (R8, no-R8) on the booted emulator with androidx.benchmark's default
# method-tracing pass ON and no memory checker, until one run crashes or the time budget is spent.
#
# The benchmark process has died with SIGSEGV or SIGILL in JIT code within seconds of a traced pass
# ending, on the API 34 emulator only, and never with a memory checker attached. This lane keeps the
# conditions that showed it. What a hit proves depends on the leg:
#   API 34   the crash still exists. debuggerd's tombstone (every thread, registers, maps), the last
#            method trace and the logcat around the end of the traced pass say which threads were in
#            JIT code, in JNI or in the tracing teardown at that moment.
#   API 36   the control. A hit here means the crash is not confined to API 34's runtime, so the
#            library's own code is the suspect again.
#
# Called from nightly-deep-checks.yaml's emulator step as ONE line.
# Usage: android-trace-crash-hunt.sh <api-level> <minutes> <apk-dir>
set -uo pipefail

API_LEVEL="${1:?usage: android-trace-crash-hunt.sh <api-level> <minutes> <apk-dir>}"
MINUTES="${2:?usage: android-trace-crash-hunt.sh <api-level> <minutes> <apk-dir>}"
APKS="${3:?usage: android-trace-crash-hunt.sh <api-level> <minutes> <apk-dir>}"
OUT="trace-crash-hunt"
PKG=com.ditchoom.socket.benchmark.android.test
RUNNER=androidx.benchmark.junit4.AndroidBenchmarkRunner
MEDIA="/storage/emulated/0/Android/media/$PKG"
mkdir -p "$OUT"

# shellcheck disable=SC2154 # status is assigned inside the trap
trap 'status=$?; bash scripts/emulator-shutdown.sh "$API_LEVEL" "$status"; exit "$status"' EXIT

capture() { # <label>
  local label="$1" trace
  # The process is gone before its tombstone is written: wait for debuggerd to finish the backtrace
  # and for DropBox to file it, or the capture holds the first log line and nothing else.
  for _ in $(seq 1 30); do
    if adb logcat -d -b crash 2>/dev/null | grep -q 'backtrace:' &&
      ! adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE 2>/dev/null | grep -q 'No entries found'; then
      break
    fi
    sleep 1
  done
  adb logcat -d -b crash >"$OUT/logcat-crash-$label.txt" 2>&1 || true
  adb logcat -d -v threadtime -b main,system >"$OUT/logcat-$label.txt" 2>&1 || true
  adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE >"$OUT/tombstone-$label.txt" 2>&1 || true
  # DropBox prints the crashing thread and cuts the entry off inside the memory map. The files
  # debuggerd wrote hold every thread; they need root, taken only now that the run is over.
  adb root >/dev/null 2>&1 && adb wait-for-device
  adb pull /data/tombstones "$OUT/tombstones-$label" >/dev/null 2>&1 || true
  # The traced pass that had just ended (or was ending) when the process died.
  trace="$(adb shell "ls -t $MEDIA/*methodTracing*.trace 2>/dev/null | head -1" | tr -d '\r')"
  if [ -n "$trace" ]; then
    adb pull "$trace" "$OUT/last-method-trace-$label.trace" >/dev/null 2>&1 || true
    echo "$trace" >"$OUT/last-method-trace-$label.name"
  fi
  gzip -f "$OUT/logcat-$label.txt" "$OUT"/last-method-trace-"$label".trace 2>/dev/null || true
  gzip -rf "$OUT/tombstones-$label" 2>/dev/null || true
  echo "::error title=Crash under method tracing (API $API_LEVEL, $label)::tombstone, last method trace and logcat are in this job's trace-crash-hunt artifact"
  grep -h -m6 -E 'Fatal signal|^signal [0-9]+|#0[0-3] pc' "$OUT/logcat-crash-$label.txt" "$OUT/tombstone-$label.txt" | cut -c1-220 || true
}

deadline=$(($(date +%s) + MINUTES * 60))
runs=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  for cfg in R8 noR8; do
    [ "$(date +%s)" -lt "$deadline" ] || break
    runs=$((runs + 1))
    label="$cfg-$runs"
    adb uninstall "$PKG" >/dev/null 2>&1 || true
    if ! adb install -r -t "$APKS/$cfg.apk" >"$OUT/install.log" 2>&1; then
      echo "::error title=Install failed::$label: $(tail -1 "$OUT/install.log")"
      exit 1
    fi
    # As AGP's connected test does; without it the runner refuses to measure.
    adb shell cmd package compile -m speed -f "$PKG" >/dev/null 2>&1 || true
    adb shell "rm -rf $MEDIA" >/dev/null 2>&1 || true
    adb logcat -c || true
    timeout 600 adb shell am instrument -w -e androidx.benchmark.suppressErrors EMULATOR "$PKG/$RUNNER" >"$OUT/instrument.txt" 2>&1
    if adb logcat -d -b crash 2>/dev/null | grep -q -E 'Fatal signal [0-9]+'; then
      cp "$OUT/instrument.txt" "$OUT/instrument-$label.txt"
      capture "$label"
      exit 1
    fi
    if ! grep -qE '^OK \(' "$OUT/instrument.txt"; then
      cp "$OUT/instrument.txt" "$OUT/instrument-$label.txt"
      adb logcat -d -b main,system >"$OUT/logcat-$label.txt" 2>&1 || true
      echo "::error title=Benchmark run failed without a signal (API $API_LEVEL, $label)::$(grep -m1 -E 'Error in|shortMsg|FAILURES' "$OUT/instrument.txt" | cut -c1-200)"
      exit 1
    fi
  done
done
echo "API $API_LEVEL: $runs traced runs, no crash" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
