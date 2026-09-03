#!/usr/bin/env bash
# One screen of where the run is: tail, counts, last heartbeat, process memory.
set -uo pipefail
. "$(dirname "$0")/common.sh"
pid=$(adbs shell pidof "$PKG" | tr -d '\r')
echo "pid=${pid:-DEAD}  log=$(adbs shell ls -la "$DEVICE_LOG" | awk '{print $5}') bytes"
[ -n "$pid" ] && adbs shell cat /proc/$pid/status | grep -E "VmRSS|VmSize|Threads" | tr -s ' ' | tr '\n' ' '; echo
echo "ok=$(adbs shell grep -c ECHO-OK "$DEVICE_LOG") fail=$(adbs shell grep -c ECHO-FAIL "$DEVICE_LOG") migrated=$(adbs shell grep -c 'PATH Migrated' "$DEVICE_LOG") dead=$(adbs shell grep -c CONNECTION-DEAD "$DEVICE_LOG") broken=$(adbs shell grep -c STREAM-INTEGRITY-BROKEN "$DEVICE_LOG") attempts=$(adbs shell grep -c CONNECT-ATTEMPT "$DEVICE_LOG")"
echo "== last heartbeat =="; adbs shell grep HEARTBEAT "$DEVICE_LOG" | tail -1
echo "== tail =="; adbs shell tail -4 "$DEVICE_LOG"
