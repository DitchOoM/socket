#!/usr/bin/env bash
# One screen of where the run is: tail, counts, last heartbeat, process memory.
set -uo pipefail
. "$(dirname "$0")/common.sh"
pid=$(adbs shell pidof "$PKG" | tr -d '\r ')
echo "pid=${pid:-DEAD}  log=$(adbs shell ls -la "$DEVICE_LOG" | awk '{print $5}') bytes"
[ -n "$pid" ] && adbs shell "cat /proc/$pid/status" | grep -E "VmRSS|VmSize|Threads" | tr -s ' ' | tr '\n' ' '; echo
count() { adbs shell "grep -c -e '$1' $DEVICE_LOG" | tr -d '\r'; }
echo "ok=$(count ECHO-OK) late=$(count ECHO-LATE) overdue=$(count ECHO-OVERDUE) unanswered=$(adbs shell "grep -o 'ECHO-UNANSWERED count=[0-9]*' $DEVICE_LOG" | awk -F= '{s+=$2} END{print s+0}') fail=$(count ECHO-FAIL) migrated=$(count 'PATH Migrated') failedPaths=$(count 'PATH Failed') dead=$(count CONNECTION-DEAD) broken=$(count STREAM-INTEGRITY-BROKEN) attempts=$(count CONNECT-ATTEMPT) heartbeats=$(count HEARTBEAT)"
F="/sdcard/Android/data/$PKG/files"
echo "capture: log=$(adbs shell "du -k $DEVICE_LOG" | cut -f1 | tr -d '\r')KB traces=$(adbs shell "du -sk $F/traces 2>/dev/null" | cut -f1 | tr -d '\r')KB qlog=$(adbs shell "du -sk $F/qlog 2>/dev/null" | cut -f1 | tr -d '\r')KB budget-spent=$(count TRACE-BUDGET-SPENT) previous=$(adbs shell "ls $F/previous 2>/dev/null" | wc -l | tr -d ' ')"
echo "== last heartbeat =="; adbs shell grep HEARTBEAT "$DEVICE_LOG" | tail -1
echo "== tail =="; adbs shell tail -4 "$DEVICE_LOG"
