#!/usr/bin/env bash
#
# Stands in for the SDK's `adb` on the emulator lane (installed by scripts/android-emulator-tests.sh,
# which moves the real binary to `adb.real`). Every command is passed straight through to the real
# adb, except an APK install, which is bounded, recorded when it stalls, and retried once.
#
# WHY
# ---
# AGP 9.5's test engine installs each test APK with `adb -s <serial> install -t -g --user 0 <apk>`
# and waits for it with no deadline: AGP writes the timeout as `android-test.install-timeout-ms[<serial>]`
# and the engine reads the key without the serial, so `installApkTimeout` never reaches it, and
# `installation.installOptions` never reaches the command line either. On the API-24 emulator that
# streamed install (`cmd package install -S <size>`, whose stdin is a PTY that adbd feeds and that
# system_server reads in PackageManagerShellCommand.doWriteSplit) has stalled four times since the
# AGP 9.5.1 bump, always the same way. Run 42513600114 has the fullest capture: installd logged
# `free_cache(<apk size>)` as the install session opened its file, then nothing for two minutes,
# while the device kept answering `adb shell`, `cmd` sat in its binder call to system_server, and
# the host `adb install` kept waiting. Which hop stopped moving bytes is not yet known.
#
# WHAT
# ----
# An install that has not finished after STALL_S seconds is recorded to
# <diag dir>/install-stall-<n>.txt with what that capture lacked: how many bytes the host adb
# client has written and whether the count still moves, the TCP queues between the adb client, the
# adb server and the emulator, where adbd, `cmd` and system_server's threads are (a Java stack dump
# of system_server), and how much of the APK the device's session file actually holds. The stalled
# install is then killed, the device is checked again to see what that released, and the install
# is retried once WITHOUT streaming (`--no-streaming -r`: adb pushes the APK to /data/local/tmp and
# runs `pm install` on the local file). Its output and exit status are what the caller sees.
#
# This is a workaround for the stall, not its fix: the retry takes the stalled stage out of the
# path, and the capture is there so the next stall says where the stream stopped.

set -uo pipefail

REAL_ADB="@REAL_ADB@"
DIAG_DIR="@DIAG_DIR@"
STALL_S="@STALL_S@"
RETRY_S="@RETRY_S@"

# Global options come before the subcommand; the ones that take a value are listed here.
args=("$@")
i=0
while [ "$i" -lt "${#args[@]}" ]; do
  case "${args[$i]}" in
    -s | -t | -H | -P | -L) i=$((i + 2)) ;;
    -a | -d | -e) i=$((i + 1)) ;;
    *) break ;;
  esac
done
last="${args[${#args[@]} - 1]:-}"

global=("${args[@]:0:$i}")

# Device queries are bounded so a wedged adb cannot hang the capture. The test engine's environment
# is not the CI step's, so do not assume `timeout` is on its PATH.
if command -v timeout >/dev/null 2>&1; then
  bounded() { timeout "$@"; }
else
  bounded() { shift; "$@"; }
fi

# A LOST ADB LINK: WHAT THE HOST AND THE DEVICE LOOKED LIKE AT THAT INSTANT
# --------------------------------------------------------------------------
# adb ends a command with 255 when its link to the device or to the adb server broke under it. The
# test engine reads that as a failed device query and gives up without saying why. Each probe below
# answers one question: is the server the same process as before (pid and start time: a restart
# shows as a young server), does the server still list the device and in what state, does a shell
# round trip work now, and is the emulator process alive and running. The two adb probes run with
# ADB_TRACE=adb,transport, whose output goes to this file only: the engine's own commands run
# untraced, so its stdout, stderr and exit status are exactly what they were.
#
# Recorded to <diag dir>/adb-link-loss-<n>.txt, at most LINK_LOSS_CAPTURES per run, by a detached
# background job started before the failing command's status is returned. Every probe is bounded
# to PROBE_S seconds and its own exit status is written next to its output.
LINK_LOSS_CAPTURES=3
PROBE_S=4

probe() { # <title> <command...>: the command's output, bounded, with its exit status
  local title="$1"
  shift
  echo
  echo "--- $title ---"
  bounded "$PROBE_S" "$@" 2>&1 | head -c 6000
  echo "[exit ${PIPESTATUS[0]}]"
}

