#!/usr/bin/env bash
# Job summary for native-compare.yaml: native-compare-summary.sh <title> <scoreboard.md> <trace dir>
# The scoreboard, then every trace's on-CPU shares per cost bucket (benchmarks/native-compare/trace_buckets.py).
set -uo pipefail
title=$1
board=$2
traces=$3
{
  echo "## $title"
  [ -f "$board" ] && grep -v '^round ' "$board"
  echo
  shopt -s nullglob
  for t in "$traces"/*; do
    python3 -I benchmarks/native-compare/trace_buckets.py "$t"
  done
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
