#!/usr/bin/env bash
#
# Tests for linux-perf-ring.sh with a fake `perf` (a real one needs root and a Linux kernel) and a
# real process named test.kexe: the watcher attaches to it, collect snapshots a live recorder, keeps
# the last window, compresses, caps, and says what it dropped.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../linux-perf-ring.sh"
tmp="$(mktemp -d)"
pids=""
trap 'for p in $pids; do kill "$p" 2>/dev/null; done; pkill -f "$tmp/fakeperf" 2>/dev/null; rm -rf "$tmp"' EXIT
pass=0
fail=0
check() { if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; fail=$((fail + 1)); fi; }

# fake perf: `record` waits for its target, writes a snapshot on SIGUSR2 and one on exit/SIGINT;
# `script` prints the snapshot (which already is perf-script text).
cat >"$tmp/fakeperf" <<'PERF'
#!/usr/bin/env bash
case "$1" in
  --version) echo "perf version fake"; exit 0 ;;
  script) while [ $# -gt 0 ]; do [ "$1" = -i ] && { cat "$2"; exit 0; }; shift; done ;;
  record)
    out=""; target=""
    while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -p) target="$2"; shift ;; esac; shift; done
    n=0
    snap() { n=$((n + 1)); { printf 'thr 100 [000] 1.000000: sched:sched_switch: prev_comm=thr next_comm=swapper\n\t0 old_frame\n\n'
      printf 'thr 100 [000] 100.000000: sched:sched_switch: prev_comm=thr next_comm=blocked_thread\n\t0 main\n\n'
      printf 'thr 101 [001] 130.000000: cpu-clock:\n\t0 burn_cpu_here\n\n'; } >"$out.2026$n"; }
    trap 'snap' USR2
    trap 'snap; exit 0' INT TERM
    while kill -0 "$target" 2>/dev/null; do sleep 0.2; done
    snap; exit 0 ;;
esac
PERF
chmod +x "$tmp/fakeperf"
# a real process whose NAME is test.kexe (a copied `sleep` is killed by macOS code signing)
printf '#include <unistd.h>\nint main(void){sleep(60);return 0;}\n' >"$tmp/k.c"
cc -o "$tmp/test.kexe" "$tmp/k.c" || { echo "skip: needs a C compiler"; exit 0; }
export PERF_RING_PERF="$tmp/fakeperf" PERF_RING_SUDO="" PERF_RING_POLL_SECONDS=1 PERF_RING_WINDOW_SECONDS=60

# --- no perf: armed nothing, said so
PERF_RING_PERF="$tmp/missing" bash "$script" start "$tmp/d0" >"$tmp/log" 2>&1
grep -q 'no perf binary' "$tmp/log" && [ ! -f "$tmp/d0/watcher.pid" ]; check "no perf on the runner: not armed, and it says so" $?

# --- arm, run a test binary, watch it get recorded
bash "$script" start "$tmp/d" >"$tmp/log" 2>&1
grep -q 'armed: perf version fake' "$tmp/log" && [ -f "$tmp/d/watcher.pid" ]; check "start arms the watcher" $?
"$tmp/test.kexe" &
kexe=$!; pids="$kexe"
for _ in $(seq 1 15); do [ -d "$tmp/d/pid-$kexe" ] && break; sleep 1; done
[ -d "$tmp/d/pid-$kexe" ] && grep -q "recording pid $kexe" "$tmp/d/watch.log"; check "the watcher attaches to a running test.kexe" $?
sleep 1
pgrep -f "fakeperf record.*-p $kexe" >/dev/null; check "one recorder runs for it" $?

# --- a hung run: collect snapshots the LIVE recorder, then stops it
bash "$script" collect "$tmp/d" "$tmp/out" >"$tmp/log" 2>&1
ls "$tmp/out"/pid-"$kexe"-perf.data.*.txt.gz >/dev/null 2>&1; check "collect snapshots a live recorder and writes compressed text" $?
f="$(ls "$tmp/out"/pid-*.txt.gz | head -1)"
gunzip -c "$f" | grep -q 'blocked_thread' && gunzip -c "$f" | grep -q 'burn_cpu_here'; check "the filtered text carries the sched switch and the sampled frame" $?
! gunzip -c "$f" | grep -q 'old_frame'; check "events older than the window are dropped" $?
[ -s "$tmp/out/README.txt" ]; check "a README says how to read it" $?
! pgrep -f "fakeperf record.*-p $kexe" >/dev/null; check "the recorder is stopped" $?
grep -q 'kept pid-' "$tmp/log"; check "the log names what was kept and its size" $?

# --- cap
PERF_RING_CAP_BYTES=1 bash "$script" collect "$tmp/d" "$tmp/out2" >"$tmp/log" 2>&1
grep -q 'TRUNCATED' "$tmp/log" && [ -z "$(ls "$tmp/out2"/*.gz 2>/dev/null)" ]; check "over the cap snapshots are dropped and the log says so" $?

# --- nothing recorded
bash "$script" collect "$tmp/never" "$tmp/out3" >"$tmp/log" 2>&1
grep -q 'nothing recorded' "$tmp/log"; check "a job that never armed uploads nothing" $?

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
