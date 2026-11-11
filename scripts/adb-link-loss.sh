# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh.

# Succeeds, printing the journal line, when a test engine session had an adb command end 255 (other
# than an install) and never ran an install or an `am instrument`: it lost its link while collecting
# device information, before any test APK reached the device. A session that installed or ran
# instrumentation had its link; a 255 in it (the engine's cleanup probes end 255 on API 24 while the
# device is healthy) is not a lost link. A session starts at the engine's `adb version`.
# Argument: the adb journal written by scripts/adb-install-guard.sh.
engine_lost_adb_link() {
  local lost
  lost=$(awk '
    function close_session() { if (line != "" && !reached) lost = line; line = ""; reached = 0 }
    $2 == "start" && / adb version$/ { close_session(); next }
    $2 == "end" && / rc=255 .* FAILED adb / && !/ install / { line = $0; next }
    $2 == "start" && (/ adb .* install / || / shell am instrument /) { reached = 1 }
    END { close_session(); if (lost != "") print lost }
  ' "$1" 2>/dev/null)
  [ -n "$lost" ] && echo "$lost"
}
