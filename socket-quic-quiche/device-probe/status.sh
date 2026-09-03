#!/usr/bin/env bash
# One screen of where the run is: tail, counts, last heartbeat, process memory.
set -uo pipefail
. "$(dirname "$0")/common.sh"
pid=$(adbs shell pidof "$PKG" | tr -d '\r ')
echo "pid=${pid:-DEAD}  log=$(adbs shell ls -la "$DEVICE_LOG" | awk '{print $5}') bytes"
[ -n "$pid" ] && adbs shell "cat /proc/$pid/status" | grep -E "VmRSS|VmSize|Threads" | tr -s ' ' | tr '\n' ' '; echo
count() { adbs shell "grep -c -e '$1' $DEVICE_LOG" | tr -d '\r'; }
echo "ok=$(count ECHO-OK) fail=$(count ECHO-FAIL) migrated=$(count 'PATH Migrated') failedPaths=$(count 'PATH Failed') dead=$(count CONNECTION-DEAD) broken=$(count STREAM-INTEGRITY-BROKEN) attempts=$(count CONNECT-ATTEMPT) heartbeats=$(count HEARTBEAT)"
echo "== last heartbeat =="; adbs shell grep HEARTBEAT "$DEVICE_LOG" | tail -1
echo "== tail =="; adbs shell tail -4 "$DEVICE_LOG"
