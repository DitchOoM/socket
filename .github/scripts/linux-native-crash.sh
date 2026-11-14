#!/usr/bin/env bash
#
# Crash and hang evidence for a Kotlin/Native test binary on a Linux runner: a core dump turned into
# gdb backtraces on the runner (where the binary is), and every thread's stack when a run outlives its
# budget. Written only when something went wrong. Driven by .github/actions/linux-native-crash;
# tested by tests/linux-native-crash.test.sh.
#
#   start   <dir>                         route cores into <dir>/cores, check gdb
#   run     <dir> <hang-seconds> <cmd...> run cmd with cores enabled; stacks at the budget; backtraces on a core
#   collect <dir> <out>                   compress the evidence into <out>, capped
#
# A core needs `ulimit -c` raised in the SAME shell that starts the binary (each workflow step is a new
# shell), so the test step runs its binary through `run`. core_pattern is machine-wide and is set once
# by `start`. The raw core is kept only when it fits the cap: it needs the exact binary to be read,
# which is why the backtraces are taken here.
#
# Overrides (tests): CRASH_GDB, CRASH_SUDO, CRASH_CORE_PATTERN_FILE, CRASH_CAP_BYTES.
set -uo pipefail

GDB="${CRASH_GDB:-gdb}"
SUDO="${CRASH_SUDO-sudo}"
CORE_PATTERN_FILE="${CRASH_CORE_PATTERN_FILE:-/proc/sys/kernel/core_pattern}"
CAP_BYTES="${CRASH_CAP_BYTES:-20000000}"

gdb_core() { # <binary> <core> <out>
  "$GDB" -batch -nx -q \
    -ex 'set pagination off' \
    -ex 'echo \n===== crashed thread =====\n' -ex 'bt 80' \
    -ex 'echo \n===== registers =====\n' -ex 'info registers' \
    -ex 'echo \n===== instructions at pc =====\n' -ex 'x/12i $pc' \
    -ex 'echo \n===== all threads =====\n' -ex 'thread apply all bt 50' \
    -ex 'echo \n===== shared libraries =====\n' -ex 'info sharedlibrary' \
    "$1" "$2" >"$3" 2>&1
}

do_start() { # <dir>
  local dir="$1"
  mkdir -p "$dir/cores"
  chmod 1777 "$dir/cores" 2>/dev/null || true
  if ! echo "$dir/cores/core.%e.%p" | $SUDO tee "$CORE_PATTERN_FILE" >/dev/null; then
    echo "::warning title=No core dumps::could not set core_pattern; a crash will leave only its exit status"
  fi
  if "$GDB" --version >/dev/null 2>&1; then
    echo "armed: $("$GDB" --version | head -1); cores -> $dir/cores"
  else
    echo "::warning title=No gdb::gdb is missing; cores are kept but not read"
  fi
  touch "$dir/armed"
}

do_run() { # <dir> <hang-seconds> <cmd...>
  local dir="$1" hang="$2"
  shift 2
  local binary="$1" marker="$dir/run-$$.marker"
  touch "$marker"
  ulimit -c unlimited 2>/dev/null || echo "::warning::ulimit -c unlimited refused; no core for this run"
  "$@" &
  local pid=$!
  (
    # The sleep must not hold the step's output pipe: a finished run would wait for it to expire.
    sleep "$hang" </dev/null >/dev/null 2>&1 &
    local nap=$!
    trap 'kill "$nap" 2>/dev/null; exit 0' TERM
    wait "$nap"
    if kill -0 "$pid" 2>/dev/null; then
      local out="$dir/hang-$(basename "$binary")-$pid.txt"
      echo "::error title=Hang::$(basename "$binary") (pid $pid) still running after ${hang}s; all thread stacks -> $(basename "$out")" >&2
      $SUDO "$GDB" -p "$pid" -batch -nx -q -ex 'set pagination off' -ex 'thread apply all bt 50' >"$out" 2>&1
      grep -m 40 -E '^(Thread |#[0-9])' "$out" >&2 || true
    fi
  ) &
  local watchdog=$!
  wait "$pid"
  local status=$?
  kill "$watchdog" 2>/dev/null
  wait "$watchdog" 2>/dev/null
  local core found=0
  for core in "$dir"/cores/core.*; do
    [ -f "$core" ] && [ "$core" -nt "$marker" ] || continue
    found=1
    local out="$dir/crash-$(basename "$core").txt"
    if gdb_core "$binary" "$core" "$out"; then :; fi
    echo "::error title=Native crash::$(basename "$binary") exited $status and left $(basename "$core"); backtraces -> $(basename "$out")" >&2
    sed -n '/===== crashed thread =====/,/===== registers =====/p' "$out" | head -60 >&2
  done
  if [ "$found" -eq 0 ] && [ "$status" -gt 128 ]; then
    echo "::error title=Killed by signal $((status - 128))::$(basename "$binary") died on signal $((status - 128)) and left no core (core_pattern or ulimit refused)" >&2
  fi
  rm -f "$marker"
  return "$status"
}

do_collect() { # <dir> <out>
  local dir="$1" out="$2" f
  mkdir -p "$out"
  for f in "$dir"/crash-*.txt "$dir"/hang-*.txt; do
    [ -f "$f" ] && gzip -c "$f" >"$out/$(basename "$f").gz"
  done
  local used
  used=$(cat "$out"/* 2>/dev/null | wc -c | tr -d ' ')
  for f in "$dir"/cores/core.*; do
    [ -f "$f" ] || continue
    gzip -c "$f" >"$out/$(basename "$f").gz.part"
    local size
    size=$(wc -c <"$out/$(basename "$f").gz.part")
    if [ $((used + size)) -le "$CAP_BYTES" ]; then
      mv "$out/$(basename "$f").gz.part" "$out/$(basename "$f").gz"
      used=$((used + size))
    else
      rm -f "$out/$(basename "$f").gz.part"
      echo "dropped $(basename "$f"): ${size} bytes compressed would pass the ${CAP_BYTES}-byte cap; its backtraces are kept" | tee -a "$out/dropped.txt"
    fi
  done
  ls -la "$out"
}

case "${1:-}" in
  start) do_start "${2:?dir}" ;;
  run) do_run "${2:?dir}" "${3:?hang-seconds}" "${@:4}" ;;
  collect) do_collect "${2:?dir}" "${3:?out}" ;;
  *) echo "usage: $0 start|run|collect ..." >&2; exit 2 ;;
esac
