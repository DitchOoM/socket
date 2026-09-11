#!/usr/bin/env bash
# Copy the log out of the app's Documents container to ./logs/<utc-stamp>-<tag>.log, and the
# per-connection replay traces beside it.
#
# The traces are the point of a walk now: the log says what happened, the traces let it be replayed
# through `TraceToFixture` without walking again. Pulled unconditionally — a walk whose traces are
# left on the device is a walk that has to be repeated.
#
# ⚠️ devicectl cannot copy a file the app is still writing. Terminate the probe process before
# pulling, exactly as for the log itself.
set -euo pipefail
. "$(dirname "$0")/common.sh"
TAG="${1:-walk}"; mkdir -p "$(dirname "$0")/logs"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$(dirname "$0")/logs/$stamp-$TAG.log"
xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/quic-handoff-probe.log --destination "$out" >/dev/null
echo "pulled $(wc -l < "$out") lines -> $out"

# The whole directory in one copy: devicectl has no globbing, and a per-file loop would need a
# listing it cannot give us. A run that never connected has no directory at all, which is a
# legitimate state and not a failure — but say so, because a silent absence reads like a success.
traces="$(dirname "$0")/logs/$stamp-$TAG-traces"
if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/traces --destination "$traces" >/dev/null 2>&1; then
  n=$(find "$traces" -name '*.trace' | wc -l | tr -d ' ')
  bytes=$(find "$traces" -name '*.trace' -exec cat {} + | wc -c | tr -d ' ')
  echo "pulled $n replay trace(s), $bytes bytes -> $traces"
else
  echo "no replay traces on the device — the walk is NOT replayable. Check that the probe build carries the trace sink, and that the probe process was terminated before pulling." >&2
fi

# quiche's own frame-level record (the probe turns qlog on), and any run a Start moved aside.
qlog="$(dirname "$0")/logs/$stamp-$TAG-qlog"
if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/qlog --destination "$qlog" >/dev/null 2>&1; then
  echo "pulled $(find "$qlog" -name '*.sqlog' | wc -l | tr -d ' ') qlog file(s), $(du -sk "$qlog" | cut -f1) KB -> $qlog"
else
  echo "no qlog on the device"
fi
prev="$(dirname "$0")/logs/$stamp-$TAG-previous"
if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/previous --destination "$prev" >/dev/null 2>&1; then
  echo "pulled previous run(s): $(ls "$prev" | tr '\n' ' ') -> $prev (still on the device; devicectl cannot delete)"
fi
