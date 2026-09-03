#!/usr/bin/env bash
# Is the app resident, and what does its log say? (pulls a fresh copy to a temp file)
set -uo pipefail
. "$(dirname "$0")/common.sh"
xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | grep -i quicprobe || echo "process: NOT RUNNING"
tmp=$(mktemp); xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/quic-handoff-probe.log --destination "$tmp" >/dev/null 2>&1 || { echo "no log yet"; exit 0; }
echo "lines=$(wc -l < "$tmp") ok=$(grep -c ECHO-OK "$tmp") fail=$(grep -c ECHO-FAIL "$tmp") migrated=$(grep -c 'PATH Migrated' "$tmp") dead=$(grep -c CONNECTION-DEAD "$tmp") broken=$(grep -c STREAM-INTEGRITY-BROKEN "$tmp") attempts=$(grep -c CONNECT-ATTEMPT "$tmp")"
echo "== last heartbeat =="; grep -E "HEARTBEAT|KEEPALIVE-STATUS" "$tmp" | tail -1
echo "== tail =="; tail -3 "$tmp"; rm -f "$tmp"
