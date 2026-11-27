#!/usr/bin/env bash
#
# Sourced by scripts/android-emulator-tests.sh; tested by .github/scripts/tests/android-adb-root.test.sh.
#
# `adb root` returns while adbd restarts, and the transport can drop more than once as the restarted adbd
# comes up: right after boot, API 35 answered, dropped (`device 'emulator-5554' not found`) and came back
# within about two seconds. A command run in that window fails or, like a logcat reader, ends. So the
# script goes on only once adbd has answered as root ROOT_STEADY_ANSWERS times in a row, a second apart.

ROOT_STEADY_ANSWERS=3

# Restarts adbd as root and returns once it answers as root steadily. After $1 s without that it warns and
# returns anyway: a run that needs root says so where it does.
adb_root_settled() {
  local api="$1" budget="$2" deadline uid="" steady=0
  deadline=$((SECONDS + budget))
  adb root || true
  while [ "$SECONDS" -lt "$deadline" ]; do
    uid=$({ timeout 10 adb shell id -u 2>/dev/null || true; } | tr -d '\r')
    if [ "$uid" = "0" ]; then
      steady=$((steady + 1))
      if [ "$steady" -ge "$ROOT_STEADY_ANSWERS" ]; then
        echo "adbd (API ${api}) answers as root"
        return 0
      fi
    else
      steady=0
    fi
    sleep 1
  done
  echo "::warning title=adbd is not steadily root (API ${api})::$(gh_escape "adbd did not answer as root ${ROOT_STEADY_ANSWERS} times in a row within ${budget}s (last \`id -u\`: ${uid:-no answer}).")"
}
