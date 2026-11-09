#!/usr/bin/env bash
#
# Tests for bench-delta.py: rows are matched by name + params, the direction of "worse" follows the
# JMH mode, a benchmark only one side has shows "-", and a base of "-" says there is nothing to compare.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../bench-delta.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

sample() { # <bench> <size> <p50> <p99>
  printf '{"benchmark":"com.ditchoom.socket.benchmark.%s","mode":"sample","params":{"size":"%s"},"primaryMetric":{"score":%s,"scoreUnit":"us/op","scorePercentiles":{"50.0":%s,"99.0":%s}}}' "$1" "$2" "$3" "$3" "$4"
}
thrpt() { # <bench> <score>
  printf '{"benchmark":"com.ditchoom.socket.benchmark.%s","mode":"thrpt","primaryMetric":{"score":%s,"scoreUnit":"ops/s"}}' "$1" "$2"
}
avgt() { # <bench> <score>
  printf '{"benchmark":"com.ditchoom.socket.benchmark.%s","mode":"avgt","primaryMetric":{"score":%s,"scoreUnit":"ns/op"}}' "$1" "$2"
}

echo "[$(sample QuicLongLivedStreamRoundTrip.roundTrip 64 100 200),$(thrpt QuicBulkThroughput.mibPerSecond 100),$(avgt VarInt.encodeDecode 10),$(avgt Gone.bench 5)]" >"$tmp/base.json"
echo "[$(sample QuicLongLivedStreamRoundTrip.roundTrip 64 150 190),$(thrpt QuicBulkThroughput.mibPerSecond 80),$(avgt VarInt.encodeDecode 9),$(avgt New.bench 7)]" >"$tmp/head.json"

expect() { # <name> <output> <line-that-must-appear>
  if grep -qF -- "$3" <<<"$2"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL %s: missing line\n    %s\n  in output:\n%s\n' "$1" "$3" "$2"
  fi
}

out="$(python3 "$script" "T" "$tmp/base.json" "$tmp/head.json")"
expect "latency up is flagged" "$out" "| QuicLongLivedStreamRoundTrip.roundTrip (size=64) p50 | us/op | 100.0 | 150.0 | +50.0% ⚠️ |"
expect "latency down is not" "$out" "| QuicLongLivedStreamRoundTrip.roundTrip (size=64) p99 | us/op | 200.0 | 190.0 | -5.0% |"
expect "throughput down is flagged, in MiB/s" "$out" "| QuicBulkThroughput.mibPerSecond | MiB/s | 100.0 | 80.0 | -20.0% ⚠️ |"
expect "avgt down is better" "$out" "| VarInt.encodeDecode | ns/op | 10.0 | 9.000 | -10.0% |"
expect "head-only row" "$out" "| New.bench | ns/op | - | 7.000 | - |"
expect "base-only row" "$out" "| Gone.bench | ns/op | 5.000 | - | - |"

out="$(python3 "$script" "T" - "$tmp/head.json")"
expect "no base" "$out" "The base commit has no JVM benchmarks"
expect "no base row" "$out" "| VarInt.encodeDecode | ns/op | - | 9.000 | - |"

out="$(python3 "$script" "T" "$tmp/base.json" "$tmp/head.json" 60)"
expect "custom flag threshold" "$out" "| QuicLongLivedStreamRoundTrip.roundTrip (size=64) p50 | us/op | 100.0 | 150.0 | +50.0% |"

echo "bench-delta: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
