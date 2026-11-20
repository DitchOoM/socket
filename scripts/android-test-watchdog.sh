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

# Where the script's time went, so a run out of time says whether the wait for the shared jobs' inputs
# or the tests took it.
test_run_time_split() { # <s when the lane inputs were taken, or empty> <elapsed s>
  if [ -n "$1" ]; then
    echo "of ${2}s: 0-${1}s waiting for and taking the lane inputs, ${1}-${2}s building and running the tests"
  else
    echo "of ${2}s: 0-${2}s waiting for and taking the lane inputs, not yet taken"
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
