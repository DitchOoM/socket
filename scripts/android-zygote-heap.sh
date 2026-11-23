#!/usr/bin/env bash
#
# Sourced by scripts/android-emulator-tests.sh. Makes the test process's Java heap the one the emulator
# was booted with, before any test runs.
#
# WHY THE HEAP CAN BE 16 MB ON A 512 MB EMULATOR
# ----------------------------------------------
# An app's maximum heap is the zygote's -Xmx, fixed when the zygote starts: every app process is forked
# from it and does not read dalvik.vm.heapsize again. The API 29 x86_64 image has no dalvik.vm.heapsize in
# any build.prop; its vendor init.ranchu.rc sets it `on boot`, from ro.kernel.qemu.dalvik.vm.heapsize. But
# init.rc triggers `zygote-start` before `early-boot` and `boot`, so the zygote races that setprop. A zygote
# that reads the property first finds none and AndroidRuntime falls back to -Xmx16m. getprop then reports
# 512m while every app, largeHeap or not, has 16 MB, and the many-connection suites run out of memory.
#
# The zygote's heap reservation is read from its maps: the "dalvik-main space" mappings span the heap
# capacity (twice it under the concurrent-copying collector). A span below dalvik.vm.heapsize means the
# zygote started without it, and the framework is restarted so the zygote reads the property as it is now.

# Bytes in a dalvik heap property value: 512m, 196608k, 1g, or plain bytes. Empty for anything else.
heap_property_bytes() {
  local v="${1//[$'\r\n ']/}"
  case "$v" in
    *[gG]) [[ "${v%?}" =~ ^[0-9]+$ ]] && echo $((${v%?} * 1024 * 1024 * 1024)) ;;
    *[mM]) [[ "${v%?}" =~ ^[0-9]+$ ]] && echo $((${v%?} * 1024 * 1024)) ;;
    *[kK]) [[ "${v%?}" =~ ^[0-9]+$ ]] && echo $((${v%?} * 1024)) ;;
    *) [[ "$v" =~ ^[0-9]+$ ]] && echo "$v" ;;
  esac
  return 0
}

# The total span of the "dalvik-main space" mappings in maps text on stdin, in bytes (0 when there are none).
main_space_span_bytes() {
  local total=0 range lo hi
  while read -r range _; do
    lo="${range%-*}"
    hi="${range#*-}"
    total=$((total + 16#$hi - 16#$lo))
  done < <(tr -d '\r' | grep 'dalvik-main space')
  echo "$total"
}

# The zygote's Java heap reservation (the 64-bit zygote where there is one): its size in bytes, or
# "unreadable: <why>" naming which step failed and how.
zygote_heap_reservation() {
  local out rc pid bytes
  out=$(adb shell 'pidof zygote64 || pidof zygote' 2>&1) && rc=0 || rc=$?
  out="${out//$'\r'/}"
  pid=$(awk 'NR == 1 && $1 ~ /^[0-9]+$/ { print $1 }' <<<"$out")
  if [ -z "$pid" ]; then
    if [ "$rc" = "0" ] || [ -z "$out" ]; then
      echo "unreadable: no zygote process (pidof zygote64 and pidof zygote found none)"
    else
      echo "unreadable: adb shell pidof failed (exit $rc): $(head -c 200 <<<"$out" | tr '\n' ' ')"
    fi
    return 0
  fi
  out=$(adb shell cat "/proc/$pid/maps" 2>&1) && rc=0 || rc=$?
  bytes=$(main_space_span_bytes <<<"$out")
  if [ "$bytes" != "0" ]; then
    echo "$bytes"
  elif grep -qi 'permission denied' <<<"$out"; then
    echo "unreadable: /proc/$pid/maps: permission denied (adbd is not root)"
  elif [ "$rc" != "0" ]; then
    echo "unreadable: adb shell cat /proc/$pid/maps failed (exit $rc): $(head -c 200 <<<"${out//$'\r'/}" | tr '\n' ' ')"
  else
    echo "unreadable: /proc/$pid/maps of zygote $pid has no dalvik-main space mapping"
  fi
}

# The reservation from zygote_heap_reservation, for a person: "<n> MB" or the reason it is unknown.
reservation_text() {
  case "$1" in
    unreadable:*) echo "$1" ;;
    *) echo "$(($1 / 1024 / 1024)) MB" ;;
  esac
}

# 0 once sys.boot_completed is 1, 1 if not within $1 seconds.
await_boot_completed() {
  local deadline=$((SECONDS + $1))
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      return 1
    fi
    sleep 2
  done
}

# Restarts the framework when the zygote's heap is smaller than dalvik.vm.heapsize. Prints what it found and
# did; returns 1 when the zygote still has the wrong heap afterwards. Needs a root adbd.
ensure_zygote_heap() {
  local api="$1" wanted_prop wanted reserved rc
  wanted_prop=$(adb shell getprop dalvik.vm.heapsize 2>&1) && rc=0 || rc=$?
  wanted_prop="${wanted_prop//$'\r'/}"
  if [ "$rc" != "0" ]; then
    wanted_prop="unreadable: adb shell getprop failed (exit $rc): $(head -c 200 <<<"$wanted_prop" | tr '\n' ' ')"
  fi
  wanted=$(heap_property_bytes "$wanted_prop")
  reserved=$(zygote_heap_reservation)
  echo "zygote heap (API ${api}): dalvik.vm.heapsize=${wanted_prop:-unset}, zygote reserved $(reservation_text "$reserved")"
  case "$reserved" in
    unreadable:*)
      echo "zygote heap (API ${api}): not compared, because the zygote's reservation is ${reserved}; the test process reports its own heap at start"
      return 0
      ;;
  esac
  if [ -z "$wanted" ]; then
    echo "zygote heap (API ${api}): not compared, because dalvik.vm.heapsize is ${wanted_prop:-unset}; the test process reports its own heap at start"
    return 0
  fi
  if [ "$reserved" -ge "$wanted" ]; then
    return 0
  fi
  echo "::warning title=Zygote started without the emulator's heap size (API ${api})::$(gh_escape "The zygote reserved $(reservation_text "$reserved") of Java heap, below dalvik.vm.heapsize=${wanted_prop}: it started before init set the property, so every app would get the 16 MB fallback. Restarting the framework so the zygote reads it.")"
  adb shell stop
  adb shell setprop sys.boot_completed 0
  adb shell start
  if ! await_boot_completed 240; then
    echo "::error title=Framework restart did not complete (API ${api})::$(gh_escape "After restarting the framework to give the zygote dalvik.vm.heapsize=${wanted_prop}, sys.boot_completed was not 1 within 240 s. This is the emulator, not a test result.")"
    return 1
  fi
  reserved=$(zygote_heap_reservation)
  echo "zygote heap (API ${api}) after the framework restart: zygote reserved $(reservation_text "$reserved")"
  case "$reserved" in
    unreadable:*) ;;
    *) [ "$reserved" -lt "$wanted" ] || return 0 ;;
  esac
  echo "::error title=Zygote heap is still below dalvik.vm.heapsize (API ${api})::$(gh_escape "The restarted zygote reserved $(reservation_text "$reserved"), below dalvik.vm.heapsize=${wanted_prop}. No test ran: their heap would not be the emulator's. This is the emulator, not a test result.")"
  return 1
}
