#!/usr/bin/env bash
#
# Tests for deep-checks-report.sh against canned job and artifact lists.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../deep-checks-report.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
export GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=42

ok() { # <name> <condition-status>
  if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; cat "$tmp/out"; fail=$((fail + 1)); fi
}

jobs_all_green='[{"name":"Lincheck deep","conclusion":"success","html_url":"u1"},{"name":"Report","conclusion":null,"html_url":"u0"}]'
jobs_red='[{"name":"Lincheck deep","conclusion":"success","html_url":"u1"},{"name":"Fuzz deep (conn-recv)","conclusion":"failure","html_url":"u2"},{"name":"Soak deep (Linux x64)","conclusion":"skipped","html_url":"u3"},{"name":"Report","conclusion":null,"html_url":"u0"}]'
artifacts='[{"name":"deep-fuzz-crashes-conn-recv","id":7},{"name":"unrelated","id":8}]'

echo "$jobs_all_green" > "$tmp/j"; echo '[]' > "$tmp/a"; : > "$tmp/gho"
GITHUB_OUTPUT="$tmp/gho" "$script" "$tmp/j" "$tmp/a" Report > "$tmp/out"
grep -q 'All 1 deep checks passed' "$tmp/out" && grep -q '^failed=0$' "$tmp/gho" && ! grep -q Report "$tmp/out"; ok "a green night counts zero failures and omits the report job" $?

echo "$jobs_red" > "$tmp/j"; echo "$artifacts" > "$tmp/a"; : > "$tmp/gho"
GITHUB_OUTPUT="$tmp/gho" "$script" "$tmp/j" "$tmp/a" Report > "$tmp/out"
grep -q '^failed=2$' "$tmp/gho"; ok "a failure and a job that did not run both count" $?
grep -q '| Fuzz deep (conn-recv) | FAILED (failure) |' "$tmp/out" && grep -q '| Soak deep (Linux x64) | NOT RUN |' "$tmp/out"; ok "each failing check is named with its result" $?
grep -q 'actions/runs/42/artifacts/7' "$tmp/out" && ! grep -q unrelated "$tmp/out"; ok "only deep-* artifacts are linked, by run" $?

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
