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
  "of 960s: 0-312s waiting for and taking the lane inputs, 312-960s building and running the tests" \
  "$(test_run_time_split 312 960)"
check "a run stopped before the inputs arrived says so" \
  "of 400s: 0-400s waiting for and taking the lane inputs, not yet taken" \
  "$(test_run_time_split "" 400)"

echo "android-test-watchdog: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
