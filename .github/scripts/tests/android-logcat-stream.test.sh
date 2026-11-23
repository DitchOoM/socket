#!/usr/bin/env bash
#
# Tests for scripts/android-logcat-stream.sh: a logcat reader that ends does not end the stream. The case is
# run 45222410247 (API 35): the reader ended a second after it started, when the transport dropped as the
# root adbd came up, and the stream held 104 lines for a run of 622 tests.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/android-logcat-stream.sh
source "$here/../../../scripts/android-logcat-stream.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }

tmp="$(mktemp -d)"
supervisor=""
trap '[ -n "$supervisor" ] && kill "$supervisor" 2>/dev/null; rm -rf "$tmp"' EXIT

L1='11-23 17:03:09.731   344   374 I netd    : setProcSysNet(6, 1, wlan0, accept_ra_min_lft, 0) <0.02ms>'
L2='11-23 17:03:09.890  1404  1600 V MediaProvider: onCreate() for external.db'
L3='11-23 17:03:09.890  1404  1600 V MediaProvider: onUpgrade() for external.db'
L4='11-23 17:03:12.004  3001  3020 I TestRunner: started: aThousandClientsThatStartTogether(com.ditchoom.socket.quic.core.demux.CoreServerStartupHerdTests)'
L5='11-23 17:03:15.120  3001  3020 I TestRunner: finished: aThousandClientsThatStartTogether(com.ditchoom.socket.quic.core.demux.CoreServerStartupHerdTests)'

# A fake device: the first reader prints two lines and ends as a dropped transport does. The second is
# asked to resume and prints from that timestamp on, the line already written included, then stays open.
calls="$tmp/calls"
: >"$calls"
adb() {
  echo "$*" >>"$calls"
  case "$*" in
    "logcat -v threadtime")
      printf '%s\n%s\n' "--------- beginning of main" "$L1"
      printf '%s\n' "$L2"
      echo "error: device offline" >&2
      return 1
      ;;
    "logcat -v threadtime -T 11-23 17:03:09.890")
      printf '%s\n%s\n%s\n%s\n' "$L2" "$L3" "$L4" "$L5"
      exec sleep 31.7
      ;;
    "wait-for-device") return 0 ;;
    *) return 1 ;;
  esac
}
timeout() { shift; "$@"; }

printf '%s\n%s\n' "$L1" "$L2" >"$tmp/stamp.txt"
if [ "$(logcat_last_timestamp "$tmp/stamp.txt")" = "11-23 17:03:09.890" ]; then ok "the last line's timestamp is the resume point"; else bad "wrong resume point: $(logcat_last_timestamp "$tmp/stamp.txt")"; fi
if [ -z "$(logcat_last_timestamp "$tmp/none.txt")" ]; then ok "a file not yet written has no resume point"; else bad "a missing file had a resume point"; fi

file="$tmp/logcat.txt"
record="$tmp/stream.txt"
: >"$file"
: >"$record"
logcat_stream_supervise "$file" "$record" &
supervisor=$!
for _ in $(seq 1 50); do
  grep -qF "$L5" "$file" && break
  sleep 0.1
done

if grep -qxF "$L5" "$file" && grep -qxF "$L4" "$file" && grep -qxF "$L3" "$file"; then
  ok "the reader was restarted and the lines after the end reached the file"
else
  bad "the stream did not resume" "$(cat "$file")" "calls: $(tr '\n' ';' <"$calls")"
fi
if [ "$(grep -cxF "$L2" "$file")" = "1" ]; then
  ok "a line already written at the resume timestamp is not written twice"
else
  bad "the resume duplicated or lost the line at its timestamp" "$(cat "$file")"
fi
if grep -qx 'logcat -v threadtime -T 11-23 17:03:09.890' "$calls"; then
  ok "the second reader resumed from the last line written"
else
  bad "the second reader did not resume from the last line" "$(cat "$calls")"
fi
if [ "$(logcat_stream_ends "$record")" = "1" ] && grep -q 'resuming after the start; last line written at 11-23 17:03:09.890' "$record" &&
  grep -qF 'device offline' "$record"; then
  ok "the end is recorded once, with the reader's own error"
else
  bad "the end was not recorded" "$(cat "$record")"
fi

kill "$supervisor"
wait "$supervisor" 2>/dev/null
supervisor_status=$?
sleep 0.2
if [ "$supervisor_status" = "0" ] && ! pgrep -f 'sleep 31[.]7' >/dev/null 2>&1; then
  ok "a stopped supervisor stops its reader"
else
  bad "the supervisor or its reader outlived the stop (status $supervisor_status)" "$(ps -eo pid,ppid,args | grep '[s]leep 31.7')"
fi
supervisor=""
if [ "$(logcat_stream_ends "$tmp/missing.txt")" = "0" ]; then ok "no record is no ends"; else bad "a missing record counted ends"; fi

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