record_link_loss() { # <exit status> <failed command line>
  local n=1 report
  while [ -e "$DIAG_DIR/adb-link-loss-$n.txt" ]; do n=$((n + 1)); done
  [ "$n" -le "$LINK_LOSS_CAPTURES" ] || return 0
  report="$DIAG_DIR/adb-link-loss-$n.txt"
  {
    echo "adb ended with status $1: $2"
    echo "captured $(date -u +%FT%TZ)"
    probe "adb get-state" "$REAL_ADB" "${global[@]}" get-state
    probe "adb devices -l" "$REAL_ADB" devices -l
    probe "adb shell echo ok" "$REAL_ADB" "${global[@]}" shell echo ok
    # The bracketed first letter keeps each pgrep from matching the bash that carries its pattern.
    # shellcheck disable=SC2016 # the scripts are for the inner bash
    probe "adb server processes (pid, started, age)" bash -c \
      'for p in $(pgrep -f "adb.*([f]ork-server|[n]odaemon) server"); do ps -o pid=,lstart=,etime=,stat= -p "$p"; done'
    # shellcheck disable=SC2016
    probe "emulator processes (pid, state, age, cpu%, rss KiB)" bash -c \
      'for p in $(pgrep -f "[q]emu-system|[e]mulator64"); do ps -o pid=,stat=,etime=,pcpu=,rss=,comm= -p "$p"; done'
    probe "adb get-state, traced (adb,transport)" env ADB_TRACE=adb,transport "$REAL_ADB" "${global[@]}" get-state
    probe "adb shell echo ok, traced (adb,transport)" env ADB_TRACE=adb,transport "$REAL_ADB" "${global[@]}" shell echo ok
    # shellcheck disable=SC2016
    probe "adb server log, tail" bash -c 'tail -n 60 "$1"/adb.*.log 2>&1' _ "${TMPDIR:-/tmp}"
  } >"$report" 2>&1
}

