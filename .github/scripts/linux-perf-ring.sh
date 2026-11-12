#!/usr/bin/env bash
#
# A `perf record` ring of a Kotlin/Native test binary on Linux, kept in memory and written out ONLY
# when the job fails or hangs. Driven by .github/actions/linux-perf-ring; tested by
# tests/linux-perf-ring.test.sh (fake perf) and, on a real runner, by the probe in the PR.
#
#   start   <dir>            arm the watcher (a detached loop that attaches perf to each test.kexe)
#   watch   <dir>            (internal)
#   collect <dir> <out>      snapshot what is still running, then filter + compress the snapshots
#
# WHY: on 2026-11-11 the Linux soak stall left stacks of ONE instant (the gdb `thread apply all bt`
# of the hang watchdog). A scheduling timeline says which thread was descheduled, woken by whom, and
# where the CPU time went in the minute before the stall.
#
# HOW (measured on ubuntu-24.04 and ubuntu-24.04-arm hosted runners, kernel 7.0 azure):
#   - `perf` is preinstalled (/usr/bin/perf 7.0.14, matching the running kernel), so no apt step
#     is needed in the common case; the action falls back to the project's apt-install-cached only
#     if it is missing.
#   - kernel.perf_event_paranoid is 4 and /sys/kernel/tracing is root-only: an unprivileged perf
#     cannot open any event ("Failure to open any events"), so this runs perf under passwordless
#     sudo, which hosted runners provide.
#   - `--overwrite --switch-output=signal` is perf's flight-recorder mode: the mmap ring is
#     overwritten in place, SIGUSR2 writes a snapshot `perf.data.<timestamp>`, and so does the
#     recorder's exit (so a test binary that is killed by the hang watchdog still leaves its last
#     window).
#   - `perf script` runs on the runner, where the binary still is, so frames are symbolised; the raw
#     perf.data needs that binary to be read, which is why only the text goes into the artifact.
#
# Never fails the job: bounded commands, every failure reported and skipped. NOT `set -e`.
set -uo pipefail

PERF="${PERF_RING_PERF:-perf}"
SUDO="${PERF_RING_SUDO-sudo}"                       # empty in tests
TARGET="${PERF_RING_TARGET:-test.kexe}"             # exact process name of a K/N test binary
POLL_SECONDS="${PERF_RING_POLL_SECONDS:-2}"
BUFFER="${PERF_RING_BUFFER:-4M}"                    # per-CPU ring; 4 CPUs = 16 MB of RAM
FREQ="${PERF_RING_FREQ:-49}"                        # call-graph samples per second: low on purpose
WINDOW_SECONDS="${PERF_RING_WINDOW_SECONDS:-60}"    # the part of a snapshot kept
CAP_BYTES="${PERF_RING_CAP_BYTES:-20971520}"        # 20 MB compressed per job
KEEP_PIDS="${PERF_RING_KEEP_PIDS:-3}"               # on-disk rings of finished processes

say() { echo "perf-ring: $*" >&2; }

start_recorder() { # <dir> <pid>
  local d="$1/pid-$2"
  mkdir -p "$d"
  # -p follows threads the process creates later; sched tracepoints for who ran / who woke whom,
  # cpu-clock + -g for where the time went.
  nohup $SUDO "$PERF" record --overwrite --switch-output=signal -m "$BUFFER" \
    -e sched:sched_switch -e sched:sched_wakeup -e cpu-clock -F "$FREQ" -g \
    -p "$2" -o "$d/perf.data" >"$d/record.log" 2>&1 </dev/null &
  echo $! >"$d/recorder.pid"
  say "recording pid $2 (comm $TARGET) into $d"
}

do_watch() { # <dir>
  local dir="$1" pid
  while sleep "$POLL_SECONDS"; do
    [ -f "$dir/.stop" ] && return 0
    for pid in $(pgrep -x "$TARGET" 2>/dev/null); do
      [ -d "$dir/pid-$pid" ] || start_recorder "$dir" "$pid" >>"$dir/watch.log" 2>&1
    done
    # Disk: a green run's rings are never read. Keep the newest few finished ones.
    ls -dt "$dir"/pid-* 2>/dev/null | tail -n +$((KEEP_PIDS + 1)) | while IFS= read -r old; do
      pgrep -x "$TARGET" 2>/dev/null | grep -qx "${old##*pid-}" || $SUDO rm -rf "$old"
    done
  done
}

do_start() { # <dir>
  local dir="$1"
  mkdir -p "$dir"
  rm -f "$dir/.stop"
  if ! command -v "$PERF" >/dev/null 2>&1; then say "no perf binary on this runner: ring not armed"; return 0; fi
  if [ -n "$SUDO" ] && ! $SUDO -n true 2>/dev/null; then say "no passwordless sudo: perf_event_paranoid=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null) blocks an unprivileged perf, ring not armed"; return 0; fi
  nohup "$0" watch "$dir" >"$dir/watch.out" 2>&1 </dev/null &
  echo $! >"$dir/watcher.pid"
  disown 2>/dev/null || true
  say "armed: $("$PERF" --version 2>&1 | head -1), perf_event_paranoid=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null), ring $BUFFER per CPU, ${FREQ} Hz call graphs"
}

