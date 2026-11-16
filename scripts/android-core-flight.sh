#!/usr/bin/env bash
#
# Rebuilds the flight recordings a failed core test wrote on the device from the run's streamed logcat
# (sourced by android-emulator-tests.sh; nothing runs on source).
#
#   core_flight_extract <logcat-file> <out-dir>   one .trace file per connection under <out-dir>
#
# The instrumented test app is uninstalled when the run ends, taking any file it wrote, so a failed core test logs
# its recordings under the `core-flight` tag instead (FlightFiles.android.kt): `<path><TAB><trace line>` per line,
# the path being `core-flight/<TestClass>/<test>.<role>-<n>.trace`. The files are the same ones the JVM and
# Kotlin/Native lanes write, and read with the same converters. Never fails the job.

core_flight_extract() { # <logcat-file> <out-dir>
  local logcat="$1" out="$2"
  [ -s "$logcat" ] || return 0
  grep -a ' I core-flight: ' "$logcat" >/dev/null 2>&1 || return 0
  mkdir -p "$out"
  # threadtime: `MM-DD HH:MM:SS.mmm  pid  tid I core-flight: <path>\t<line>`
  awk -v out="$out" '
    {
      at = index($0, " I core-flight: ")
      if (at == 0) next
      rest = substr($0, at + length(" I core-flight: "))
      tab = index(rest, "\t")
      if (tab == 0) next
      path = substr(rest, 1, tab - 1)
      sub(/^core-flight\//, "", path)
      if (path ~ /\.\./) next
      dir = out "/" path
      sub(/\/[^\/]*$/, "", dir)
      if (!(dir in made)) { system("mkdir -p \"" dir "\""); made[dir] = 1 }
      print substr(rest, tab + 1) >> (out "/" path)
    }' "$logcat" || true
  echo "core flight recordings: $(find "$out" -name '*.trace' 2>/dev/null | wc -l | tr -d ' ') file(s) -> $out"
}