# THE ADB JOURNAL
# ---------------
# Every command, install or not, is recorded in $DIAG_DIR/adb-journal.txt: when it started, which
# process ran it, its exit status, how long it took, and the head of its stderr when it failed.
# AGP 9.5.1's test engine runs on the JUnit Platform, and when the engine fails before a device has
# run anything, AGP throws the exception away: no listener logs it, the module's test-result.pb is
# never written, and the log says only "Could not find test-result.pb for device" (run
# 42650305746, :socket-http3 on API 24, 0.8 s after "Running tests on devices", with no install or
# instrumentation reaching the device). Nearly everything the engine does before that point is an
# adb command, and a failed one is the likeliest cause (DeviceApiLevelProvider throws when
# `getprop ro.build.version.sdk` fails), so this record names it. android-emulator-tests.sh prints
# the failed entries when a module ran no tests.
#
# adb is a child rather than exec'd, so its status can be recorded. Its stdin and stdout are the
# caller's; its stderr is held until it exits (the engine reads stderr in full after the exit
# anyway). SIGTERM, SIGINT and SIGHUP are passed on, so `Process.destroy()` still stops adb.
# `destroyForcibly()` (SIGKILL) cannot be passed on; the engine's AdbController then joins its
# reader threads for one second at most, so an adb left running cannot hang it.
# Milliseconds since the epoch, without a fork where bash 5's EPOCHREALTIME exists.
now_ms() {
  local t="${EPOCHREALTIME:-}"
  if [ -n "$t" ]; then
    t="${t/[.,]/}"
    echo $((10#$t / 1000))
  else
    echo $(($(date +%s) * 1000))
  fi
}

journal() {
  local ms
  ms=$(now_ms)
  printf '%s.%03d %s\n' "$(TZ=UTC date -r $((ms / 1000)) +%H:%M:%S 2>/dev/null || TZ=UTC date -d "@$((ms / 1000))" +%H:%M:%S)" \
    $((ms % 1000)) "$*" >>"$DIAG_DIR/adb-journal.txt" 2>/dev/null || true
}

run_journaled() {
  local caller errf start rc child ms
  caller=$({ tr -d '\n' <"/proc/$PPID/comm"; } 2>/dev/null || echo '?')
  if ! mkdir -p "$DIAG_DIR" 2>/dev/null || ! errf=$(mktemp 2>/dev/null); then
    exec "$REAL_ADB" "$@"
  fi
  start=$(now_ms)
  # `0<&0`: a background command's stdin is /dev/null unless it is redirected explicitly.
  "$REAL_ADB" "$@" 0<&0 2>"$errf" &
  child=$!
  journal "start pid=$child caller=$caller($PPID) adb $*"
  trap 'kill -TERM "$child" 2>/dev/null' TERM INT HUP
  while :; do
    wait "$child"
    rc=$?
    kill -0 "$child" 2>/dev/null || break
  done
  trap - TERM INT HUP
  cat "$errf" >&2
  ms=$(($(now_ms) - start))
  if [ "$rc" = "0" ]; then
    journal "end   pid=$child rc=0 ${ms}ms"
  else
    journal "end   pid=$child rc=$rc ${ms}ms FAILED adb $* | stderr: $(head -c 400 "$errf" | tr '\n' ' ')"
    if [ "$rc" = "255" ]; then
      # Detached, with no fd shared with the engine, so it neither delays the status below nor holds
      # a pipe the engine waits on.
      record_link_loss "$rc" "adb $*" 0</dev/null >/dev/null 2>&1 &
    fi
  fi
  rm -f "$errf"
  exit "$rc"
}

if [ "${args[$i]:-}" != "install" ] || [[ "$last" != *.apk ]]; then
  run_journaled "$@"
fi
mkdir -p "$DIAG_DIR" 2>/dev/null
journal "start pid=$$ (install guard) adb $*"

install_opts=("${args[@]:$((i + 1))}")
unset 'install_opts[${#install_opts[@]}-1]'
apk="$last"

dev() { bounded "$1" "$REAL_ADB" "${global[@]}" shell "${@:2}" 2>&1; }

# Runs the real adb with the given arguments in the background; waits up to $1 seconds.
# Sets RUN_PID, RUN_OUT, and RUN_EXIT (empty if it is still running).
run_bounded() {
  local limit="$1"
  shift
  RUN_OUT=$(mktemp)
  "$REAL_ADB" "$@" >"$RUN_OUT" 2>&1 &
  RUN_PID=$!
  local deadline=$((SECONDS + limit))
  while kill -0 "$RUN_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 1
  done
  RUN_EXIT=""
  if ! kill -0 "$RUN_PID" 2>/dev/null; then
    wait "$RUN_PID"
    RUN_EXIT=$?
  fi
}

finish() {
  if [ "$1" = "0" ]; then
    journal "end   pid=$$ (install guard) rc=0"
  else
    journal "end   pid=$$ (install guard) rc=$1 FAILED adb ${args[*]} | output: $(head -c 400 "$RUN_OUT" | tr '\n' ' ')"
  fi
  cat "$RUN_OUT"
  rm -f "$RUN_OUT"
  exit "$1"
}

run_bounded "$STALL_S" "${global[@]}" install "${install_opts[@]}" "$apk"
if [ -n "$RUN_EXIT" ]; then
  finish "$RUN_EXIT"
fi

# ── Stalled: record it ───────────────────────────────────────────────────────────────────────────
mkdir -p "$DIAG_DIR"
n=1
while [ -e "$DIAG_DIR/install-stall-$n.txt" ]; do n=$((n + 1)); done
report="$DIAG_DIR/install-stall-$n.txt"
stalled_pid="$RUN_PID"
stalled_out="$RUN_OUT"
apk_size=$(wc -c <"$apk" | tr -d ' ')

host_proc() {
  local pid="$1"
  echo "state: $(grep -E '^State' "/proc/$pid/status" 2>/dev/null)  wchan: $(cat "/proc/$pid/wchan" 2>/dev/null)"
  echo "io:"
  sed 's/^/  /' "/proc/$pid/io" 2>/dev/null || echo "  (unreadable)"
  for t in /proc/"$pid"/task/*; do
    [ -d "$t" ] || continue
    echo "  thread ${t##*/} $(cat "$t/comm" 2>/dev/null) $(sed -E 's/^.*\) (.).*$/\1/' "$t/stat" 2>/dev/null) $(cat "$t/wchan" 2>/dev/null)"
  done
  echo "kernel stack:"
  sudo -n cat "/proc/$pid/stack" 2>&1 | sed 's/^/  /' || true
}

{
  echo "Install stalled: no result after ${STALL_S}s"
  echo "command: $REAL_ADB ${args[*]}"
  echo "apk: $apk ($apk_size bytes)"
  echo "captured $(date -u +%FT%TZ)"

  echo
  echo "--- host adb install client (pid $stalled_pid) ---"
  host_proc "$stalled_pid"
  wchar1=$(awk '/^wchar/ { print $2 }' "/proc/$stalled_pid/io" 2>/dev/null)
  sleep 3
  wchar2=$(awk '/^wchar/ { print $2 }' "/proc/$stalled_pid/io" 2>/dev/null)
  echo "wchar 3 s apart: ${wchar1:-?} -> ${wchar2:-?} (APK is $apk_size bytes)"
  echo "output so far:"
  sed 's/^/  /' "$stalled_out"

  echo
  echo "--- host adb server ---"
  for pid in $(pgrep -f 'adb.*(fork-server|nodaemon) server' || true); do
    echo "pid $pid: $(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null)"
    host_proc "$pid"
  done

  # Send-Q/Recv-Q show where bytes are queued: client -> server on 5037, server -> emulator on the
  # emulator's adb port (its console port + 1).
  echo
  echo "--- host TCP sockets of adb and the emulator (ss -tnpi) ---"
  ss -tnpi 2>&1 | grep -E -A1 'adb|qemu|:5037|:555[0-9]' || true

  echo
  echo "--- device: does it answer ---"
  dev 15 echo alive

  echo
  echo "--- device: install-related processes ---"
  dev 15 ps | awk 'NR == 1 || / (cmd|sh|adbd|system_server|installd|logcat)$/'

  echo
  echo "--- device: where adbd, cmd and their shell wait ---"
  dev 20 'for p in $(pidof adbd) $(pidof cmd); do
      echo "pid $p $(cat /proc/$p/cmdline | tr "\0" " ")";
      for t in /proc/$p/task/*; do echo "  thread ${t##*/} $(cat $t/comm) $(cat $t/wchan)"; done;
      echo "  fds:"; ls -l /proc/$p/fd 2>&1 | sed "s/^/    /";
      echo "  kernel stack:"; cat /proc/$p/stack 2>&1 | sed "s/^/    /";
    done'

  # How much of the APK reached the session file. The file is fallocate()d to the full size up
  # front, so its length says nothing; comparing 1-MiB blocks with the host's copy does. A block
  # that differs (normally all zeros) has not arrived.
  echo
  echo "--- device: how much of the APK the install session holds (md5 per MiB, host vs device) ---"
  session_apk=$(dev 10 'ls /data/app/vmdl*.tmp/base.apk 2>/dev/null | head -1' | tr -d '\r' | grep '^/data/app/' || true)
  echo "session file: ${session_apk:-none}"
  if [ -n "$session_apk" ]; then
    blocks=$(((apk_size + 1048575) / 1048576))
    device_sums=$(dev 60 "k=0; while [ \$k -lt $blocks ]; do dd if=$session_apk bs=1048576 skip=\$k count=1 2>/dev/null | md5sum | cut -d' ' -f1; k=\$((k+1)); done" | tr -d '\r' | grep -E '^[0-9a-f]{32}$')
    k=0
    first_missing=""
    while IFS= read -r device_sum; do
      host_sum=$(dd if="$apk" bs=1048576 skip="$k" count=1 2>/dev/null | md5sum | cut -d' ' -f1)
      if [ "$host_sum" != "$device_sum" ] && [ -z "$first_missing" ]; then
        first_missing=$k
      fi
      k=$((k + 1))
    done <<<"$device_sums"
    if [ -z "$first_missing" ]; then
      echo "all $blocks MiB blocks match: the whole APK reached the device"
    else
      echo "MiB blocks 0..$((first_missing - 1)) match; block $first_missing is the first that does not"
      echo "=> the device received between $((first_missing * 1048576)) and $(((first_missing + 1) * 1048576)) of $apk_size bytes"
    fi
  fi

  # SIGQUIT makes ART write every thread's Java stack to /data/anr/traces.txt, into an existing file
  # only. The binder thread that runs `cmd package install` shows whether system_server is reading
  # the stream or waiting on something else.
  echo
  echo "--- device: system_server Java stacks ---"
  dev 10 'rm -f /data/anr/traces.txt; touch /data/anr/traces.txt && chmod 666 /data/anr/traces.txt; kill -3 $(pidof system_server)'
  sleep 4
  dev 30 'cat /data/anr/traces.txt'

  echo
  echo "--- device logcat, last 200 lines ---"
  bounded 20 "$REAL_ADB" "${global[@]}" logcat -d -t 200 -v threadtime 2>&1
} >"$report" 2>&1

