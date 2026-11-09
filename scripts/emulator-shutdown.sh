#!/usr/bin/env bash
#
# Shuts the emulator down within a bounded time, and records where it was stuck if it would not go.
# Called last by every script that runs inside `reactivecircus/android-emulator-runner`'s `script:`
# (android-emulator-tests.sh, android-benchmark.sh).
#
# Usage: emulator-shutdown.sh <api-level> <tests-exit-status>
#
# Always exits 0: a wedge is a host-side emulator fault, reported as a ::warning:: with its capture,
# never as the caller's test result. The caller exits with its own status after this.
#
# The android-benchmark lane did not have this and hit exactly the wedge described below: run
# 42633798118 finished its benchmarks at 14:35:43, the action's `adb emu kill` got "OK: killing
# emulator, bye bye", and the step then sat until its 25-minute timeout at 14:52:16.

set -uo pipefail

API_LEVEL="${1:?usage: emulator-shutdown.sh <api-level> <tests-exit-status>}"
TEST_EXIT="${2:?usage: emulator-shutdown.sh <api-level> <tests-exit-status>}"

# A workflow-command message is one line; encode the rest (GitHub renders %0A as a newline).
gh_escape() {
  local s="$1"
  s="${s//'%'/%25}"
  s="${s//$'\r'/%0D}"
  s="${s//$'\n'/%0A}"
  printf '%s' "$s"
}

mkdir -p emulator-diagnostics

# EMULATOR SHUTDOWN, BOUNDED
# --------------------------
# The emulator inherits this step's stdout, so the step cannot finish while any emulator process is
# alive; after this script the action only sends `adb emu kill` and waits. An emulator whose console
# acknowledges that kill but whose main loop never runs the shutdown (#665) then holds the step until
# its timeout. So the shutdown happens here: `emu kill`, a grace period, and if qemu is still alive,
# a capture of where it is stuck followed by SIGKILL of every emulator process.
#
# A wedge after the tests is a host-side emulator fault, not a test result: it leaves the exit status
# alone, and is reported as a ::warning:: carrying the capture, which is also written to
# $WEDGE_FILE (uploaded by the workflow whenever it exists).
# Overridable only so .github/scripts/tests/emulator-shutdown.test.sh can aim at a fake emulator
# without touching a real one on the same machine.
QEMU_PATTERN="${EMULATOR_QEMU_PATTERN:-qemu-system}"
# Every process the emulator starts: launcher, qemu, crashpad_handler, netsimd.
EMULATOR_FAMILY_PATTERN="${EMULATOR_FAMILY_PATTERN:-$QEMU_PATTERN|crashpad_handler|netsimd|${ANDROID_HOME:-/nonexistent}/emulator/}"
# ANDROID_EMULATOR_WAIT_TIME_BEFORE_KILL=1 on this step: a healthy emulator is gone ~1 s after the ack.
EMU_KILL_GRACE_S="${EMULATOR_KILL_GRACE_S:-15}"
SIGKILL_GRACE_S="${EMULATOR_SIGKILL_GRACE_S:-10}"
WEDGE_FILE="emulator-diagnostics/emulator-shutdown-wedge-api${API_LEVEL}.txt"

pids_matching() { pgrep -d ' ' -f "$1" || true; }

