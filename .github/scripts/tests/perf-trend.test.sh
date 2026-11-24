#!/usr/bin/env bash
#
# Tests for perf-trend.py: merge joins the tests' JSONL records with the run's SHA; compare flags a number that
# crept above the median of earlier runs by more than both thresholds, leaves a flat or noisy series alone, says
# "no history" when there is too little, and fails only in --mode fail.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../perf-trend.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

expect() { # <name> <output> <text that must appear>
  if grep -qF -- "$3" <<<"$2"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL %s: missing\n    %s\n  in output:\n%s\n' "$1" "$3" "$2"
  fi
}

refuse() { # <name> <output> <text that must not appear>
  if grep -qF -- "$3" <<<"$2"; then
    fail=$((fail + 1))
    printf 'FAIL %s: unexpected\n    %s\n  in output:\n%s\n' "$1" "$3" "$2"
  else
    pass=$((pass + 1))
  fi
}

record() { # <test> <metric> <value> <gate>
  printf '{"test":"%s","metric":"%s","value":%s,"gate":%s,"unit":"per-op","os":"Linux","arch":"amd64","jdk":"21","backend":"JniQuicheApi","stack":"native"}\n' "$1" "$2" "$3" "$4"
}

run() { # <file> <sha> <clock-reads> <io-sends> <loop-bytes>
  {
    printf '{"sha":"%s","run":"1","records":[' "$2"
    record "T.echo" "clock-reads" "$3" 24 | tr -d '\n'
    printf ','
    record "T.echo" "io-sends" "$4" 2.2 | tr -d '\n'
    printf ','
    record "T.echo" "loop-bytes" "$5" 8 | tr -d '\n'
    printf ']}\n'
  } >"$1"
}

# merge: every JSONL line of every directory, with the run's SHA.
mkdir -p "$tmp/a/build/perf-gates" "$tmp/b/build/perf-gates"
record "A.one" "clock-reads" 21.8 24 >"$tmp/a/build/perf-gates/A.jsonl"
record "A.one" "io-sends" 2.0 2.2 >>"$tmp/a/build/perf-gates/A.jsonl"
record "B.two" "loop-bytes" 3.5 8 >"$tmp/b/build/perf-gates/B.jsonl"
out="$(GITHUB_SHA=abcdef1234567890 GITHUB_RUN_ID=77 python3 "$script" merge --out "$tmp/merged.json" "$tmp/a" "$tmp/b")"
expect "merge counts" "$out" "merged 3 gated numbers at abcdef123456"
merged="$(cat "$tmp/merged.json")"
expect "merge keeps the sha" "$merged" '"sha": "abcdef1234567890"'
expect "merge keeps the run" "$merged" '"run": "77"'
expect "merge keeps a record" "$merged" '"metric": "loop-bytes"'

# A flat series: nothing flagged, exit 0 even in fail mode.
mkdir -p "$tmp/flat"
for i in 1 2 3 4 5; do run "$tmp/flat/$i.json" "s$i" 21.8 2.0 3.5; done
run "$tmp/now-flat.json" "head" 21.9 2.0 3.6
out="$(python3 "$script" compare --current "$tmp/now-flat.json" --history-dir "$tmp/flat" --mode fail)"
rc=$?
expect "flat exits 0" "rc=$rc" "rc=0"
expect "flat compares" "$out" "0 flagged, 0 without history, 3 compared"
refuse "flat flags nothing" "$out" "::warning"
expect "flat row" "$out" "| T.echo clock-reads | Linux amd64 JDK 21 JniQuicheApi native | 21.900 | 24.000 | 21.800 | +0.5% |  |"

# A creeping series: each run a little higher, the head well above the median, under its gate.
mkdir -p "$tmp/creep"
v=(20.0 20.4 20.8 21.2 21.6)
for i in 0 1 2 3 4; do run "$tmp/creep/$i.json" "s$i" "${v[$i]}" 2.0 3.5; done
run "$tmp/now-creep.json" "head" 22.9 2.0 3.5
out="$(python3 "$script" compare --current "$tmp/now-creep.json" --history-dir "$tmp/creep" --summary "$tmp/summary.md")"
rc=$?
expect "creep warns but exits 0" "rc=$rc" "rc=0"
expect "creep flagged" "$out" "::warning title=perf trend: T.echo clock-reads::Linux amd64 JDK 21 JniQuicheApi native: 22.900 against a median of 20.800 (+10.1%)"
expect "creep row first" "$(sed -n '/^| Gate/,$p' "$tmp/summary.md" | sed -n 3p)" "| T.echo clock-reads |"
expect "creep summary written" "$(cat "$tmp/summary.md")" "## Performance gates: trend"
out="$(python3 "$script" compare --current "$tmp/now-creep.json" --history-dir "$tmp/creep" --mode fail)"
rc=$?
expect "creep fails in fail mode" "rc=$rc" "rc=1"

# A rise past the fraction but not the absolute floor (a near-zero count) is not flagged; nor one past the floor only.
mkdir -p "$tmp/small"
for i in 1 2 3; do run "$tmp/small/$i.json" "s$i" 21.8 0.01 900; done
run "$tmp/now-small.json" "head" 21.8 0.04 940
out="$(python3 "$script" compare --current "$tmp/now-small.json" --history-dir "$tmp/small" --mode fail)"
rc=$?
expect "small rise exits 0" "rc=$rc" "rc=0"
refuse "near-zero count not flagged" "$out" "io-sends::"
refuse "4% of a byte count not flagged" "$out" "loop-bytes::"

# Missing history: too few runs, an empty directory, a metric new in this run.
mkdir -p "$tmp/two" "$tmp/none"
for i in 1 2; do run "$tmp/two/$i.json" "s$i" 10 1 1; done
out="$(python3 "$script" compare --current "$tmp/now-creep.json" --history-dir "$tmp/two" --mode fail)"
rc=$?
expect "two runs is no history" "$out" "no history (2)"
expect "no history never fails" "rc=$rc" "rc=0"
out="$(python3 "$script" compare --current "$tmp/now-creep.json" --history-dir "$tmp/none")"
expect "empty history says so" "$out" "No earlier run has the perf-gates artifact"
expect "empty history counts" "$out" "0 flagged, 3 without history, 3 compared"
out="$(python3 "$script" compare --current "$tmp/merged.json" --history-dir "$tmp/flat")"
expect "a new metric has no history" "$out" "| A.one clock-reads | Linux amd64 JDK 21 JniQuicheApi native | 21.800 | 24.000 | - | no history (0) |  |"

# Without a history source compare refuses to guess.
python3 "$script" compare --current "$tmp/merged.json" >/dev/null 2>&1
rc=$?
expect "compare needs a history source" "rc=$rc" "rc=2"

echo "perf-trend: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
