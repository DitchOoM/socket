#!/usr/bin/env bash
#
# Tests for scripts/android-zygote-heap.sh: a zygote that started without dalvik.vm.heapsize is found from its
# heap reservation and the framework is restarted before any test runs.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/android-zygote-heap.sh
source "$here/../../../scripts/android-zygote-heap.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }
gh_escape() { printf '%s' "$1"; }
sleep() { :; }

# The concurrent-copying collector reserves twice -Xmx, in several mappings, as an API 29 zygote64 shows.
MAPS_512M=$'12c00000-14f40000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\r
14f40000-16140000 ---p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\r
16140000-52c00000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\r
72cba000-72cbb000 rw-p 00000000 00:00 0                                  [anon:dalvik-non moving space]\r
7877b6f000-7878b6f000 rw-p 00000000 00:00 0                              [anon:dalvik-region space live bitmap]'
MAPS_16M=$'12c00000-13c00000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\r
13c00000-14c00000 ---p 00000000 00:00 0                                  [anon:dalvik-main space (region space)]\r
72cba000-72cbb000 rw-p 00000000 00:00 0                                  [anon:dalvik-non moving space]'

# A fake device: the zygote's maps are $ZYGOTE_MAPS until a `start`, then $ZYGOTE_MAPS_AFTER_RESTART.
# DEVICE=gone fails every command after getprop the way a dropped transport does; DEVICE=no-zygote has no
# zygote process; DEVICE=not-root cannot read another process's maps.
ADB_LOG=""
DEVICE=up
adb() {
  ADB_LOG="${ADB_LOG}$*"$'\n'
  case "$DEVICE:$*" in
    *":shell getprop dalvik.vm.heapsize") printf '%s\r\n' "$HEAPSIZE" ;;
    "gone:"*) echo "adb: device 'emulator-5554' not found" >&2; return 1 ;;
    "no-zygote:shell pidof zygote64 || pidof zygote") return 1 ;;
    *":shell pidof zygote64 || pidof zygote") printf '178\r\n' ;;
    "not-root:shell cat /proc/178/maps") echo "cat: /proc/178/maps: Permission denied" >&2; return 1 ;;
    *":shell cat /proc/178/maps") printf '%s\n' "$ZYGOTE_MAPS" ;;
    *":shell start") ZYGOTE_MAPS="$ZYGOTE_MAPS_AFTER_RESTART" ;;
    *":shell getprop sys.boot_completed") printf '1\r\n' ;;
  esac
}

for pair in "512m 536870912" "196608k 201326592" "1g 1073741824" "4096 4096" "garbage " " "; do
  read -r v want <<<"$pair"
  got=$(heap_property_bytes "${v:-}")
  if [ "$got" = "${want:-}" ]; then ok "heapsize '${v:-}' is '${want:-}' bytes"; else bad "heapsize '${v:-}' read as '$got'"; fi
done

got=$(main_space_span_bytes <<<"$MAPS_512M")
if [ "$got" = "$((1024 * 1024 * 1024))" ]; then ok "a 512m zygote reserves 1 GB of main space"; else bad "512m maps read as $got"; fi
got=$(main_space_span_bytes <<<"")
if [ "$got" = "0" ]; then ok "no main space reads as 0"; else bad "empty maps read as $got"; fi

# The zygote has the 16 MB fallback while getprop says 512m: the framework restarts and the heap is right after it.
HEAPSIZE=512m ZYGOTE_MAPS="$MAPS_16M" ZYGOTE_MAPS_AFTER_RESTART="$MAPS_512M" ADB_LOG=""
out=$(ensure_zygote_heap 29; echo "status=$?"; printf '%s' "$ADB_LOG")
if grep -q 'status=0' <<<"$out" && grep -q '^shell stop$' <<<"$out" && grep -q '^shell start$' <<<"$out" &&
  grep -q 'reserved 1024 MB' <<<"$out" && grep -q '::warning title=Zygote started without' <<<"$out"; then
  ok "a zygote without the heap size is restarted, with a warning, and then has it"
else
  bad "a clamped zygote was not repaired" "$out"
fi

# The zygote already has it: nothing restarts.
HEAPSIZE=512m ZYGOTE_MAPS="$MAPS_512M" ZYGOTE_MAPS_AFTER_RESTART="$MAPS_512M" ADB_LOG=""
out=$(ensure_zygote_heap 29; echo "status=$?"; printf '%s' "$ADB_LOG")
if grep -q 'status=0' <<<"$out" && ! grep -q 'shell stop' <<<"$out"; then
  ok "a zygote with the heap size is left alone"
else
  bad "a correct zygote was restarted" "$out"
fi

# A restart that does not help fails the lane before any test.
HEAPSIZE=512m ZYGOTE_MAPS="$MAPS_16M" ZYGOTE_MAPS_AFTER_RESTART="$MAPS_16M" ADB_LOG=""
out=$(ensure_zygote_heap 29; echo "status=$?")
if grep -q 'status=1' <<<"$out" && grep -q '::error title=Zygote heap is still below' <<<"$out"; then
  ok "a zygote still clamped after the restart fails with the reason"
else
  bad "a restart that did not help was not reported" "$out"
fi

# A reservation that cannot be read is never a 0 MB reading: each way it fails is named, and nothing restarts.
for case_ in \
  "up|no dalvik-main space mapping" \
  "gone|adb shell pidof failed (exit 1): adb: device 'emulator-5554' not found" \
  "no-zygote|no zygote process" \
  "not-root|permission denied (adbd is not root)"; do
  DEVICE="${case_%%|*}" HEAPSIZE=512m ZYGOTE_MAPS="" ZYGOTE_MAPS_AFTER_RESTART="" ADB_LOG=""
  want="${case_#*|}"
  out=$(ensure_zygote_heap 35; echo "status=$?"; printf '%s' "$ADB_LOG")
  if grep -q 'status=0' <<<"$out" && grep -qF "$want" <<<"$out" && grep -q 'not compared' <<<"$out" &&
    ! grep -q '0 MB' <<<"$out" && ! grep -q 'shell stop' <<<"$out"; then
    ok "an unreadable reservation ($DEVICE) says: $want"
  else
    bad "an unreadable reservation ($DEVICE) was misreported" "$out"
  fi
done
DEVICE=up

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
