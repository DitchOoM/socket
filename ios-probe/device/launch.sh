#!/usr/bin/env bash
# Launch the app; the operator then taps "Start walk" (the start needs the Always-location grant,
# which only a tap in the app can request). Verifies the process is alive.
set -euo pipefail
. "$(dirname "$0")/common.sh"
xcrun devicectl device process launch --device "$DEVICE" "$BUNDLE" 2>&1 | tail -1
sleep 3
xcrun devicectl device info processes --device "$DEVICE" 2>/dev/null | grep -i quicprobe || echo "NOT RUNNING"
