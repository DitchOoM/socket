#!/usr/bin/env bash
#
# Tests for scripts/android-test-watchdog.sh: when a watched instrumented-test run counts as hung. The
# progressing case is run 44484652562 (API 29): 660 s in, the 64-connection echo test had been running
# 105 s and tests had finished up to the second before it.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
lib="$here/../../../scripts/android-test-watchdog.sh"
pass=0
fail=0
# shellcheck source=scripts/android-test-watchdog.sh
source "$lib"

check() { # <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "ok: $1"; pass=$((pass + 1)); else
    echo "FAIL: $1"; printf '    expected: %s\n    actual:   %s\n' "$2" "$3"; fail=$((fail + 1))
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

running="sixtyFourConnectionsOnTwoLoopsEachEchoAThousandTimesByteExact(com.ditchoom.socket.quic.core.loop.SharedClientLoopTests)"
cat >"$tmp/logcat.txt" <<EOF
11-19 08:21:31.574  4022  4041 I TestRunner: finished: aSocketThatFailsEndsItsConnectionAndTheOthersOnTheLoopKeepEchoing(com.ditchoom.socket.quic.core.loop.SharedClientLoopTests)
11-19 08:21:31.578  4022  4041 I TestRunner: started: aLoopThatFailsEndsItsConnectionsOnlyAndTheOtherLoopKeepsEchoingAndIsReplaced(com.ditchoom.socket.quic.core.loop.SharedClientLoopTests)
11-19 08:21:38.299  4022  4041 I TestRunner: finished: aLoopThatFailsEndsItsConnectionsOnlyAndTheOtherLoopKeepsEchoingAndIsReplaced(com.ditchoom.socket.quic.core.loop.SharedClientLoopTests)
11-19 08:21:38.303  4022  4041 I TestRunner: started: $running
11-19 08:21:39.644  4022  4033 I uic.quiche.tes: Background concurrent copying GC freed 33681(2305KB) AllocSpace objects
11-19 08:22:01.000  4022  4041 I TestRunner: run started: 580 tests
EOF
check "started and finished tests are the events, a run header and other lines are not" "4" "$(test_run_events "$tmp/logcat.txt")"
check "a logcat not yet written has no events" "0" "$(test_run_events "$tmp/missing.txt")"

budget=$(test_run_budget 1763540397 1763539223 150 660)
check "the budget is the step's deadline less the capture" "1024" "$budget"
check "without a deadline the budget is the default" "660" "$(test_run_budget "" 1763539223 150 660)"

check "660 s in, with the running test 105 s old, the run is not hung" "" "$(test_run_hang 660 105 300 "$budget" "$running")"
check "a test that has run for the stall bound is a hang" \
  "no test started or finished for 300s (stall bound 300s). Running test: $running" \
  "$(test_run_hang 400 300 300 "$budget" "$running")"
# Run 44683082787 (API 29): the bound fired 1 s after aClosedSocketEndsItsConnection... started, and the
# run finished all 593 tests 3 s later. The report must say the run was out of time, not that it hung.
closed="aClosedSocketEndsItsConnectionWithATypedFailureAndTheOthersKeepEchoing(com.ditchoom.socket.quic.core.loop.SharedClientLoopTests)"
check "a run at its total bound while tests finish is out of time, not hung" \
  "out of time, not hung: still running after 960s (bound 959s: the step's deadline less the capture), and a test started or finished 1s ago. Running test: $closed" \
  "$(test_run_hang 960 1 300 959 "$closed")"
check "a stall is titled a hang" "hung" "$(test_run_stop_kind "no test started or finished for 300s (stall bound 300s). Running test: $running")"
check "an install that never finished is titled a hang" "hung" "$(test_run_stop_kind "an APK install has not finished after 300s: x.apk")"
check "the total bound is titled out of time" "out of time" "$(test_run_stop_kind "$(test_run_hang 960 1 300 959 "$closed")")"
check "the time split says what took the script's time before and after the tests began" \
  "of 960s: 0-312s taking the lane inputs, 312-960s building and running the tests" \
  "$(test_run_time_split 312 960)"
check "a run stopped before the inputs arrived says so" \
  "of 400s: 0-400s taking the lane inputs, not yet taken" \
  "$(test_run_time_split "" 400)"

# A stream that died is not a stalled run (run 45222410247, API 35): the streamed logcat ended a second
# after it started, while the device's own logcat kept logging tests. A fake device answers the direct read.
herd="aThousandClientsThatStartTogether(com.ditchoom.socket.quic.core.demux.CoreServerStartupHerdTests)"
printf '%s\n' "11-23 17:03:09.890  1404  1600 V MediaProvider: onCreate() for external.db" >"$tmp/dead-stream.txt"
DEVICE_EVENTS=""
DEVICE_STATUS=0
adb() {
  case "$*" in
    "logcat -d -v threadtime TestRunner:I *:S")
      [ -n "$DEVICE_EVENTS" ] && printf '%s\r\n' "$DEVICE_EVENTS"
      [ "$DEVICE_STATUS" = "0" ] || echo "adb: device 'emulator-5554' not found" >&2
      return "$DEVICE_STATUS"
      ;;
  esac
  return 1
}
timeout() { shift; "$@"; }
device_event="11-23 17:08:40.114  4120  4141 I TestRunner: started: $herd"
DEVICE_EVENTS=$'11-23 17:08:39.950  4120  4141 I TestRunner: finished: x(Y)\n'"$device_event"
IFS=$'\t' read -r verdict last < <(device_stall_check "$tmp/dead-stream.txt" "")
check "a dead stream with tests still logged on the device is progress" "progress" "$verdict"
check "the device's last event is what the next check compares against" "$device_event" "$last"
IFS=$'\t' read -r verdict last < <(device_stall_check "$tmp/dead-stream.txt" "$device_event")
check "a device with no event since the last check is stalled too" \
  "the device's own logcat has no later test event either" "$verdict"
DEVICE_STATUS=1
IFS=$'\t' read -r verdict last < <(device_stall_check "$tmp/dead-stream.txt" "")
check "a device that cannot be read says so" "the device's own logcat could not be read either (adb exit 1)" "$verdict"
DEVICE_STATUS=0
cp "$tmp/logcat.txt" "$tmp/live-stream.txt"
DEVICE_EVENTS="$(last_test_event <"$tmp/live-stream.txt")"
IFS=$'\t' read -r verdict last < <(device_stall_check "$tmp/live-stream.txt" "")
check "a stream that holds the device's last event is a real stall" \
  "the device's own logcat has no later test event either" "$verdict"
check "the last event of a logcat is its last started or finished line" \
  "11-19 08:21:38.303  4022  4041 I TestRunner: started: $running" "$(last_test_event <"$tmp/logcat.txt")"

echo "android-test-watchdog: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
