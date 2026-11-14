#!/usr/bin/env bash
#
# Runs :benchmark-android's release test APK (R8 and no-R8 in turn) over and over on a rooted emulator
# whose runtime checks memory, until a round fails or the time budget is spent:
#
#   CheckJNI         dalvik.vm.checkjni, for every app (a release APK otherwise runs without it)
#   heap verify      dalvik.vm.extra-opts -Xgc:preverify,postverify. On API 34's concurrent-copying
#                    collector a corrupted object header faults inside the collector at the next GC
#                    (art::gc::collector::ConcurrentCopying frames in the tombstone), not later in
#                    unrelated JIT code
#   GWP-ASan         libc.debug.gwp_asan.* for the test process: guarded native allocations (quiche,
#                    BoringSSL, the JNI shim), so a native overflow or use-after-free stops at the access
#
# The target is the SIGSEGV seen three times since 2026-11-12 on the benchmark lane (quic-deadline-t,
# DefaultDispatch; JIT frames reading a corrupted class pointer), which a debuggable instrumented run has
# never shown. On a failed round: the crash buffer, the full DropBox tombstones and the logcat.
#
# Called from nightly-deep-checks.yaml's emulator step as ONE line.
# Usage: android-heap-verify.sh <api-level> <minutes>
set -euo pipefail

API_LEVEL="${1:?usage: android-heap-verify.sh <api-level> <minutes>}"
MINUTES="${2:?usage: android-heap-verify.sh <api-level> <minutes>}"
OUT=heap-verify
PKG=com.ditchoom.socket.benchmark.android.test
mkdir -p "$OUT"

# shellcheck disable=SC2154 # status is assigned inside the trap
trap 'status=$?; bash scripts/emulator-shutdown.sh "$API_LEVEL" "$status"; exit "$status"' EXIT

system_server_pid() { adb shell pidof system_server 2>/dev/null | tr -d '\r' || true; }

wait_for_runtime() { # <old system_server pid>
  local old="$1"
  for _ in $(seq 1 120); do
    local now
    now="$(system_server_pid)"
    if [ -n "$now" ] && [ "$now" != "$old" ] && adb shell cmd package list packages >/dev/null 2>&1; then
      sleep 10
      return 0
    fi
    sleep 2
  done
  echo "::error title=Runtime did not come back::system_server did not restart within 240 s after the memory checks were set"
  adb logcat -d -b main,system,crash >"$OUT/logcat-restart.txt" 2>&1 || true
  return 1
}

arm() {
  adb root >/dev/null
  adb wait-for-device
  local old
  old="$(system_server_pid)"
  adb shell setprop dalvik.vm.checkjni true
  adb shell setprop dalvik.vm.extra-opts "-Xgc:preverify,postverify"
  adb shell setprop "libc.debug.gwp_asan.process_sampling.$PKG" 1
  adb shell setprop "libc.debug.gwp_asan.sample_rate.$PKG" 1
  adb shell setprop "libc.debug.gwp_asan.max_allocs.$PKG" 20000
  adb logcat -c || true
  adb shell stop
  adb shell start
  wait_for_runtime "$old"
  # Proof the checks are live, not just set: the runtime prints its CheckJNI state when it starts.
  adb logcat -d -b main,system >"$OUT/logcat-armed.txt" 2>&1 || true
  if ! grep -q 'CheckJNI is ON' "$OUT/logcat-armed.txt"; then
    echo "::error title=CheckJNI not on::the restarted runtime did not report CheckJNI is ON; the lane would check nothing"
    grep -E 'CheckJNI|AndroidRuntime' "$OUT/logcat-armed.txt" | head -20 || true
    return 1
  fi
  echo "armed: CheckJNI on; heap verify $(adb shell getprop dalvik.vm.extra-opts | tr -d '\r'); GWP-ASan sample_rate $(adb shell getprop "libc.debug.gwp_asan.sample_rate.$PKG" | tr -d '\r') for $PKG"
}

capture() { # <label>
  local label="$1"
  adb logcat -d -b crash >"$OUT/logcat-crash-$label.txt" 2>&1 || true
  adb logcat -d -b main,system >"$OUT/logcat-$label.txt" 2>&1 || true
  adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE >"$OUT/tombstone-$label.txt" 2>&1 || true
  adb shell dumpsys dropbox --print data_app_crash >"$OUT/app-crash-$label.txt" 2>&1 || true
  gzip -f "$OUT/logcat-$label.txt" || true
  echo "::error title=Memory check failed ($label)::logcat, crash buffer and full tombstones are in the android-heap-verify artifact"
  grep -h -E 'GWP-ASan|Heap corruption|[Vv]erification failed|JNI DETECTED ERROR|Fatal signal|Abort message|signal [0-9]+ \(SIG' \
    "$OUT/logcat-crash-$label.txt" "$OUT/tombstone-$label.txt" | head -40 || true
}

round() { # <label> <gradle property args...>
  local label="$1"
  shift
  adb logcat -c || true
  if ! ./gradlew :benchmark-android:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
    -PlinuxTargets=false --no-configuration-cache -q "$@"; then
    capture "$label"
    return 1
  fi
  # A check that reports without killing the process (GWP-ASan recoverable mode, a CheckJNI warning).
  if adb logcat -d -b crash,main | grep -q -E 'GWP-ASan|JNI DETECTED ERROR|Heap corruption'; then
    capture "$label"
    return 1
  fi
}

arm
deadline=$(($(date +%s) + MINUTES * 60))
n=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  n=$((n + 1))
  round "R8-$n" -PbenchMinify=true
  [ "$(date +%s)" -lt "$deadline" ] || break
  n=$((n + 1))
  round "no-R8-$n" -PbenchMinify=false
done
echo "rounds: $n, every one clean under CheckJNI, heap verification and GWP-ASan" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
