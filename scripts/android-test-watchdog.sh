#!/usr/bin/env bash
# When a watched instrumented-test run counts as hung. Sourced by scripts/android-emulator-tests.sh;
# tested by .github/scripts/tests/android-test-watchdog.test.sh.
#
# A hang is a run that stopped making progress: no test has started or finished for the stall bound.
# The total bound is the time the step has left before its own timeout would end it with nothing
# captured, so it follows the step's deadline, not a sum of test durations: run 44484652562 (API 29) was
# declared hung at a fixed 660 s on a runner 1.3x slower than its neighbours, while tests kept finishing.

# The TestRunner events in the streamed logcat: each started or finished test is progress.
test_run_events() { # <logcat file>
  if [ -f "$1" ]; then grep -cE 'TestRunner: (started|finished): ' "$1" || true; else echo 0; fi
}

# The last TestRunner started/finished line in the logcat text on stdin; empty when there is none.
last_test_event() {
  grep -E 'TestRunner: (started|finished): ' | tail -1 || true
}

# A stall read from the streamed logcat is checked against the device's own logcat, read directly: the
# stream can end while the tests go on (run 45222410247, API 35: the reader ended a second after it started,
# and a run whose instrumentation was still running tests was stopped as hung after 301 s).
# Prints "progress" when the device logged a test event other than <baseline>, the last one already
# accounted for; otherwise why the stall stands.
stall_on_device() { # <baseline event line> <device read exit status> <device's last event line>
  if [ "$2" != "0" ]; then
    echo "the device's own logcat could not be read either (adb exit $2)"
  elif [ -n "$3" ] && [ "$3" != "$1" ]; then
    echo "progress"
  else
    echo "the device's own logcat has no later test event either"
  fi
}

# Reads the device's TestRunner events directly and prints "<stall_on_device verdict><TAB><device's last
# event>". The baseline is <accounted event>, or the streamed logcat's last event when that is empty.
device_stall_check() { # <streamed logcat file> <device event already accounted for, or empty>
  local events rc last
  events=$(timeout 30 adb logcat -d -v threadtime 'TestRunner:I' '*:S' 2>&1) && rc=0 || rc=$?
  last=$(last_test_event <<<"${events//$'\r'/}")
  printf '%s\t%s\n' "$(stall_on_device "${2:-$(last_test_event <"$1" 2>/dev/null)}" "$rc" "$last")" "$last"
}

# Why the run must be stopped, or nothing when it may go on. Reaching the total bound while tests
# still start and finish is running out of time, not a hang: run 44683082787 (API 29) reached it 1 s
# after a test started, and the report that named that test "hung" sent the hunt into a test that
# finished 3 s later along with the whole suite.
test_run_hang() { # <elapsed s> <s since the last test event> <stall bound s> <total bound s> <running test>
  if [ "$2" -ge "$3" ]; then
    echo "no test started or finished for ${2}s (stall bound ${3}s). Running test: ${5:-none}"
  elif [ "$1" -ge "$4" ]; then
    echo "out of time, not hung: still running after ${1}s (bound ${4}s: the step's deadline less the capture), and a test started or finished ${2}s ago. Running test: ${5:-none}"
  fi
}

# The annotation's word for a reason from test_run_hang or the install bound: "out of time" or "hung".
test_run_stop_kind() { # <reason>
  case "$1" in
    "out of time, not hung:"*) echo "out of time" ;;
    *) echo "hung" ;;
  esac
}

# Where the script's time went, so a run out of time says whether taking the shared jobs' inputs or
# the tests took it.
test_run_time_split() { # <s when the lane inputs were taken, or empty> <elapsed s>
  if [ -n "$1" ]; then
    echo "of ${2}s: 0-${1}s taking the lane inputs, ${1}-${2}s building and running the tests"
  else
    echo "of ${2}s: 0-${2}s taking the lane inputs, not yet taken"
  fi
}

# Seconds this script may run: until the step's deadline less the capture margin, or the default
# when no deadline was handed over (a local run).
test_run_budget() { # <deadline epoch s, or empty> <now epoch s> <capture margin s> <default s>
  if [ -n "$1" ]; then
    echo $(($1 - $2 - $3))
  else
    echo "$4"
  fi
}
