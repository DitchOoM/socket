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
# The log is bounded: past 32 MB it moves to quic-handoff-probe.log.1 (older ones .2 .. .4). Oldest line first when read back as .4 .3 .2 .1 then the current file.
for n in 1 2 3 4; do
  xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source "Documents/quic-handoff-probe.log.$n" --destination "$out.$n" >/dev/null 2>&1 \
    && echo "pulled rotated log -> $out.$n"
done
# The core lanes' flight recordings (written when a core connection ends other than by our own close, and on app background / terminate / memory warning).
flight="$(dirname "$0")/logs/$stamp-$TAG-flight"
if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/flight --destination "$flight" >/dev/null 2>&1; then
  echo "pulled $(find "$flight" -name '*.trace' | wc -l | tr -d ' ') core flight recording(s) -> $flight"
else
  echo "no core flight recordings on the device (none were written: every core connection ended by our own clean close, or a build without core lanes)"
fi

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
# TLS key logs (conn-v6-0003.keys beside conn-v6-0003.trace): with them Wireshark decrypts the traces' datagrams
# with no code of ours in the loop. They decrypt the walk, so the probe keeps them in the app's private
# Library rather than the Files-app-visible Documents, and this copy is 0700.
keys="$(dirname "$0")/logs/$stamp-$TAG-keys"
if (umask 077 && xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Library/keys --destination "$keys" >/dev/null 2>&1); then
  echo "pulled $(find "$keys" -name '*.keys' | wc -l | tr -d ' ') key log(s) -> $keys (SECRET: decrypts the walk)"
else
  echo "no key logs on the device (a probe build without them)"
fi
keysprev="$(dirname "$0")/logs/$stamp-$TAG-keys-previous"
if (umask 077 && xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Library/previous --destination "$keysprev" >/dev/null 2>&1); then
  echo "pulled previous runs' key logs -> $keysprev (still on the device; devicectl cannot delete)"
fi
prev="$(dirname "$0")/logs/$stamp-$TAG-previous"
if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/previous --destination "$prev" >/dev/null 2>&1; then
  echo "pulled previous run(s): $(ls "$prev" | tr '\n' ' ') -> $prev (still on the device; devicectl cannot delete)"
fi
