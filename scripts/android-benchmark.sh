#!/usr/bin/env bash
#
# Runs :benchmark-android's Jetpack Microbenchmarks on the booted emulator three ways and prints them
# side by side:
#
#   R8          the release test APK minified by R8 (full mode) with AGP's default rules + the libraries'
#               consumer rules: what an app that ships with R8 runs.
#   R8-consumer the same, WITHOUT AGP's proguard-android-optimize.txt: only the libraries' consumer rules
#               keep quiche's JNI natives, so a pass proves socket-quic-quiche's consumer-rules.pro is
#               enough on its own.
#   no-R8       minification off, for the with/without comparison.
#
# Every QUIC benchmark first completes an unmeasured round trip and asserts the JNI backend loaded, so a
# JNI binding R8 broke fails the run (UnsatisfiedLinkError) rather than producing numbers.
#
# Emulator numbers are regression signals only (androidx.benchmark.suppressErrors=EMULATOR).
#
# Called from android_integration.yaml's emulator step as ONE line (the action runs each line in its own
# shell). Usage: android-benchmark.sh <api-level>
set -euo pipefail

API_LEVEL="${1:?usage: android-benchmark.sh <api-level>}"
OUT=bench-android
RESULTS=benchmarks/android-micro/build/outputs/connected_android_test_additional_output
mkdir -p "$OUT"

# The emulator is shut down here, bounded, whatever the outcome (scripts/emulator-shutdown.sh). Left to
# the action's own `adb emu kill`, a wedged emulator held this step until its timeout: run 42633798118
# finished its benchmarks at 14:35:43, got "OK: killing emulator, bye bye", and timed out at 14:52:16.
# shellcheck disable=SC2154 # status is assigned inside the trap
trap 'status=$?; bash scripts/emulator-shutdown.sh "$API_LEVEL" "$status"; exit "$status"' EXIT

run() { # <label> <gradle property args...>
  local label="$1"
  shift
  rm -rf "$RESULTS"
  ./gradlew :benchmark-android:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
    -PlinuxTargets=false --no-configuration-cache "$@"
  local json
  json="$(find "$RESULTS" -name '*benchmarkData.json' | head -1)"
  if [ -z "$json" ]; then
    echo "::error::$label: the benchmark run produced no benchmarkData.json under $RESULTS"
    exit 1
  fi
  cp "$json" "$OUT/$label.json"
}

run R8 -PbenchMinify=true
run R8-consumer -PbenchMinify=true -PbenchDefaultProguardFile=false
run no-R8 -PbenchMinify=false

summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
python3 -I .github/scripts/bench-android-summary.py "Android microbenchmarks (emulator, API $API_LEVEL; signals, not results)" \
  "R8=$OUT/R8.json" "R8-consumer=$OUT/R8-consumer.json" "no-R8=$OUT/no-R8.json" | tee -a "$summary"
