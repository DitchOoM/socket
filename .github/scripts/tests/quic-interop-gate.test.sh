#!/usr/bin/env bash
#
# Tests for quic-interop-gate.py: a passing cell expected to pass is fine, a regression fails the gate unless
# its retry passed, a known failure that starts passing is stale (a warning by default, a failure with
# --stale=fail), a cell with no expectation and an expected peer that never ran both fail, the migration
# pseudo-test follows the client's summary line, --record writes today's results back, and a failure whose
# expectation carries a signature is accepted only when the cell's evidence shows it.
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

# Signatures: a known failure or flaky cell that carries one is accepted only when the cell's evidence shows
# that failure; any other failure is a regression, and an entry the gate cannot judge stops it.
evidence() { # <file> <direction> <peer> <test> <tests-json-body>
  printf '{"direction":"%s","peer":"%s","tests":{"%s":%s}}' "$2" "$3" "$4" "$5" >"$1"
}
conn() { # <initial limit> <max_streams sent> <peer streams opened> <close code or none> <remote disable_active_migration>
  local closes='[]'
  [ "$4" != none ] && closes="[{\"space\":\"transport\",\"code\":$4}]"
  printf '{"qlog":"q.sqlog","local_initial_max_streams_bidi":%s,"max_streams_bidi_sent":%s,"peer_bidi_streams_opened":%s,"local_closes":%s,"remote_disable_active_migration":%s}' "$1" "$2" "$3" "$closes" "$5"
}
cat >"$tmp/sig-exp.json" <<'EOF2'
{
  "our-server": {"x": {"multiplexing": {"known-failure": {"why": "peer: opens past our limit", "signature": {"kind": "peer-exceeded-stream-limit"}}},
                       "transfer": {"flaky": "cause not yet determined"}}},
  "our-server-raised-streams": {"x": {"multiplexing": "pass"}},
  "our-client-migrating": {"p": {"transfer": {"flaky": {"why": "peer: crashes", "signature": {"kind": "peer-process-died", "signal": "SIGSEGV"}}},
                                 "active-migration": {"known-failure": {"why": "peer: forbids", "signature": {"kind": "migration-refused-peer-forbids"}}}}}
}
EOF2
mkdir "$tmp/sig"
runner "$tmp/sig/our-server__x.json" multiplexing:failed transfer:failed
runner "$tmp/sig/our-server-raised-streams__x.json" multiplexing:succeeded
runner "$tmp/sig/our-client-migrating__p.json" transfer:failed
echo "MIGRATION-SUMMARY attempted=6 succeeded=0 consecutive=0 firstUnmoved=move 1: PeerForbids attempts=1" >"$tmp/sig/our-client-migrating__p.summary"
evidence "$tmp/sig/our-server__x.evidence.json" our-server x multiplexing "{\"connections\":[$(conn 100 0 101 4 false)],\"exits\":{\"client\":0},\"migrations\":[]}"
evidence "$tmp/sig/our-client-migrating__p.evidence.json" our-client-migrating p transfer \
  "{\"connections\":[$(conn 100 0 0 none true)],\"exits\":{\"server\":139},\"migrations\":[{\"attempted\":6,\"succeeded\":0,\"first_unmoved\":\"PeerForbids\"}]}"
out="$(python3 "$script" judge "$tmp/sig-exp.json" "$tmp/sig" --stale=fail)"
expect_rc "failures that match their signatures pass" $? 0
expect "a matched stream-limit signature is shown" "$out" "| known-failure (signature) | our-server | x | multiplexing | failed | known-failure | peer opened 101 bidi streams, allowed 100; we closed 0x4 |"
expect "a matched crash signature is shown" "$out" "| flaky (signature) | our-client-migrating | p | transfer | failed | flaky | peer server exited with 139 (SIGSEGV) |"
expect "a matched PeerForbids signature is shown" "$out" "| known-failure (signature) | our-client-migrating | p | active-migration | failed | known-failure | peer sets disable_active_migration; our first move: PeerForbids |"
expect "a failure without a signature is reported unproven" "$out" "| flaky (unproven) | our-server | x | transfer | failed | flaky | cause not yet determined |"
expect "the unproven count is shown" "$out" "1 unproven expectations (any failure accepted)"
expect "each unproven entry is listed by name" "$out" "| our-server | x | transfer | flaky | cause not yet determined |"
expect "the raised-streams direction is a cell" "$out" "5 cells; 0 failing the gate"

# The same failures, shown differently: each is a regression.
evidence "$tmp/sig/our-server__x.evidence.json" our-server x multiplexing "{\"connections\":[$(conn 100 150 101 4 false)],\"exits\":{\"client\":0},\"migrations\":[]}"
evidence "$tmp/sig/our-client-migrating__p.evidence.json" our-client-migrating p transfer \
  "{\"connections\":[$(conn 100 0 0 none false)],\"exits\":{\"server\":1},\"migrations\":[{\"attempted\":6,\"succeeded\":0,\"first_unmoved\":\"PeerForbids\"}]}"
out="$(python3 "$script" judge "$tmp/sig-exp.json" "$tmp/sig" --stale=fail)"
expect_rc "failures that do not match their signatures fail" $? 1
expect "a stream-limit close within what we allowed is a regression" "$out" "| REGRESSION | our-server | x | multiplexing | failed | known-failure | failed, but not as peer-exceeded-stream-limit"
expect "a peer exit that is not the signal is a regression" "$out" "| REGRESSION | our-client-migrating | p | transfer | failed | flaky | failed, but not as peer-process-died"
expect "PeerForbids without the peer's parameter is a regression" "$out" "| REGRESSION | our-client-migrating | p | active-migration | failed | known-failure | failed, but not as migration-refused-peer-forbids"
rm "$tmp/sig/our-server__x.evidence.json"
out="$(python3 "$script" judge "$tmp/sig-exp.json" "$tmp/sig" --stale=fail)"
expect "a signature with no evidence is a regression" "$out" "no evidence to check the peer-exceeded-stream-limit signature against"

