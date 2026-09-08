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
