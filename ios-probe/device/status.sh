#!/usr/bin/env bash
# Is the app resident, and what does its log say? (pulls a fresh copy to a temp file)
set -uo pipefail
. "$(dirname "$0")/common.sh"
xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | grep -i quicprobe || echo "process: NOT RUNNING"
# Retried: the container copy intermittently fails while the phone is LOCKED, and a single attempt
# then reports "no log yet" — indistinguishable from a probe that never started. Measured 2026-09-06:
# two consecutive single-shot reads failed while three retries seconds apart all succeeded and showed
# the echo count still climbing. A monitor that cries "nothing there" at a healthy multi-day run is
# worse than no monitor, so try a few times before believing it.
tmp=$(mktemp); got=""
for _ in 1 2 3 4 5; do
  if xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/quic-handoff-probe.log --destination "$tmp" >/dev/null 2>&1; then got=1; break; fi
  sleep 2
done
[ -n "$got" ] || { echo "no log yet (5 copy attempts failed — if the phone is locked this may still be transient)"; exit 0; }
echo "lines=$(wc -l < "$tmp") ok=$(grep -c ECHO-OK "$tmp") late=$(grep -c ECHO-LATE "$tmp") overdue=$(grep -c ECHO-OVERDUE "$tmp") unanswered=$(grep -o 'ECHO-UNANSWERED count=[0-9]*' "$tmp" | awk -F= '{s+=$2} END{print s+0}') fail=$(grep -c ECHO-FAIL "$tmp") migrated=$(grep -c 'PATH Migrated' "$tmp") dead=$(grep -c CONNECTION-DEAD "$tmp") broken=$(grep -c STREAM-INTEGRITY-BROKEN "$tmp") attempts=$(grep -c CONNECT-ATTEMPT "$tmp")"
echo "== last heartbeat =="; grep -E "HEARTBEAT|KEEPALIVE-STATUS" "$tmp" | tail -1
echo "== tail =="; tail -3 "$tmp"; rm -f "$tmp"
