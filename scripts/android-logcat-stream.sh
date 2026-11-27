#!/usr/bin/env bash
#
# Sourced by scripts/android-emulator-tests.sh; tested by .github/scripts/tests/android-logcat-stream.test.sh.
#
# The streamed logcat is both the diagnostics transport and the watchdog's progress signal, so a reader that
# ends must not end the stream. `adb logcat` exits whenever the device's transport drops (an adbd restart, a
# reconnect); the supervisor starts it again from the timestamp of the last line written, dropping the lines
# at that timestamp it already has, and records each end.

THREADTIME_STAMP='^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}'

# The threadtime timestamp ("MM-DD hh:mm:ss.mmm") of the last line of file $1 that has one; empty if none.
logcat_last_timestamp() {
  [ -f "$1" ] || return 0
  tail -n 500 "$1" | grep -oE "$THREADTIME_STAMP" | tail -1 || true
}

# stdin to stdout, line-buffered, dropping each line listed in file $1 once.
logcat_drop_written() {
  awk -v written="$1" '
    BEGIN { while ((getline l < written) > 0) seen[l]++ }
    ($0 in seen) && seen[$0] > 0 { seen[$0]--; next }
    { print; fflush() }'
}

# Streams the device's logcat into file $1 until sent TERM, restarting the reader each time it ends. One line
# per end goes to file $2. Run it in the background: it turns errexit off in its own shell, since a reader
# that ends is what it handles.
logcat_stream_supervise() {
  set +e
  local file="$1" record="$2" ts written
  local -a resume
  written=$(mktemp)
  # The reader and its filter are this shell's children; both go with it.
  trap 'pkill -TERM -P "$BASHPID" 2>/dev/null; rm -f "$written"; exit 0' TERM INT
  while :; do
    ts=$(logcat_last_timestamp "$file")
    resume=()
    : >"$written"
    if [ -n "$ts" ]; then
      resume=(-T "$ts")
      tail -n 500 "$file" | awk -v ts="$ts" 'index($0, ts) == 1' >"$written"
    fi
    # The filter is last, so waiting on it waits until everything the reader printed is in the file.
    adb logcat -v threadtime "${resume[@]+"${resume[@]}"}" 2>>"$record" | logcat_drop_written "$written" >>"$file" &
    wait "$!"
    echo "$(date -u +%H:%M:%S) the logcat stream ended, resuming after ${ts:-the start}; last line written at $(logcat_last_timestamp "$file")" >>"$record"
    timeout 30 adb wait-for-device 2>>"$record" || true
    sleep 1
  done
}

# How many times the stream's reader ended, from the supervisor's record $1.
logcat_stream_ends() {
  if [ -f "$1" ]; then grep -c 'the logcat stream ended' "$1" || true; else echo 0; fi
}
