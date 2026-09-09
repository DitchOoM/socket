#!/usr/bin/env bash
# Start the probe DETACHED from adb (nohup on the device side), so unplugging does not end it.
#   ./start.sh <minutes> [readTimeoutMs=400] [echoIntervalMs=250] [traceBudgetMb]
#
# traceBudgetMb is optional because the probe derives it from <minutes> and [echoIntervalMs]. Pass it
# only to override that. It is a positional passthrough rather than a default here so the two places
# cannot disagree again: this script's 250ms cadence is 8x the probe's own 2s default, and a budget
# constant sized for the latter died at hour 65 of the 75-hour run this script documents.
set -euo pipefail
. "$(dirname "$0")/common.sh"
MIN="${1:?minutes}"; RT="${2:-400}"; EI="${3:-250}"; BUDGET="${4:-}"
BUDGET_ARG=""; [ -n "$BUDGET" ] && BUDGET_ARG="-e probeTraceBudgetMb $BUDGET"
adbs shell "nohup am instrument -w -e class $PROBE_CLASS -e probeHost $SERVER_HOST -e probePort $SERVER_PORT -e probeMinutes $MIN -e probeReadTimeoutMs $RT -e probeEchoIntervalMs $EI $BUDGET_ARG $RUNNER > /dev/null 2>&1 &"
sleep 8
echo "== first lines =="; adbs shell head -6 "$DEVICE_LOG"
echo "== process =="; adbs shell pidof "$PKG" || echo "(no process yet)"
