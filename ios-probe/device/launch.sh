#!/usr/bin/env bash
# Launch the app: it resumes the walk on the phone, or begins a new one with WALK_AUTOSTART=1.
# Verifies the process is alive.
set -euo pipefail
. "$(dirname "$0")/common.sh"
# The target(s) are SERVER_HOST and SERVER_PORT from walk-server.env (see common.sh), or an optional
# 1st argument for this launch only. One host, or a COMMA-separated list, one lane per host, all
# running at once, so one phone exercises both address families on every network it crosses:
#   ./launch.sh "192.0.2.1,2001:db8::1"
# Comma, not colon — an IPv6 literal is made of colons. Quote it: it is ONE argument to -host, which
# the app reads from UserDefaults' argument domain and keeps for later launches from the icon.
[ $# -ge 1 ] && SERVER_HOST="$1"
walk_server_require SERVER_HOST SERVER_PORT
# --terminate-existing: launch arguments reach only a fresh process, so a running app would ignore
# -autostart/-pinned. Restarting one mid-walk loses nothing — the relaunch resumes the walk on the phone.
xcrun devicectl device process launch --device "$DEVICE" --terminate-existing "$BUNDLE" -- -host "$SERVER_HOST" -port "$SERVER_PORT" ${WALK_MINUTES:+-minutes "$WALK_MINUTES"} ${WALK_AUTOSTART:+-autostart "$WALK_AUTOSTART"} ${WALK_PINNED:+-pinned "$WALK_PINNED"} 2>&1 | tail -1
sleep 3
xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | grep -i quicprobe || echo "NOT RUNNING"