# ── Release it, and see what that freed ───────────────────────────────────────────────────────────
kill "$stalled_pid" 2>/dev/null
sleep 2
kill -9 "$stalled_pid" 2>/dev/null
sleep 3
{
  echo
  echo "--- 5 s after killing the stalled host adb install ---"
  dev 15 ps | awk 'NR == 1 || / (cmd|sh)$/'
  dev 10 'ls -la /data/app'
} >>"$report" 2>&1
rm -f "$stalled_out"

# ── Retry once, pushed rather than streamed ─────────────────────────────────────────────────────
run_bounded "$RETRY_S" "${global[@]}" install --no-streaming -r "${install_opts[@]}" "$apk"
if [ -z "$RUN_EXIT" ]; then
  kill -9 "$RUN_PID" 2>/dev/null
  echo "RESULT: the non-streamed retry did not finish within ${RETRY_S}s either" >>"$report"
  echo "adb install stalled twice (streamed, then pushed); see $report" >>"$RUN_OUT"
  finish 1
fi
{
  echo
  echo "--- non-streamed retry: exit $RUN_EXIT ---"
  cat "$RUN_OUT"
  if [ "$RUN_EXIT" = "0" ]; then
    echo "RESULT: recovered by the non-streamed retry"
  else
    echo "RESULT: the non-streamed retry failed (exit $RUN_EXIT)"
  fi
} >>"$report" 2>&1
finish "$RUN_EXIT"
