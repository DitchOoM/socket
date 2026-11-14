#!/usr/bin/env bash
#
# Tests for quic-interop-gate.py: a passing cell expected to pass is fine, a regression fails the gate unless
# its retry passed, a known failure that starts passing is stale (a warning by default, a failure with
# --stale=fail), a cell with no expectation and an expected peer that never ran both fail, the migration
# pseudo-test follows the client's summary line, and --record writes today's results back.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../quic-interop-gate.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

runner() { # <file> <abbr:result>...
  local file=$1
  shift
  local tests="" results=""
  for cell in "$@"; do
    local abbr=${cell%%:*} result=${cell#*:}
    tests+="\"$abbr\":{\"name\":\"$abbr\"},"
    results+="{\"abbr\":\"$abbr\",\"result\":$([ "$result" = null ] && echo null || echo "\"$result\"")},"
  done
  printf '{"servers":["s"],"clients":["c"],"tests":{%s},"results":[[%s]],"measurements":[[]]}' "${tests%,}" "${results%,}" >"$file"
}

expect() { # <name> <output> <line-that-must-appear>
  if grep -qF -- "$3" <<<"$2"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL %s: missing line\n    %s\n  in output:\n%s\n' "$1" "$3" "$2"
  fi
}

expect_rc() { # <name> <actual> <expected>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    printf 'FAIL %s: exit %s, expected %s\n' "$1" "$2" "$3"
  fi
}

cat >"$tmp/exp.json" <<'EOF'
{
  "our-client": {"peer": {"handshake": "pass", "transfer": "pass", "zerortt": {"known-failure": "the peer sends no ticket"}, "retry": "unsupported"}},
  "our-server": {"gone": {"handshake": "pass"}},
  "our-client-migrating": {"peer": {"transfer": "pass", "active-migration": "pass"}}
}
EOF

# All as expected.
mkdir "$tmp/ok"
runner "$tmp/ok/our-client__peer.json" handshake:succeeded transfer:succeeded zerortt:failed retry:unsupported
runner "$tmp/ok/our-client-migrating__peer.json" transfer:succeeded
echo "MIGRATION-SUMMARY attempted=6 succeeded=6 consecutive=6 firstUnmoved=none" >"$tmp/ok/our-client-migrating__peer.summary"
runner "$tmp/ok/our-server__gone.json" handshake:succeeded
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/ok")"
expect_rc "as expected passes" $? 0
expect "as expected says zero failing" "$out" "0 failing the gate"

# A regression, and the retry list names it.
mkdir "$tmp/reg"
cp "$tmp/ok/"* "$tmp/reg/"
runner "$tmp/reg/our-client__peer.json" handshake:succeeded transfer:failed zerortt:failed retry:unsupported
out="$(python3 "$script" retry "$tmp/exp.json" our-client peer "$tmp/reg/our-client__peer.json")"
expect "retry lists only the expected pass that failed" "$out" "transfer"
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/reg")"
expect_rc "a regression fails" $? 1
expect "a regression is named" "$out" "| REGRESSION | our-client | peer | transfer | failed | pass |"

# The retry passed: accepted and reported.
runner "$tmp/reg/our-client__peer.retry" transfer:succeeded
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/reg")"
expect_rc "a passing retry is accepted" $? 0
expect "a passing retry is reported" "$out" "| passed on retry | our-client | peer | transfer | retried | pass |"

# A known failure that passes is stale: a warning, or a failure with --stale=fail.
mkdir "$tmp/stale"
cp "$tmp/ok/"* "$tmp/stale/"
runner "$tmp/stale/our-client__peer.json" handshake:succeeded transfer:succeeded zerortt:succeeded retry:unsupported
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/stale")"
expect_rc "stale warns by default" $? 0
expect "stale is named" "$out" "| STALE | our-client | peer | zerortt | succeeded | known-failure | the peer sends no ticket |"
python3 "$script" judge "$tmp/exp.json" "$tmp/stale" --stale=fail >/dev/null
expect_rc "stale fails when asked" $? 1

# A cell with no expectation, and an expected peer that never ran.
mkdir "$tmp/new"
cp "$tmp/ok/"* "$tmp/new/"
rm "$tmp/new/our-server__gone.json"
runner "$tmp/new/our-server__fresh.json" handshake:succeeded
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/new")"
expect_rc "new and missing fail" $? 1
expect "a new cell is named" "$out" "| NEW | our-server | fresh | handshake | succeeded | absent |"
expect "a missing peer is named" "$out" "| MISSING | our-server | gone | * | not run |"

# The migration pseudo-test follows the summary: a refused move fails it, no summary fails it.
mkdir "$tmp/mig"
cp "$tmp/ok/"* "$tmp/mig/"
echo "MIGRATION-SUMMARY attempted=6 succeeded=1 consecutive=1 firstUnmoved=move 2: NoSpareConnectionId" >"$tmp/mig/our-client-migrating__peer.summary"
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/mig")"
expect_rc "a refused move fails the migration cell" $? 1
expect "the refusal is shown" "$out" "| REGRESSION | our-client-migrating | peer | active-migration | failed | pass | MIGRATION-SUMMARY attempted=6 succeeded=1"
rm "$tmp/mig/our-client-migrating__peer.summary"
out="$(python3 "$script" judge "$tmp/exp.json" "$tmp/mig")"
expect "no summary is a failed migration cell" "$out" "no MIGRATION-SUMMARY"

# --record writes today's results, keeping the reason of a known failure that still fails.
python3 "$script" judge "$tmp/exp.json" "$tmp/reg" --record="$tmp/recorded.json" >/dev/null
expect_rc "record never fails" $? 0
expect "record keeps a known failure's reason" "$(cat "$tmp/recorded.json")" '"known-failure": "the peer sends no ticket"'
expect "record writes a pass" "$(cat "$tmp/recorded.json")" '"handshake": "pass"'

# A native lane's cells carry a lane prefix; a run of one lane is not judged MISSING for another lane's rows.
mkdir "$tmp/lane"
runner "$tmp/lane/macos-arm64-our-client__quic-go.json" handshake:succeeded
runner "$tmp/lane/macos-arm64-our-client-migrating__quic-go.json" transfer:succeeded
echo "MIGRATION-SUMMARY attempted=6 succeeded=6 consecutive=6 firstUnmoved=none" >"$tmp/lane/macos-arm64-our-client-migrating__quic-go.summary"
cat >"$tmp/lane-exp.json" <<'EOF2'
{
  "our-client": {"peer": {"handshake": "pass"}},
  "macos-arm64-our-client": {"quic-go": {"handshake": "pass"}},
  "macos-arm64-our-client-migrating": {"quic-go": {"transfer": "pass", "active-migration": "pass"}}
}
EOF2
out="$(python3 "$script" judge "$tmp/lane-exp.json" "$tmp/lane")"
expect_rc "a lane's run is judged on its own rows" $? 0
expect "a lane's run counts its cells" "$out" "3 cells; 0 failing the gate"

echo "quic-interop-gate: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