# A known failure with a signature that passes is stale, and fails the gate.
runner "$tmp/sig/our-server__x.json" multiplexing:succeeded transfer:succeeded
python3 "$script" judge "$tmp/sig-exp.json" "$tmp/sig" --stale=fail >/dev/null
expect_rc "a signed known failure that passes is stale" $? 1

# An entry the gate cannot judge stops it.
sed 's/"peer-exceeded-stream-limit"/"something-else"/' "$tmp/sig-exp.json" >"$tmp/bad-exp.json"
python3 "$script" judge "$tmp/bad-exp.json" "$tmp/sig" >/dev/null 2>"$tmp/bad.err"
expect_rc "an unknown signature kind stops the gate" $? 1
expect "the unknown kind is named" "$(cat "$tmp/bad.err")" "unknown signature kind 'something-else'"
sed 's/"signal": "SIGSEGV"/"signal": "SIGWHAT"/' "$tmp/sig-exp.json" >"$tmp/bad-exp.json"
python3 "$script" judge "$tmp/bad-exp.json" "$tmp/sig" >/dev/null 2>"$tmp/bad.err"
expect_rc "an unknown signal stops the gate" $? 1

# A FRAME_ENCODING_ERROR close counts only against a peer whose source connection id is zero-length.
cat >"$tmp/cid-exp.json" <<'EOF2'
{"our-server": {"k": {"handshake": {"known-failure": {"why": "peer: sends NEW_CONNECTION_ID with a zero-length id", "signature": {"kind": "frame-encoding-error-from-zero-length-cid-peer"}}}}}}
EOF2
mkdir "$tmp/cid"
runner "$tmp/cid/our-server__k.json" handshake:failed
cid_conn() { # <close code> <peer scid lengths json>
  printf '{"qlog":"q","local_initial_max_streams_bidi":100,"max_streams_bidi_sent":0,"peer_bidi_streams_opened":1,"remote_disable_active_migration":false,"local_closes":[{"space":"transport","code":%s}],"peer_source_cid_lengths":%s}' "$1" "$2"
}
evidence "$tmp/cid/our-server__k.evidence.json" our-server k handshake "{\"connections\":[$(cid_conn 7 '[0]')],\"exits\":{},\"migrations\":[]}"
out="$(python3 "$script" judge "$tmp/cid-exp.json" "$tmp/cid" --stale=fail)"
expect_rc "a 0x7 close against a zero-length-id peer matches" $? 0
expect "the zero-length-id match is shown" "$out" "peer's source connection id is zero-length; we closed 0x7 (FRAME_ENCODING_ERROR)"
evidence "$tmp/cid/our-server__k.evidence.json" our-server k handshake "{\"connections\":[$(cid_conn 7 '[8]')],\"exits\":{},\"migrations\":[]}"
python3 "$script" judge "$tmp/cid-exp.json" "$tmp/cid" --stale=fail >/dev/null
expect_rc "a 0x7 close against a peer with connection ids is a regression" $? 1
evidence "$tmp/cid/our-server__k.evidence.json" our-server k handshake "{\"connections\":[$(cid_conn 10 '[0]')],\"exits\":{},\"migrations\":[]}"
python3 "$script" judge "$tmp/cid-exp.json" "$tmp/cid" --stale=fail >/dev/null
expect_rc "another close code against a zero-length-id peer is a regression" $? 1

# The evidence extractor reads our endpoint's qlog and the runner's output.
extract="$here/../quic-interop-evidence.py"
d="$tmp/logs/ditchoom_x/multiplexing"
mkdir -p "$d/server/qlog"
{
  printf '{"file_schema":"urn:ietf:params:qlog:file:sequential"}\n'
  printf '{"name":"quic:parameters_set","data":{"initiator":"local","initial_max_streams_bidi":100}}\n'
  printf '{"name":"quic:parameters_set","data":{"initiator":"remote","disable_active_migration":true}}\n'
  printf '{"name":"quic:packet_received","data":{"header":{"packet_type":"initial","scil":0},"frames":[]}}\n'
  printf '{"name":"quic:packet_received","data":{"frames":[{"frame_type":"stream","stream_id":400},{"frame_type":"stream","stream_id":401}]}}\n'
  printf '{"name":"quic:packet_sent","data":{"frames":[{"frame_type":"max_streams","stream_type":"bidirectional","maximum":90}]}}\n'
  printf '{"name":"quic:packet_sent","data":{"frames":[{"frame_type":"connection_close","error_space":"transport","error_code":4}]}}\n'
  printf '{"name":"quic:packet_sent","data":{"frames":[{"frame_type":"connection_close","error_space":"transport","error_code":4}]}}\n'
} >"$d/server/qlog/quiche-server-1.sqlog"
printf "2026-11-14 13:40:27,246 Container exits: b'client exited with code 0'\n" >"$d/output.txt"
python3 -I "$extract" "$tmp/logs" our-server x "$tmp/ev.json"
expect_rc "the extractor runs" $? 0
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["multiplexing"]; c=t["connections"][0]; print(c["local_initial_max_streams_bidi"], c["max_streams_bidi_sent"], c["peer_bidi_streams_opened"], c["local_closes"], c["remote_disable_active_migration"], t["exits"], c["peer_source_cid_lengths"])' "$tmp/ev.json")"
expect "the extractor reads limits, streams, one close per code and exits" "$ev" "100 90 101 [{'code': 4, 'space': 'transport'}] True {'client': 0} [0]"

echo "quic-interop-gate: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
