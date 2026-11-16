#!/usr/bin/env bash
# Put a RUNNING probe through the states a phone puts an app in, and mark every step in the probe's own log so
# analyze.py can say, per step, what each lane did (survived / died with its typed reason / recovered in Xs).
#
#   SERIAL=<emulator-or-attached-device> device-probe/lifecycle.sh [dwellSeconds=60] [steps...]
#
# Not for the walk and not for a device with a walk running on it: it kills the probe process on purpose.
# Local only. Start a probe first (start.sh <minutes> 1000), then run this; afterwards pull.sh + analyze.py.
#
# Steps, in this order by default:
#   doze           screen off + `dumpsys deviceidle force-idle` (deep Doze), battery unplugged
#   standby-rare   `am set-standby-bucket <pkg> rare`, then restricted, then back to active
#   trim-memory    `am send-trim-memory` RUNNING_CRITICAL, then COMPLETE
#   background-kill `am kill` (kills only a process that is in the background: the marker records whether it died)
#   force-stop     `am force-stop`, then `start.sh resume` (needs the server config start.sh needs)
#   airplane       airplane mode on, then off
#   wifi           Wi-Fi off (cellular, if the device has it), then on
#
# Every step writes two lines into the device log, under lane=run, stamped on the probe's own clock:
#   LIFECYCLE step=<n> name=<name> phase=begin|end probe=alive|dead [action=...]
# The probe's `t=` is milliseconds since its start; the start is read from the probe's walk-record file on the
# device, and the device's own clock stamps the step, so the host's clock never enters it. A copy goes to
# device-probe/logs/lifecycle-<stamp>.log on the host, with the host's UTC time.
set -uo pipefail
. "$(dirname "$0")/common.sh"
DWELL="${1:-60}"; [ $# -gt 0 ] && shift
STEPS=("$@"); [ ${#STEPS[@]} -eq 0 ] && STEPS=(doze standby-rare trim-memory background-kill force-stop airplane wifi)

mkdir -p "$(dirname "$0")/logs"
HOST_LOG="$(dirname "$0")/logs/lifecycle-$(date -u +%Y%m%dT%H%M%SZ).log"
WALK_RECORD="/sdcard/Android/data/$PKG/files/walk-record"

probe_pid() { adbs shell pidof "$PKG" 2>/dev/null | tr -d '\r ' ; }
# The device's wall clock in ms (toybox date has %N; the first 13 digits of epoch-ns are the ms).
device_ms() { adbs shell 'date +%s%N' | tr -d '\r' | cut -c1-13; }
# Milliseconds since the probe's start, on the probe's own clock; 0 when the record is not readable.
probe_t() {
  local started; started="$(adbs shell "cut -d' ' -f1 $WALK_RECORD" 2>/dev/null | tr -d '\r ')"
  [[ "$started" =~ ^[0-9]+$ ]] || { echo 0; return; }
  echo $(( $(device_ms) - started ))
}
STEP_N=0
mark() { # <name> <begin|end> [action]
  local name="$1" phase="$2" action="${3:-}" alive=alive t line
  [ -n "$(probe_pid)" ] || alive=dead
  t="$(probe_t)"
  line="t=${t}ms lane=run LIFECYCLE step=$STEP_N name=$name phase=$phase probe=$alive${action:+ action=$action}"
  adbs shell "echo '$line' >> $DEVICE_LOG" 2>/dev/null
  echo "$(date -u +%H:%M:%S) $line" | tee -a "$HOST_LOG"
}
act() { echo "    \$ $*" | tee -a "$HOST_LOG"; "$@" > /dev/null 2>&1; }

for name in "${STEPS[@]}"; do
  STEP_N=$((STEP_N + 1))
  mark "$name" begin
  case "$name" in
    doze)
      act adbs shell dumpsys battery unplug
      act adbs shell input keyevent KEYCODE_SLEEP
      act adbs shell dumpsys deviceidle force-idle
      sleep "$DWELL"
      act adbs shell dumpsys deviceidle unforce
      act adbs shell dumpsys battery reset
      act adbs shell input keyevent KEYCODE_WAKEUP ;;
    standby-rare)
      act adbs shell am set-standby-bucket "$PKG" rare;       sleep $((DWELL / 2))
      act adbs shell am set-standby-bucket "$PKG" restricted; sleep $((DWELL / 2))
      act adbs shell am set-standby-bucket "$PKG" active ;;
    trim-memory)
      act adbs shell am send-trim-memory --user 0 "$PKG" RUNNING_CRITICAL; sleep $((DWELL / 2))
      act adbs shell am send-trim-memory --user 0 "$PKG" COMPLETE;         sleep $((DWELL / 2)) ;;
    background-kill)
      act adbs shell am kill "$PKG"; sleep "$DWELL" ;;
    force-stop)
      act adbs shell am force-stop "$PKG"; sleep 5
      # The probe dies with its process; resume continues the same walk in place (same log, same clock).
      mark "$name" end "stopped"; STEP_N=$STEP_N
      "$(dirname "$0")/start.sh" resume 1000 2>&1 | tail -3 | tee -a "$HOST_LOG"
      sleep "$DWELL"; mark "$name" end "restarted"; continue ;;
    airplane)
      act adbs shell cmd connectivity airplane-mode enable;  sleep "$DWELL"
      act adbs shell cmd connectivity airplane-mode disable; sleep "$DWELL" ;;
    wifi)
      act adbs shell cmd -w wifi set-wifi-enabled disabled; sleep "$DWELL"
      act adbs shell cmd -w wifi set-wifi-enabled enabled;  sleep "$DWELL" ;;
    *) echo "unknown step '$name'" >&2 ;;
  esac
  mark "$name" end
done
echo "done; markers also in $HOST_LOG. Now: device-probe/pull.sh lifecycle && device-probe/analyze.py <the pulled log>"
