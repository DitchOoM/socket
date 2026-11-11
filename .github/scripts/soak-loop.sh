#!/usr/bin/env bash
#
# Runs one Kotlin/Native test binary again and again, whole, until a time budget is spent or one run
# fails, and keeps the full output of the run that failed.
#
#   soak-loop.sh <test.kexe> <ktest-filter> <budget-seconds> <per-run-timeout-seconds> <out-dir>
#
# A run fails when the binary exits non-zero, outlives the per-run timeout (a hang), or runs no test
# at all: --ktest_filter exits 0 when it matches nothing, so "no [ RUN ] line" is treated as a failure,
# not a pass.
#
# Writes to <out-dir>:
#   result.txt      one line: `result=<ok|fail|hang|vacuous> iterations=<n> seconds=<s> stall_runs=<k>`
#   failure.log     the complete output of the failing run (absent when every run passed)
#   stall-lines.log every `[stall]` / `[nw-udp]` line of a run that passed, prefixed with its iteration
# Exit status: 0 when the budget was spent with every run passing, 1 otherwise, 2 on bad usage.
#
# The per-run timeout is a perl alarm, because macOS has no timeout(1) and both runner images have perl.
set -uo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <test.kexe> <ktest-filter> <budget-seconds> <per-run-timeout-seconds> <out-dir>" >&2
  exit 2
fi
bin="$1" filter="$2" budget="$3" run_timeout="$4" out="$5"
for n in "$budget" "$run_timeout"; do
  case "$n" in '' | *[!0-9]*) echo "not a number: '$n'" >&2; exit 2 ;; esac
done
[ -x "$bin" ] || { echo "not an executable: $bin" >&2; exit 2; }

mkdir -p "$out"
: > "$out/stall-lines.log"
log="$out/current.log"
start=$(date +%s)
i=0
stall_runs=0

finish() { # <result> <exit-status>
  printf 'result=%s iterations=%d seconds=%d stall_runs=%d\n' "$1" "$i" "$(($(date +%s) - start))" "$stall_runs" > "$out/result.txt"
  cat "$out/result.txt"
  exit "$2"
}

while [ "$(($(date +%s) - start))" -lt "$budget" ]; do
  i=$((i + 1))
  perl -e 'alarm shift; exec @ARGV' "$run_timeout" "$bin" "--ktest_filter=$filter" > "$log" 2>&1
  rc=$?
  if [ "$rc" -ne 0 ]; then
    mv "$log" "$out/failure.log"
    # 142 = 128 + SIGALRM: the per-run timeout fired.
    if [ "$rc" -eq 142 ]; then
      echo "iteration $i HUNG (killed after ${run_timeout}s); full output in $out/failure.log"
      finish hang 1
    fi
    echo "iteration $i FAILED (exit $rc); full output in $out/failure.log"
    finish fail 1
  fi
  if ! grep -q '^\[ RUN      \]' "$log"; then
    mv "$log" "$out/failure.log"
    echo "iteration $i ran no test: filter '$filter' matched nothing, which is not a pass"
    finish vacuous 1
  fi
  if grep -qE '\[stall\]|\[nw-udp\]' "$log"; then
    stall_runs=$((stall_runs + 1))
    grep -E '\[stall\]|\[nw-udp\]' "$log" | sed "s/^/iter $i: /" >> "$out/stall-lines.log"
  fi
done
rm -f "$log"
finish ok 0
