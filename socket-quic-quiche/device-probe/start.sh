#!/usr/bin/env bash
# Start the probe DETACHED from adb (nohup on the device side), so unplugging does not end it.
#   ./start.sh <minutes> [echoIntervalMs=250] [traceBudgetMb]
#
# The targets come from SERVER_HOST in common.sh: one host, or a COMMA-separated list, one lane per
# host, all running at once, so one phone exercises both address families on every network it crosses.
#   SERVER_HOST="178.156.248.95,2a01:4ff:f4:eb1a::1" ./start.sh 4500 250
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
# qlog is always on: quiche's own frame-level record, ~1.8 GB per 75 h at 250 ms and the one record
# that does not pass through this library's code. The probe derives its budget (QLOG-BUDGET) itself.
# Key logs are always on too: the TLS secrets that decrypt each lane's traces without this library.
# They stay in the app's private files dir and only pull.sh (run-as) takes them off the phone.
BUDGET_ARG=""; [ -n "$BUDGET" ] && BUDGET_ARG="-e probeTraceBudgetMb $BUDGET"
# STANDBY_LINK=OnDemand ./start.sh ... runs without the held cellular standby link, for an A/B against
# the default (KeepCellularReady). The START line names the one in effect.
STANDBY_ARG=""; [ -n "${STANDBY_LINK:-}" ] && STANDBY_ARG="-e probeStandbyLink $STANDBY_LINK"
# probeHost is one host or a COMMA-separated list of lanes (see common.sh). Quoted twice on purpose: once
# for this shell and once for the device's, because a list is one argument and an IPv6 literal must
# reach the probe with its colons intact.
adbs shell "nohup am instrument -w -e class $PROBE_CLASS -e probeHost '$SERVER_HOST' -e probePort $SERVER_PORT -e probeMinutes $MIN -e probeEchoIntervalMs $EI -e probeQlog 1 -e probeKeyLog 1 $BUDGET_ARG $STANDBY_ARG $RUNNER > /dev/null 2>&1 &"
sleep 8
echo "== first lines =="; adbs shell head -6 "$DEVICE_LOG"
echo "== process =="; adbs shell pidof "$PKG" || echo "(no process yet)"