# <perf.data.snapshot> -> text of the last WINDOW_SECONDS, the events that explain a stall.
# A sample is a header line (`comm tid [cpu] time: event: args`) followed by indented frames.
filter_snapshot() {
  local f="$1" raw max
  raw=$(mktemp)
  # NOT under sudo: perf refuses a file that is neither owned by the user running it nor by root, and
  # collect hands the snapshots to the runner user first (the second hosted-runner probe: "not owned by
  # current user or root"). Reading needs no privilege.
  # Per-type field lists: `trace` (the tracepoint's arguments: prev_comm/prev_state/next_comm, the
  # woken comm and pid) is only valid for tracepoints, and asking for it on the cpu-clock samples makes
  # perf refuse the whole command (seen on the first hosted-runner probe: "produced nothing"). The
  # plain list is the fallback, and perf's own complaint is kept in the log either way.
  if ! "$PERF" script -i "$f" -F trace:comm,tid,cpu,time,event,trace,ip,sym -F sw:comm,tid,cpu,time,event,ip,sym >"$raw" 2>"$raw.err" || [ ! -s "$raw" ]; then
    say "perf script with per-type fields failed ($(head -c 200 "$raw.err" | tr '\n' ' ')); retrying with plain fields"
    "$PERF" script -i "$f" -F comm,tid,cpu,time,event,ip,sym >"$raw" 2>"$raw.err"
  fi
  [ -s "$raw" ] || say "perf script produced no output: $(head -c 300 "$raw.err" | tr '\n' ' ')"
  max=$(awk '/^[^ \t]/ && match($0, /[0-9]+\.[0-9]+:/) { t = substr($0, RSTART, RLENGTH - 1) + 0; if (t > m) m = t } END { printf "%.6f", m }' "$raw")
  awk -v th="$max" -v win="$WINDOW_SECONDS" '
    /^[^ \t]/ { keep = 0; if (match($0, /[0-9]+\.[0-9]+:/)) keep = ((substr($0, RSTART, RLENGTH - 1) + 0) >= th - win) }
    keep' "$raw"
  rm -f "$raw" "$raw.err"
}

do_collect() { # <dir> <out>
  local dir="$1" out="$2" p total=0 kept=0 cut=0 f gz size
  [ -d "$dir" ] || { say "nothing recorded"; return 0; }
  touch "$dir/.stop"
  mkdir -p "$out"
  # A recorder still running (a hang, or a failure while the binary lives): snapshot (SIGUSR2), then stop it
  # with SIGTERM (a background job of a non-interactive shell starts with SIGINT ignored; perf handles TERM). The
  # stop also dumps, so one more snapshot appears; both are kept, newest first.
  for p in "$dir"/pid-*/recorder.pid; do
    [ -f "$p" ] || continue
    local rp; rp=$(cat "$p")
    if pgrep -f "record .*-o ${p%/recorder.pid}/perf.data" >/dev/null 2>&1; then
      $SUDO pkill -USR2 -f "record .*-o ${p%/recorder.pid}/perf.data" 2>/dev/null
      sleep 2
      $SUDO pkill -TERM -f "record .*-o ${p%/recorder.pid}/perf.data" 2>/dev/null
    fi
    : "$rp"
  done
  sleep 2
  $SUDO chown -R "$(id -u):$(id -g)" "$dir" 2>/dev/null
  for f in $(ls -t "$dir"/pid-*/perf.data.* 2>/dev/null); do
    gz="$out/$(basename "$(dirname "$f")")-$(basename "$f").txt.gz"
    filter_snapshot "$f" | gzip -9 >"$gz"
    size=$(wc -c <"$gz" | tr -d ' ')
    if [ "$size" -le 30 ]; then rm -f "$gz"; say "$(basename "$f"): perf script produced nothing"; continue; fi
    if [ $((total + size)) -gt "$CAP_BYTES" ]; then
      rm -f "$gz"; cut=$((cut + 1)); say "TRUNCATED: dropped $(basename "$gz") ($size bytes) to stay under $CAP_BYTES"; continue
    fi
    total=$((total + size)); kept=$((kept + 1))
    say "kept $(basename "$gz"): $size bytes compressed (from $(wc -c <"$f" | tr -d ' ') bytes of perf.data)"
  done
  if [ "$kept" -gt 0 ]; then
    cat >"$out/README.txt" <<'TXT'
perf record ring of a Kotlin/Native test binary (last ~60 s before the snapshot):
sched:sched_switch / sched_wakeup for every thread, cpu-clock samples with call graphs.
  zcat pid-*-perf.data.*.txt.gz | less
  zcat ... | grep sched_switch | awk '{print $1}' | sort | uniq -c | sort -rn      # who ran
  zcat ... | grep -B1 -A12 'cpu-clock'                                           # where the time went
A thread whose last event is a sched_switch away from the CPU, with no later sched_wakeup, was blocked.
TXT
  fi
  say "$kept snapshot(s), $total bytes compressed, $cut dropped"
}

case "${1:-}" in
  start) do_start "${2:?dir}" ;;
  watch) do_watch "${2:?dir}" ;;
  collect) do_collect "${2:?dir}" "${3:?out}" ;;
  *) echo "usage: $0 start|watch|collect ..." >&2; exit 2 ;;
esac
exit 0
