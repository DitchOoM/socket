#!/usr/bin/env bash
# Simulate a night in a hotel: force deep idle for <seconds>, then release. The probe must keep
# echoing (whitelisted) or at worst reconnect on release; a run that stops echoing and never
# reconnects is the failure this dry run exists to catch before the real night.
set -uo pipefail
. "$(dirname "$0")/common.sh"
S="${1:-180}"
adbs shell dumpsys battery unplug
adbs shell dumpsys deviceidle force-idle && echo "forced deep idle for ${S}s"; sleep "$S"
adbs shell dumpsys deviceidle unforce; adbs shell dumpsys battery reset; echo "released"
