#!/usr/bin/env bash
# Start the probe DETACHED from adb (nohup on the device side), so unplugging does not end it.
#   ./start.sh <minutes> [echoIntervalMs=250] [traceBudgetMb]
#
# There is no read-deadline argument: the probe derives one per connection from the round trips it
# measures (#599), so a reply that outlives it is reported LATE, never as a failure.
#
# traceBudgetMb is optional because the probe derives it from <minutes> and [echoIntervalMs]. Pass it
# only to override that. It is a positional passthrough rather than a default here so the two places
# cannot disagree again: this script's 250ms cadence is 8x the probe's own 2s default, and a budget
# constant sized for the latter died at hour 65 of the 75-hour run this script documents.
set -euo pipefail
. "$(dirname "$0")/common.sh"
MIN="${1:?minutes}"; EI="${2:-250}"; BUDGET="${3:-}"
# qlog is always on: quiche's own frame-level record costs ~1.4 GB per 75 h at 250 ms (measured
# 2026-09-11: 652 KB per 2 min) and is the one record that does not pass through this library's code.
BUDGET_ARG=""; [ -n "$BUDGET" ] && BUDGET_ARG="-e probeTraceBudgetMb $BUDGET"
adbs shell "nohup am instrument -w -e class $PROBE_CLASS -e probeHost $SERVER_HOST -e probePort $SERVER_PORT -e probeMinutes $MIN -e probeEchoIntervalMs $EI -e probeQlog 1 $BUDGET_ARG $RUNNER > /dev/null 2>&1 &"
sleep 8
echo "== first lines =="; adbs shell head -6 "$DEVICE_LOG"
echo "== process =="; adbs shell pidof "$PKG" || echo "(no process yet)"
