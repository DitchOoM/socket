#!/usr/bin/env bash
#
# Tests for scripts/android-perfetto-ring.sh against a fake `adb` that models the device: a perfetto
# that reads its config on stdin, prints a pid, and writes its file only when told SIGTERM.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
lib="$here/../../../scripts/android-perfetto-ring.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
check() { if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; fail=$((fail + 1)); fi; }

mkdir -p "$tmp/bin" "$tmp/device"
# macOS has no `timeout`; the lib only needs the "run with a limit" behaviour.
command -v timeout >/dev/null 2>&1 || printf '#!/bin/sh\nshift\nexec "$@"\n' >"$tmp/bin/timeout"
chmod +x "$tmp/bin/timeout" 2>/dev/null
cat >"$tmp/bin/adb" <<'ADB'
#!/usr/bin/env bash
# fake adb: state in $FAKE_DEVICE
d="$FAKE_DEVICE"; echo "adb $*" >>"$d/calls"
if [ "$1" = pull ]; then [ -f "$d/trace" ] && cp "$d/trace" "$3" || { echo "remote object does not exist" >&2; exit 1; }; exit 0; fi
[ "$1" = shell ] || exit 0
cmd="$2"
case "$cmd" in
  perfetto*) cat >"$d/config"; [ -n "${FAKE_NO_PERFETTO:-}" ] && { echo "perfetto: not found" >&2; exit 127; }; echo 4242; touch "$d/running" ;;
  "kill -TERM 4242") rm -f "$d/running"; head -c 100000 /dev/urandom | base64 >"$d/trace" ;;
  "kill -KILL 4242"*) rm -f "$d/running" ;;
  "kill -0 4242") [ -f "$d/running" ] ;;
  stat*) [ -f "$d/trace" ] && wc -c <"$d/trace" || echo 0 ;;
  rm*) rm -f "$d/trace" ;;
esac
exit 0
ADB
chmod +x "$tmp/bin/adb"
export PATH="$tmp/bin:$PATH" FAKE_DEVICE="$tmp/device" ADB="$tmp/bin/adb"
# shellcheck source=../../../scripts/android-perfetto-ring.sh
source "$lib"

# not enabled: nothing starts
unset PERFETTO_RING; perfetto_ring_start 35 "$tmp/out0" >/dev/null
[ ! -f "$tmp/device/running" ] && [ "$PERFETTO_STATE" = off ]; check "a lane that is not enabled records nothing" $?

# API 24 has no Perfetto
export PERFETTO_RING=1
perfetto_ring_start 24 "$tmp/out24" >"$tmp/log" 2>&1
[ ! -f "$tmp/device/running" ] && grep -q 'no Perfetto' "$tmp/log"; check "API 24 keeps logcat only and says so" $?

# config contents
perfetto_ring_start 29 "$tmp/out29" >"$tmp/log" 2>&1
[ -f "$tmp/device/running" ] && [ "$PERFETTO_STATE" = running ]; check "API 29 starts the recorder" $?
grep -q 'sched/sched_switch' "$tmp/device/config" && grep -q 'sched/sched_wakeup' "$tmp/device/config" \
  && grep -q 'atrace_categories: "ss"' "$tmp/device/config" && grep -q 'atrace_categories: "am"' "$tmp/device/config" \
  && grep -q 'atrace_categories: "network"' "$tmp/device/config" && grep -q 'atrace_apps: "com.ditchoom.socket.quic.quiche.test"' "$tmp/device/config" \
  && grep -q RING_BUFFER "$tmp/device/config" && grep -q 'linux.process_stats' "$tmp/device/config"; check "the config is scheduling + process stats + ss/am/network atrace for the test APKs, as a ring" $?
! grep -q 'compact_sched\|COMPRESSION_TYPE_DEFLATE' "$tmp/device/config"; check "API 29 gets no field its perfetto would refuse" $?

# green: discard writes nothing
perfetto_ring_discard 29 >"$tmp/log" 2>&1
[ ! -f "$tmp/device/trace" ] && [ ! -d "$tmp/out29" -o -z "$(ls "$tmp/out29"/*.gz 2>/dev/null)" ] && grep -q 'KILL' "$tmp/device/calls"; check "a green run is stopped with SIGKILL and leaves no trace" $?
perfetto_ring_collect 29 "$tmp/out29" >/dev/null 2>&1
[ -z "$(ls "$tmp/out29"/*.gz 2>/dev/null)" ]; check "collect after a discard is a no-op" $?

# failure: SIGTERM, pull, compress, README
rm -f "$tmp/device/calls"
perfetto_ring_start 35 "$tmp/out35" >/dev/null 2>&1
grep -q 'compact_sched' "$tmp/device/config" && grep -q 'COMPRESSION_TYPE_DEFLATE' "$tmp/device/config"; check "API 35 asks for compact sched and in-trace deflate" $?
perfetto_ring_collect 35 "$tmp/out35" >"$tmp/log" 2>&1
grep -q 'kill -TERM 4242' "$tmp/device/calls" && ls "$tmp/out35"/perfetto-ring-api35.pftrace.gz >/dev/null 2>&1 && [ -s "$tmp/out35/perfetto-ring-README.txt" ]; check "a failed run is stopped gracefully, pulled and compressed" $?
grep -q 'bytes compressed' "$tmp/log"; check "the log carries the sizes" $?
perfetto_ring_collect 35 "$tmp/out35" >"$tmp/log" 2>&1
[ "$(ls "$tmp/out35"/*.gz | wc -l | tr -d ' ')" = 1 ]; check "collecting twice (hang path, then the end) keeps one trace" $?

# cap
perfetto_ring_start 35 "$tmp/outcap" >/dev/null 2>&1
PERFETTO_CAP_BYTES=10 perfetto_ring_collect 35 "$tmp/outcap" >"$tmp/log" 2>&1
grep -q 'TRUNCATED' "$tmp/log" && [ -z "$(ls "$tmp/outcap"/*.gz 2>/dev/null)" ]; check "over the cap the trace is dropped whole and the log says so" $?

# perfetto missing: warns, never fails
FAKE_NO_PERFETTO=1 perfetto_ring_start 29 "$tmp/outno" >"$tmp/log" 2>&1; rc=$?
[ "$rc" -eq 0 ] && grep -q 'did not start' "$tmp/log" && [ "$PERFETTO_STATE" = off ]; check "a recorder that will not start is a warning, not a failure" $?

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
