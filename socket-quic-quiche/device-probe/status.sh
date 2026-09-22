#!/usr/bin/env bash
# One screen of where the run is: tail, counts (and per lane, when the probe ran lanes), last heartbeat, process memory.
set -uo pipefail
. "$(dirname "$0")/common.sh"
pid=$(adbs shell pidof "$PKG" | tr -d '\r ')
echo "pid=${pid:-DEAD}  log=$(adbs shell ls -la "$DEVICE_LOG" | awk '{print $5}') bytes"
[ -n "$pid" ] && adbs shell "cat /proc/$pid/status" | grep -E "VmRSS|VmSize|Threads" | tr -s ' ' | tr '\n' ' '; echo
count() { adbs shell "grep -c -e '$1' $DEVICE_LOG" | tr -d '\r'; }
echo "ok=$(count ECHO-OK) late=$(count ECHO-LATE) overdue=$(count ECHO-OVERDUE) unanswered=$(adbs shell "grep -o 'ECHO-UNANSWERED count=[0-9]*' $DEVICE_LOG" | awk -F= '{s+=$2} END{print s+0}') fail=$(count ECHO-FAIL) migrated=$(count 'PATH Migrated') failedPaths=$(count 'PATH Failed') dead=$(count CONNECTION-DEAD) broken=$(count STREAM-INTEGRITY-BROKEN) attempts=$(count CONNECT-ATTEMPT) heartbeats=$(count HEARTBEAT)"
F="/sdcard/Android/data/$PKG/files"
echo "capture: log=$(adbs shell "du -k $DEVICE_LOG" | cut -f1 | tr -d '\r')KB traces=$(adbs shell "du -sk $F/traces 2>/dev/null" | cut -f1 | tr -d '\r')KB qlog=$(adbs shell "du -sk $F/qlog 2>/dev/null" | cut -f1 | tr -d '\r')KB budget-spent=$(count TRACE-BUDGET-SPENT) previous=$(adbs shell "ls $F/previous 2>/dev/null" | wc -l | tr -d ' ')"
# Per lane, when the probe ran lanes: the LANES line near the top names them (`LANES v4=… v6=…`).
lanes=$(adbs shell "grep -m1 ' LANES ' $DEVICE_LOG" | tr -d '\r' | tr ' ' '\n' | grep '=' | grep -v -e '^t=' -e '^lane=' -e '^staggerMs=' | cut -d= -f1)
for l in $lanes; do
  lc() { adbs shell "grep -c -e 'lane=$l $1' $DEVICE_LOG" | tr -d '\r'; }
  echo "lane=$l ok=$(lc ECHO-OK) late=$(lc ECHO-LATE) overdue=$(lc ECHO-OVERDUE) unanswered=$(adbs shell "grep -o 'lane=$l ECHO-UNANSWERED count=[0-9]*' $DEVICE_LOG" | awk -F= '{s+=$3} END{print s+0}') fail=$(lc ECHO-FAIL) migrated=$(lc 'PATH Migrated') failedPaths=$(lc 'PATH Failed') dead=$(lc CONNECTION-DEAD) attempts=$(lc CONNECT-ATTEMPT) stalls=$(lc STALL-SUSPECTED)"
done
echo "== last heartbeat =="
if [ -n "$lanes" ]; then for l in $lanes; do adbs shell grep "'lane=$l HEARTBEAT'" "$DEVICE_LOG" | tail -1; done; else adbs shell grep HEARTBEAT "$DEVICE_LOG" | tail -1; fi
echo "== tail =="; adbs shell tail -4 "$DEVICE_LOG"
