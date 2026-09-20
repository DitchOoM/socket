#!/usr/bin/env bash
# Launch the app; the operator then taps "Start walk" (the start needs the Always-location grant,
# which only a tap in the app can request). Verifies the process is alive.
set -euo pipefail
. "$(dirname "$0")/common.sh"
# Optional 1st argument: the target(s) for this launch. One host (an IPv6 literal, another box), or a
# COMMA-separated rotation the probe walks one target per connection attempt, so one phone covers both
# address families on one route:
#   ./launch.sh "178.156.248.95,2a01:4ff:f4:eb1a::1"
# Comma, not colon — an IPv6 literal is made of colons. Quote it: it is ONE argument to -host, which
# the app reads from UserDefaults' argument domain. Omitted = the app's default.
HOST_ARGS=(); [ $# -ge 1 ] && HOST_ARGS=(-- -host "$1")
xcrun devicectl device process launch --device "$DEVICE" "$BUNDLE" "${HOST_ARGS[@]}" 2>&1 | tail -1
sleep 3
xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | grep -i quicprobe || echo "NOT RUNNING"
