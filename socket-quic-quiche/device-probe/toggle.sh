#!/usr/bin/env bash
# Force handoffs and outages from the desk. Each cycle: wifi off (cellular) -> wifi on -> airplane on
# (no network) -> airplane off. Sleeps let the connection settle/migrate between steps.
#   ./toggle.sh <cycles> [dwellSeconds=45]
set -uo pipefail
. "$(dirname "$0")/common.sh"
N="${1:-2}"; D="${2:-45}"
for i in $(seq 1 "$N"); do
  echo "[$i/$N] wifi OFF (handoff to cellular)"; adbs shell cmd -w wifi set-wifi-enabled disabled; sleep "$D"
  echo "[$i/$N] wifi ON  (handoff back)";        adbs shell cmd -w wifi set-wifi-enabled enabled;  sleep "$D"
  echo "[$i/$N] airplane ON (no network)";       adbs shell cmd connectivity airplane-mode enable; sleep "$D"
  echo "[$i/$N] airplane OFF";                   adbs shell cmd connectivity airplane-mode disable; sleep "$D"
done
adbs shell cmd -w wifi set-wifi-enabled enabled