# Returns 0 once no qemu-system process remains, 1 if one is still alive after $1 seconds.
wait_qemu_gone() {
  local deadline=$((SECONDS + $1))
  while [ -n "$(pids_matching "$QEMU_PATTERN")" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      return 1
    fi
    sleep 0.5
  done
}

ps_family() {
  local pids
  pids=$(pids_matching "$EMULATOR_FAMILY_PATTERN")
  if [ -n "$pids" ]; then
    ps -o pid,ppid,stat,wchan:32,etime,cmd -p "${pids// /,}" || true
  else
    echo "(no emulator processes)"
  fi
}

# Where each qemu-system thread is: state, kernel wait channel, syscall; kernel stacks of the main
# thread and of any thread in D state; userspace backtraces when gdb is present.
capture_qemu() {
  local pid task tid comm state
  echo "--- emulator processes ---"
  ps_family
  for pid in $(pids_matching "$QEMU_PATTERN"); do
    echo
    echo "--- qemu pid $pid: /proc/$pid/status ---"
    grep -E '^(Name|State|Threads|SigPnd|ShdPnd|SigBlk|SigCgt)' "/proc/$pid/status" 2>/dev/null || echo "(unreadable)"
    echo "--- qemu pid $pid: threads (tid comm state wchan | syscall) ---"
    for task in /proc/"$pid"/task/*; do
      [ -d "$task" ] || continue
      tid=${task##*/}
      comm=$(cat "$task/comm" 2>/dev/null || echo '?')
      state=$(sed -E 's/^.*\) (.).*$/\1/' "$task/stat" 2>/dev/null || echo '?')
      printf '%s %s %s %s | %s\n' "$tid" "$comm" "$state" \
        "$(cat "$task/wchan" 2>/dev/null || echo '?')" \
        "$(sudo -n cat "$task/syscall" 2>/dev/null || echo '?')"
      if [ "$tid" = "$pid" ] || [ "$state" = "D" ]; then
        echo "  kernel stack of $tid:"
        sudo -n cat "$task/stack" 2>&1 | sed 's/^/    /' || true
      fi
    done
    if command -v gdb >/dev/null 2>&1; then
      echo "--- qemu pid $pid: userspace backtraces (gdb) ---"
      sudo -n timeout 60 gdb -p "$pid" -batch -ex 'thread apply all bt' 2>&1 || true
    else
      echo "--- qemu pid $pid: no userspace backtraces (gdb not installed) ---"
    fi
  done
}

echo "Emulator shutdown: qemu-system pids before 'adb emu kill': $(pids_matching "$QEMU_PATTERN")"
EMU_KILL_EXIT=0
timeout 15 adb emu kill || EMU_KILL_EXIT=$?
if [ "$EMU_KILL_EXIT" != "0" ]; then
  echo "'adb emu kill' exited $EMU_KILL_EXIT (124 = no answer within 15 s)"
fi

if wait_qemu_gone "$EMU_KILL_GRACE_S"; then
  echo "Emulator shutdown: qemu-system exited within ${EMU_KILL_GRACE_S}s of 'adb emu kill'"
  LEFTOVER=$(pids_matching "$EMULATOR_FAMILY_PATTERN")
  if [ -n "$LEFTOVER" ]; then
    echo "Emulator shutdown: other emulator processes still running (left to the action / job cleanup):"
    ps_family
  fi
else
  CAPTURE=$(capture_qemu 2>&1 || true)
  {
    echo "Emulator shutdown wedge (API ${API_LEVEL}): qemu-system still alive ${EMU_KILL_GRACE_S}s after 'adb emu kill' (exit ${EMU_KILL_EXIT})"
    echo "captured $(date -u +%FT%TZ), before SIGKILL"
    echo
    echo "$CAPTURE"
  } >"$WEDGE_FILE"
  echo "::group::Emulator shutdown wedge capture (API ${API_LEVEL}) -> ${WEDGE_FILE}"
  cat "$WEDGE_FILE"
  echo "::endgroup::"

  pkill -9 -f "$QEMU_PATTERN" || true
  if wait_qemu_gone "$SIGKILL_GRACE_S"; then
    KILL_RESULT="SIGKILLed qemu-system; it exited."
  else
    KILL_RESULT="qemu-system SURVIVED SIGKILL for ${SIGKILL_GRACE_S}s (uninterruptible sleep?) — this step will hang until its timeout."
    echo "::error title=Emulator unkillable (API ${API_LEVEL})::$(gh_escape "$KILL_RESULT
$(ps_family)")"
  fi
  # Any other emulator process also holds the step's stdout open.
  pkill -9 -f "$EMULATOR_FAMILY_PATTERN" || true
  echo "Emulator shutdown: $KILL_RESULT Remaining emulator processes:"
  ps_family
  # The annotation carries the head of the capture; the file has all of it.
  WARN_CAPTURE=$(printf '%s\n' "$CAPTURE" | sed -n '1,150p')
  echo "::warning title=Emulator shutdown wedge (API ${API_LEVEL}, tests exit ${TEST_EXIT})::$(gh_escape "Host-side emulator fault after the tests finished, not a test result. qemu-system ignored 'adb emu kill' for ${EMU_KILL_GRACE_S}s. ${KILL_RESULT} Full capture: ${WEDGE_FILE} (artifact emulator-shutdown-wedge-${API_LEVEL}).
$WARN_CAPTURE")"
fi

exit 0
