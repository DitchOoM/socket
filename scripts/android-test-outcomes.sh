# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh. What each module's instrumented-test run came to, and
# which adb commands ended abnormally.

# One line for a module (a directory with src/androidInstrumentedTest; "." is the root project), from
# its result XML and the Gradle output of the connected-test run:
#   PASSED <tests>             ran tests, none failed
#   FAILED <failed>/<tests>    ran tests and some failed or errored
#   NOT_STARTED                Gradle stopped before the module's connectedDebugAndroidTest task began
#   RAN_NONE <tests>           the task began (or Gradle succeeded) and no test was reported;
#                              <tests> is 0 or "no result file"
# Arguments: <module> <gradle-output-file> <gradle-exit-status>.
module_outcome() {
  local module="$1" gradle_out="$2" gradle_exit="$3"
  local xml header tests="" failed=0 task_path=""
  for xml in "$module"/build/outputs/androidTest-results/connected/debug/TEST-*.xml; do
    [ -e "$xml" ] || continue
    header=$(grep -m1 -o '<testsuites [^>]*>' "$xml" || true)
    tests=$(grep -o ' tests="[0-9]*"' <<<"$header" | grep -o '[0-9][0-9]*' || true)
    failed=$(($(grep -o ' failures="[0-9]*"' <<<"$header" | grep -o '[0-9][0-9]*' || echo 0) +
      $(grep -o ' errors="[0-9]*"' <<<"$header" | grep -o '[0-9][0-9]*' || echo 0)))
  done
  if [ -n "$tests" ] && [ "$tests" != "0" ]; then
    if [ "$failed" -gt 0 ]; then echo "FAILED $failed/$tests"; else echo "PASSED $tests"; fi
    return
  fi
  [ "$module" = "." ] || task_path=":${module//\//:}"
  if [ "$gradle_exit" != "0" ] && ! grep -qE "^> Task ${task_path}:connectedDebugAndroidTest( |$)" "$gradle_out" 2>/dev/null; then
    echo "NOT_STARTED"
  else
    echo "RAN_NONE ${tests:-no result file}"
  fi
}

# The journal's failed adb commands, less the two that end non-zero when nothing is wrong: the test
# engine's `test -d` probe for an additional-test-output directory that does not exist (exit 1), and a
# `logcat` capture the engine ends itself (exit 143, SIGTERM).
failed_adb_commands() {
  local journal="$1"
  grep -F ' FAILED ' "$journal" 2>/dev/null |
    grep -vE ' rc=1 .* shell test -d [^ ]*/additional_test_output( |$)| rc=143 .* adb -s [^ ]+ logcat ' || true
}
