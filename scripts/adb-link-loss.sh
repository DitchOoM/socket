# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh.

# Succeeds, printing the journal line, when the test engine's adb link broke on a command that is not
# an install (exit 255) and the engine then ran no install and no instrumentation: it lost its link
# while collecting device information, before any test APK reached the device. A 255 that an install
# or an `am instrument` follows was survived and is not reported.
# Argument: the adb journal written by scripts/adb-install-guard.sh.
engine_lost_adb_link() {
  local lost
  lost=$(awk '
    $2 == "end" && / rc=255 .* FAILED adb / && !/ install / { line = $0; lost = 1; next }
    lost && $2 == "start" && (/ adb .* install / || / shell am instrument /) { lost = 0 }
    END { if (lost) print line }
  ' "$1" 2>/dev/null)
  [ -n "$lost" ] && echo "$lost"
}
