#!/usr/bin/env bash
#
# Tests for linux-native-crash.sh with a fake gdb (a real one needs Linux and ptrace rights) and real
# processes: a binary that dies on SIGSEGV after "dumping core" into the routed directory, one that
# outlives its budget, one that passes. Checks exit status, backtraces, hang stacks and the cap.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../linux-native-crash.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
check() { if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; fail=$((fail + 1)); fi; }

cat >"$tmp/fakegdb" <<'GDB'
#!/usr/bin/env bash
case "$*" in
  *--version*) echo "GNU gdb (fake) 15"; exit 0 ;;
  -p*) echo "Thread 1 (LWP 1):"; echo "#0  0x1 in parked_forever ()"; exit 0 ;;
  *) echo "===== crashed thread ====="; echo "#0  0x2 in write_past_the_end ()"; echo "===== registers ====="; echo "rip 0x2"; exit 0 ;;
esac
GDB
chmod +x "$tmp/fakegdb"
cc -o "$tmp/crasher" -x c - <<'C' || { echo "skip: needs a C compiler"; exit 0; }
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
  if (argc > 1 && argv[1][0] == 'c') { /* "dump core" where core_pattern routes it, then die on SIGSEGV */
    char p[4096]; snprintf(p, sizeof p, "%s/core.crasher.%d", getenv("CORES"), getpid());
    FILE *f = fopen(p, "w"); for (int i = 0; i < 4096; i++) fputc(rand() & 0xff, f); fclose(f);
    signal(SIGSEGV, SIG_DFL); raise(SIGSEGV);
  }
  if (argc > 1 && argv[1][0] == 'h') sleep(30);
  return 0;
}
C
export CRASH_GDB="$tmp/fakegdb" CRASH_SUDO="" CRASH_CORE_PATTERN_FILE="$tmp/core_pattern"
d="$tmp/d"

bash "$script" start "$d" >"$tmp/log" 2>&1
grep -qF "$d/cores/core.%e.%p" "$tmp/core_pattern" && grep -q 'armed: GNU gdb (fake)' "$tmp/log"; check "start routes cores into the evidence directory" $?

CORES="$d/cores" bash "$script" run "$d" 20 "$tmp/crasher" crash >"$tmp/log" 2>&1
status=$?
[ "$status" -eq 139 ]; check "a crashing binary's exit status (139) is passed through" $?
ls "$d"/crash-core.crasher.*.txt >/dev/null 2>&1 && grep -q 'write_past_the_end' "$tmp/log"; check "a core becomes backtraces, and the crashed thread is in the log" $?

bash "$script" run "$d" 1 "$tmp/crasher" hang >"$tmp/log" 2>&1 &
runner=$!
for _ in $(seq 1 10); do ls "$d"/hang-crasher-*.txt >/dev/null 2>&1 && break; sleep 1; done
ls "$d"/hang-crasher-*.txt >/dev/null 2>&1 && grep -q 'parked_forever' "$d"/hang-crasher-*.txt; check "a run past its budget gets every thread's stack while it is alive" $?
pkill -f "$tmp/crasher hang" 2>/dev/null; wait "$runner" 2>/dev/null

bash "$script" run "$d" 20 "$tmp/crasher" >"$tmp/log" 2>&1
[ $? -eq 0 ] && ! grep -q '::error' "$tmp/log"; check "a passing run exits 0 and reports nothing" $?

bash "$script" run "$d" 20 sh -c 'kill -SEGV $$' >"$tmp/log" 2>&1
[ $? -eq 139 ] && grep -q 'left no core' "$tmp/log"; check "a signal without a core says the core was refused" $?

bash "$script" collect "$d" "$tmp/out" >"$tmp/log" 2>&1
ls "$tmp/out"/crash-*.txt.gz "$tmp/out"/hang-*.txt.gz "$tmp/out"/core.crasher.*.gz >/dev/null 2>&1; check "collect keeps backtraces, stacks and a core under the cap" $?

rm -rf "$tmp/out2"
CRASH_CAP_BYTES=100 bash "$script" collect "$d" "$tmp/out2" >"$tmp/log" 2>&1
! ls "$tmp/out2"/core.* >/dev/null 2>&1 && grep -q 'dropped core.crasher' "$tmp/out2/dropped.txt" && ls "$tmp/out2"/crash-*.txt.gz >/dev/null 2>&1; check "over the cap the core is dropped, said so, and its backtraces stay" $?

echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
