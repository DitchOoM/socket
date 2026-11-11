#!/usr/bin/env bash
#
# Tests for scripts/android-test-outcomes.sh: how a module's instrumented-test run is classified, and
# which failed adb commands are left once the expected non-zero exits are removed. The fixtures are cut
# from an API 35 run in which :socket-http3 ran 34 tests with one failure, Gradle stopped there,
# :socket-webtransport had already started, and :socket-quic-quiche never began.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
lib="$here/../../../scripts/android-test-outcomes.sh"
pass=0
fail=0
# shellcheck source=scripts/android-test-outcomes.sh
source "$lib"

check() { # <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "ok: $1"; pass=$((pass + 1)); else
    echo "FAIL: $1"; printf '    expected: %s\n    actual:   %s\n' "$2" "$3"; fail=$((fail + 1))
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp" || exit 1

result() { # <module> <testsuites header attributes>
  local dir="$1/build/outputs/androidTest-results/connected/debug"
  mkdir -p "$dir"
  printf "<?xml version='1.0' encoding='UTF-8' ?>\n<testsuites %s>\n</testsuites>\n" "$2" >"$dir/TEST-test(AVD) - 15.xml"
}
result "." 'tests="8" failures="0" errors="0" skipped="2" time="0.000"'
result socket-http3 'tests="34" failures="1" errors="0" skipped="0" time="0.000"'
result socket-webtransport 'tests="5" failures="0" errors="0" skipped="0" time="0.000"'
result socket-erroring 'tests="3" failures="0" errors="2" skipped="0" time="0.000"'
result socket-empty 'tests="0" failures="0" errors="0" skipped="0" time="0.000"'

# The Gradle output's task lines, in the order the run printed them.
cat >gradle-stopped.txt <<'EOF'
> Task :connectedDebugAndroidTest
> Task :socket-http3:connectedDebugAndroidTest
> Task :socket-http3:connectedDebugAndroidTest FAILED
> Task :socket-webtransport:connectedDebugAndroidTest
> Task :socket-empty:connectedDebugAndroidTest
EOF

check "a module that ran tests and none failed passed" "PASSED 8" "$(module_outcome . gradle-stopped.txt 1)"
check "a module whose tests failed says how many of how many" "FAILED 1/34" "$(module_outcome socket-http3 gradle-stopped.txt 1)"
check "errors count as failures" "FAILED 2/3" "$(module_outcome socket-erroring gradle-stopped.txt 1)"
check "a module that started after the failure and passed passed" "PASSED 5" "$(module_outcome socket-webtransport gradle-stopped.txt 1)"
check "a module Gradle never reached was not started, not 'ran none'" "NOT_STARTED" "$(module_outcome socket-quic-quiche gradle-stopped.txt 1)"
check "a module that started and reported zero tests ran none" "RAN_NONE 0" "$(module_outcome socket-empty gradle-stopped.txt 1)"
check "with no Gradle failure, a module with no result ran none" "RAN_NONE no result file" "$(module_outcome socket-quic-quiche gradle-stopped.txt 0)"
check "a missing Gradle output file never hides a module with no result" "NOT_STARTED" "$(module_outcome socket-quic-quiche missing.txt 1)"

# Verbatim from the run's adb journal (the lines it flagged FAILED), plus one install failure in the
# install guard's own wording.
cat >journal.txt <<'EOF'
21:23:58.634 end   pid=14598 rc=1 68ms FAILED adb -s emulator-5554 shell test -d /sdcard/Android/media/com.ditchoom.socket/additional_test_output | stderr: 
21:23:59.774 end   pid=13839 rc=143 13060ms FAILED adb -s emulator-5554 logcat -v threadtime -b main -b crash -T 11-10 21:23:46.000 | stderr: 
21:26:15.030 end   pid=19573 rc=1 15ms FAILED adb -s emulator-5554 shell test -d /sdcard/Android/media/com.ditchoom.socket.http3/additional_test_output | stderr: 
21:26:15.200 end   pid=19104 rc=143 14604ms FAILED adb -s emulator-5554 logcat -v threadtime -b main -b crash -T 11-10 21:26:00.000 | stderr: 
21:26:16.100 end   pid=19999 (install guard) rc=1 FAILED adb -s emulator-5554 install -t -g /work/app.apk | output: Failure [INSTALL_FAILED_ALREADY_EXISTS]
21:26:17.000 end   pid=20001 rc=1 30ms FAILED adb -s emulator-5554 shell pm list packages | stderr: 
EOF
check "only abnormal adb commands remain" "2" "$(failed_adb_commands journal.txt | wc -l | tr -d ' ')"
check "the install failure is kept" "1" "$(failed_adb_commands journal.txt | grep -c INSTALL_FAILED_ALREADY_EXISTS)"
check "another probe that exits 1 is kept" "1" "$(failed_adb_commands journal.txt | grep -c 'pm list packages')"
check "a missing journal yields nothing" "0" "$(failed_adb_commands missing-journal.txt | wc -l | tr -d ' ')"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
