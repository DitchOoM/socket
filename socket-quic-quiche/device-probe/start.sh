#!/usr/bin/env bash
# Start the probe DETACHED from adb (nohup on the device side), so unplugging does not end it.
#   ./start.sh <minutes> [readTimeoutMs=400] [echoIntervalMs=250]
set -euo pipefail
. "$(dirname "$0")/common.sh"
MIN="${1:?minutes}"; RT="${2:-400}"; EI="${3:-250}"
adbs shell "nohup am instrument -w -e class $PROBE_CLASS -e probeHost $SERVER_HOST -e probePort $SERVER_PORT -e probeMinutes $MIN -e probeReadTimeoutMs $RT -e probeEchoIntervalMs $EI $RUNNER > /dev/null 2>&1 &"
sleep 8
echo "== first lines =="; adbs shell head -6 "$DEVICE_LOG"
echo "== process =="; adbs shell pidof "$PKG" || echo "(no process yet)"
