#!/usr/bin/env bash
# Pull the log to ./logs/<utc-stamp>-<tag>.log, and the per-connection replay traces beside it.
#
# The traces are the point of a walk now: the log says what happened, the traces let it be replayed
# through `TraceToFixture` without walking again. Pulled unconditionally — a walk whose traces are
# left on the device is a walk that has to be repeated.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TAG="${1:-walk}"; mkdir -p "$(dirname "$0")/logs"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$(dirname "$0")/logs/$stamp-$TAG.log"
adbs pull "$DEVICE_LOG" "$out" >/dev/null && echo "pulled $(wc -l < "$out") lines -> $out"

# `|| true` on the listing, not on the pull: an empty traces dir is a legitimate state (a run that
# never connected), but a pull that fails after we have seen files is a real failure and must not be
# swallowed. ⚠️ A composite `shell: bash` step runs under -e, so a no-match here would kill the script.
if adbs shell "ls $DEVICE_TRACES/*.trace 2>/dev/null" | grep -q .; then
  traces="$(dirname "$0")/logs/$stamp-$TAG-traces"
  mkdir -p "$traces"
  adbs pull "$DEVICE_TRACES/." "$traces" >/dev/null
  n=$(find "$traces" -name '*.trace' | wc -l | tr -d ' ')
  bytes=$(find "$traces" -name '*.trace' -exec cat {} + | wc -c | tr -d ' ')
  echo "pulled $n replay trace(s), $bytes bytes -> $traces"
else
  echo "no replay traces on the device — the walk is NOT replayable. Check that the probe build carries the trace sink." >&2
fi

# quiche's own frame-level record, when the run had it on (start.sh turns it on). One .sqlog per
# connection, decrypted by quiche itself — evidence that does not pass through this library's code.
QLOG="/sdcard/Android/data/$PKG/files/qlog"
if adbs shell "ls $QLOG/*.sqlog 2>/dev/null" | grep -q .; then
  qlog="$(dirname "$0")/logs/$stamp-$TAG-qlog"; mkdir -p "$qlog"
  adbs pull "$QLOG/." "$qlog" >/dev/null
  echo "pulled $(find "$qlog" -name '*.sqlog' | wc -l | tr -d ' ') qlog file(s), $(du -sk "$qlog" | cut -f1) KB -> $qlog"
else
  echo "no qlog on the device (the run was started without it)"
fi

# Runs a START moved aside instead of deleting (PREVIOUS-RUN in the log). Pulled, then removed
# from the device only once the pull returned success, so nothing is ever lost between the two.
PREV="/sdcard/Android/data/$PKG/files/previous"
if adbs shell "ls $PREV 2>/dev/null" | grep -q .; then
  prev="$(dirname "$0")/logs/$stamp-$TAG-previous"; mkdir -p "$prev"
  if adbs pull "$PREV/." "$prev" >/dev/null; then
    echo "pulled previous run(s): $(ls "$prev" | tr '\n' ' ') -> $prev"
    adbs shell "rm -rf $PREV"
  else
    echo "previous run(s) on the device could NOT be pulled — left in place: $PREV" >&2
  fi
fi
