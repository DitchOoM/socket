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

# A run in which every test was skipped (the peer's image never pulled, so the runner found it not compliant) is the cell
# failing to run, not a result. A test the peer declares unsupported is a verdict: a run of only those ran.
mkdir "$tmp/ran"
runner "$tmp/ran/skipped.json" handshake:null transfer:null
out="$(python3 "$script" ran "$tmp/ran/skipped.json")"
expect_rc "a run in which every test was skipped did not run" $? 1
expect "the skipped tests are counted" "$out" "no test ran: 2 of 2 skipped"
runner "$tmp/ran/unsupported.json" handshake:unsupported transfer:unsupported
python3 "$script" ran "$tmp/ran/unsupported.json" >/dev/null
expect_rc "a run of only unsupported tests ran" $? 0
runner "$tmp/ran/one.json" handshake:failed transfer:null
python3 "$script" ran "$tmp/ran/one.json" >/dev/null
expect_rc "a run with one verdict ran" $? 0

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

# A flaky cell with a pass signature accepts each of its two outcomes only with the cause the evidence shows: out of
# ids at the peer's limit when the peer retired nothing unasked, every move when it retired one unasked.
cat >"$tmp/moves-exp.json" <<'EOF2'
{"our-client-migrating": {"m": {"transfer": "pass",
  "active-migration": {"flaky": {"why": "peer: retires unasked or not",
    "signature": {"kind": "migration-out-of-ids-peer-retired-nothing"},
    "pass-signature": {"kind": "migration-peer-retired-unasked"}}}}}}
EOF2
moves_conn() { # <peer unasked retirements>
  printf '{"qlog":"q.sqlog","remote_active_connection_id_limit":5,"peer_unasked_retirements":%s}' "$1"
}
moves_case() { # <name> <summary> <migrations json> <retirements> <expected rc> <line>
  rm -rf "$tmp/moves" && mkdir "$tmp/moves"
  runner "$tmp/moves/our-client-migrating__m.json" transfer:succeeded
  echo "$2" >"$tmp/moves/our-client-migrating__m.summary"
  evidence "$tmp/moves/our-client-migrating__m.evidence.json" our-client-migrating m transfer \
    "{\"connections\":[$(moves_conn "$4")],\"exits\":{},\"migrations\":[$3]}"
  out="$(python3 "$script" judge "$tmp/moves-exp.json" "$tmp/moves" --stale=fail)"
  expect_rc "$1" $? "$5"
  expect "$1 (shown)" "$out" "$6"
}
four='MIGRATION-SUMMARY attempted=6 succeeded=4 consecutive=4 firstUnmoved=move 5: NoSpareConnectionId attempts=5'
four_json='{"attempted":6,"succeeded":4,"first_unmoved":"NoSpareConnectionId"}'
six='MIGRATION-SUMMARY attempted=6 succeeded=6 consecutive=6 firstUnmoved=none'
six_json='{"attempted":6,"succeeded":6}'
moves_case "four moves with no retirement is the failure fingerprint" "$four" "$four_json" 0 0 \
  "| flaky (signature) | our-client-migrating | m | active-migration | failed | flaky | no unasked RETIRE_CONNECTION_ID from the peer; 4 moves, then NoSpareConnectionId at its active_connection_id_limit 5 |"
moves_case "four moves after an unasked retirement is a regression" "$four" "$four_json" 1 1 \
  "| REGRESSION | our-client-migrating | m | active-migration | failed | flaky | failed, but not as migration-out-of-ids-peer-retired-nothing"
moves_case "every move after an unasked retirement is the pass fingerprint" "$six" "$six_json" 1 0 \
  "| flaky (pass signature) | our-client-migrating | m | active-migration | succeeded | flaky | the peer retired 1 id(s) of ours unasked; 6 of 6 moves |"
moves_case "every move with no retirement is a regression" "$six" "$six_json" 0 1 \
  "| REGRESSION | our-client-migrating | m | active-migration | succeeded | flaky | passed, but not as migration-peer-retired-unasked"
moves_case "a third outcome is a regression" \
  'MIGRATION-SUMMARY attempted=6 succeeded=2 consecutive=2 firstUnmoved=move 3: PathNotValidated attempts=1' \
  '{"attempted":6,"succeeded":2,"first_unmoved":"PathNotValidated"}' 0 1 \
  "| REGRESSION | our-client-migrating | m | active-migration | failed | flaky | failed, but not as migration-out-of-ids-peer-retired-nothing"
sed 's/"flaky": {"why"/"known-failure": {"why"/' "$tmp/moves-exp.json" >"$tmp/bad-exp.json"
python3 "$script" judge "$tmp/bad-exp.json" "$tmp/moves" >/dev/null 2>"$tmp/bad.err"
expect_rc "a pass signature on a known failure stops the gate" $? 1
expect "the misplaced pass signature is named" "$(cat "$tmp/bad.err")" "only a flaky cell takes a pass-signature"

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
  printf '{"name":"quic:packet_received","data":{"header":{"packet_type":"initial","scil":0,"packet_number":1},"frames":[]}}\n'
  printf '{"name":"quic:packet_sent","data":{"header":{"packet_type":"initial"},"frames":[{"frame_type":"ack","acked_ranges":[[0,1],[4]]}]}}\n'
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
ev="$(python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))["tests"]["multiplexing"]["connections"][0]; print(c["peer_unfinished_streams"])' "$tmp/ev.json")"
expect "the extractor counts streams begun without a FIN (400 above is peer-opened, 401 is ours-to-open)" "$ev" "1"
ev="$(python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))["tests"]["multiplexing"]["connections"][0]; print(c["rx_initial_unlogged"])' "$tmp/ev.json")"
expect "the extractor counts Initial packets we acknowledged that the qlog never shows arriving (0 and 4; 1 arrived)" "$ev" "2"
d2="$tmp/logs2/ditchoom_x/multiplexing"
mkdir -p "$d2/server/qlog" "$d2/client"
{
  printf '{"name":"quic:packet_received","data":{"frames":[{"frame_type":"stream","stream_id":0,"offset":0,"fin":true},{"frame_type":"stream","stream_id":4,"offset":0},{"frame_type":"stream","stream_id":8,"offset":0},{"frame_type":"reset_stream","stream_id":8,"final_size":3}]}}\n'
  printf '{"name":"quic:packet_received","data":{"frames":[{"frame_type":"stream","stream_id":4,"offset":3,"fin":true}]}}\n'
  printf '{"name":"quic:packet_received","data":{"frames":[{"frame_type":"stream","stream_id":12,"offset":0}]}}\n'
} >"$d2/server/qlog/quiche-server-1.sqlog"
printf '[2026-11-18T22:04:46Z ERROR quiche_apps::common] connection timed out after 31.5s and only completed 3/4 requests\n' >"$d2/client/log.txt"
python3 -I "$extract" "$tmp/logs2" our-server x "$tmp/ev2.json"
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["multiplexing"]; print(t["connections"][0]["peer_unfinished_streams"], t["peer_unanswered_requests"])' "$tmp/ev2.json")"
expect "a finished, a reset and a continued stream are not unfinished; one never finished is" "$ev" "1 1"

# The handshake-family kinds: each matches the evidence that shows its failure and nothing else.
hconn() { # <qlog> <extra facts json, without braces>
  printf '{"qlog":"%s","local_initial_max_streams_bidi":100,"max_streams_bidi_sent":0,"peer_bidi_streams_opened":0,"local_closes":[],"remote_disable_active_migration":false,"rx_packets":5,"rx_initial_crypto":1,"rx_initial_acks":1,"rx_handshake_crypto":1,"rx_handshake_acks":1,"bytes_in_flight_at_end":0,"longest_wait_in_flight_ms":2000,"tx_crypto":1,"fin_sent_streams":1,"peer_resets":[]%s}' "$1" "${2:+,$2}"
}
kind_case() { # <name> <kind json> <direction> <test evidence json>
  python3 - "$script" "$2" "$3" "$4" "$1" <<'PY'
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("gate", sys.argv[1])
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
raw = json.loads(sys.argv[2])
kind = raw.pop("kind")
result = gate.SIGNATURES[kind](raw).match(json.loads(sys.argv[4]), gate.peer_role(sys.argv[3]))
print(f"{sys.argv[5]}: {type(result).__name__}")
PY
}
check_kind() { # <name> <kind json> <direction> <evidence> <expected>
  expect "$1" "$(kind_case "$1" "$2" "$3" "$4")" "$1: $5"
}
silent='{"kind":"peer-silent-until-our-timeout","silence_s":25,"our_wait_s":8}'
check_kind "a peer silent with nothing of ours in flight" "$silent" our-client "{\"connections\":[$(hconn a '"last_rx_ms":3400,"end_ms":33400')]}" Matched
check_kind "silence while our data was in flight is not the peer's" "$silent" our-client "{\"connections\":[$(hconn a '"last_rx_ms":3400,"end_ms":33400' | sed 's/"bytes_in_flight_at_end":0/"bytes_in_flight_at_end":84/')]}" NotMatched
check_kind "silence after our own long probe wait is not the peer's" "$silent" our-client "{\"connections\":[$(hconn a '"last_rx_ms":3400,"end_ms":33400' | sed 's/"longest_wait_in_flight_ms":2000/"longest_wait_in_flight_ms":25000/')]}" NotMatched
check_kind "a short silence is not the signature" "$silent" our-client "{\"connections\":[$(hconn a '"last_rx_ms":30000,"end_ms":33400')]}" NotMatched
# Run 43918562845, our client against quinn, handshakeloss: the handshake completed, our request was acknowledged, and
# quinn's response never arrived; the client idled out 30 s after the one packet it heard at 24.2 s.
check_kind "a completed handshake whose response never arrived" "$silent" our-client "{\"connections\":[$(hconn a '"last_rx_ms":24193.554,"end_ms":54212.759,"tx_after_last_rx":1,"longest_wait_in_flight_ms":3964.4')]}" Matched
# The committed expectation for that cell accepts it: its signature is an any-of that includes the silent kind.
expect "quinn handshakeloss expects the silent signature as well" "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["our-client"]["quinn"]["handshakeloss"]["flaky"]["signature"]; print(sorted(x["kind"] for x in s["signatures"]))' "$here/../../../test-harness/quic-interop/expectations.json")" "['peer-never-resent-server-hello', 'peer-silent-until-our-timeout']"
hello='{"kind":"peer-never-resent-server-hello"}'
check_kind "Initial acks without a ServerHello" "$hello" our-client "{\"connections\":[$(hconn a '"close_reason":"Failed to establish connection"' | sed 's/"rx_initial_crypto":1/"rx_initial_crypto":0/')]}" Matched
check_kind "a ServerHello that arrived is not the signature" "$hello" our-client "{\"connections\":[$(hconn a '"close_reason":"Failed to establish connection"')]}" NotMatched
acked='{"kind":"peer-acked-initials-without-server-hello","min_acks":1000}'
stuck='"rx_initial_crypto":0,"rx_handshake_crypto":0,"rx_initial_acks":2578'
check_kind "Initial acks for minutes, no ServerHello, never closed" "$acked" our-client "{\"connections\":[$(hconn a '"end_ms":270227,"last_rx_ms":270226.9' | sed "s/\"rx_initial_crypto\":1,\"rx_initial_acks\":1,\"rx_handshake_crypto\":1/$stuck/")]}" Matched
check_kind "a few Initial acks is not the signature" "$acked" our-client "{\"connections\":[$(hconn a | sed 's/"rx_initial_crypto":1,"rx_initial_acks":1,"rx_handshake_crypto":1/"rx_initial_crypto":0,"rx_handshake_crypto":0,"rx_initial_acks":3/')]}" NotMatched
check_kind "a ServerHello that arrived is not the signature" "$acked" our-client "{\"connections\":[$(hconn a | sed 's/"rx_initial_acks":1,/"rx_initial_acks":2578,/')]}" NotMatched
check_kind "a connection we closed is not the signature" "$acked" our-client "{\"connections\":[$(hconn a '"close_reason":"Failed to establish connection"' | sed "s/\"rx_initial_crypto\":1,\"rx_initial_acks\":1,\"rx_handshake_crypto\":1/$stuck/")]}" NotMatched
finished='{"kind":"peer-finished-never-arrived"}'
check_kind "Handshake acks without a Finished" "$finished" our-server "{\"connections\":[$(hconn a | sed 's/"rx_handshake_crypto":1/"rx_handshake_crypto":0/;s/"fin_sent_streams":1/"fin_sent_streams":0/')]}" Matched
check_kind "a connection we answered is not the signature" "$finished" our-server "{\"connections\":[$(hconn a | sed 's/"rx_handshake_crypto":1/"rx_handshake_crypto":0/')]}" NotMatched
reset='{"kind":"peer-reset-duplicated-request"}'
check_kind "a reset after a duplicated request" "$reset" our-client "{\"connections\":[$(hconn a | sed 's/"peer_resets":\[\]/"peer_resets":[{"stream_id":0,"final_size":0,"our_copies":3}]/')]}" Matched
check_kind "a reset of a request sent once is not the signature" "$reset" our-client "{\"connections\":[$(hconn a | sed 's/"peer_resets":\[\]/"peer_resets":[{"stream_id":0,"final_size":0,"our_copies":1}]/')]}" NotMatched
extra='{"kind":"peer-counted-extra-handshake"}'
check_kind "one handshake more than our connections" "$extra" our-client "{\"connections\":[$(hconn a),$(hconn b)],\"runner\":{\"handshakes\":{\"expected\":2,\"got\":3}}}" Matched
check_kind "an extra connection of ours is not the signature" "$extra" our-client "{\"connections\":[$(hconn a),$(hconn b),$(hconn c)],\"runner\":{\"handshakes\":{\"expected\":2,\"got\":3}}}" NotMatched
fewer='{"kind":"peer-used-fewer-connections"}'
check_kind "fewer connections than the test asks for" "$fewer" our-server "{\"connections\":[$(hconn a)],\"runner\":{\"handshakes\":{\"expected\":50,\"got\":1}}}" Matched
check_kind "as many connections as asked is not the signature" "$fewer" our-server "{\"connections\":[$(hconn a),$(hconn b)],\"runner\":{\"handshakes\":{\"expected\":2,\"got\":1}}}" NotMatched
limit='{"kind":"runner-time-limit-while-we-answered","answer_ms":5000}'
check_kind "the time limit while we answered fast" "$limit" our-server "{\"connections\":[$(hconn a '"max_answer_ms":2600')],\"runner\":{\"time_limit_s\":300}}" Matched
check_kind "a slow answer of ours is not the signature" "$limit" our-server "{\"connections\":[$(hconn a '"max_answer_ms":9000')],\"runner\":{\"time_limit_s\":300}}" NotMatched
late='{"kind":"peer-request-arrived-late","after_s":20}'
check_kind "a request long after our HANDSHAKE_DONE" "$late" our-server "{\"connections\":[$(hconn a '"handshake_done_sent_ms":11136,"first_request_ms":41802')]}" Matched
check_kind "a prompt request is not the signature" "$late" our-server "{\"connections\":[$(hconn a '"handshake_done_sent_ms":11136,"first_request_ms":11200')]}" NotMatched
lost='{"kind":"sim-lost-every-datagram-to-us","min_datagrams":5}'
check_kind "a deaf connection the simulator starved" "$lost" our-client "{\"connections\":[$(hconn a | sed 's/"rx_packets":5/"rx_packets":0/')],\"sim_to_us\":[{\"port\":57689,\"datagrams\":9,\"delivered\":0}]}" Matched
check_kind "a port that got one datagram is not the signature" "$lost" our-client "{\"connections\":[$(hconn a | sed 's/"rx_packets":5/"rx_packets":0/')],\"sim_to_us\":[{\"port\":57689,\"datagrams\":9,\"delivered\":1}]}" NotMatched
lostpeer='{"kind":"sim-lost-every-datagram-to-the-peer","min_datagrams":5}'
check_kind "a ClientHello we could not answer while every ack was lost" "$lostpeer" our-server "{\"connections\":[$(hconn a '"tx_crypto":0')],\"sim_to_peer\":[{\"port\":40390,\"datagrams\":5,\"delivered\":0}]}" Matched
check_kind "a connection that answered is not the signature" "$lostpeer" our-server "{\"connections\":[$(hconn a '"tx_crypto":2')],\"sim_to_peer\":[{\"port\":40390,\"datagrams\":5,\"delivered\":0}]}" NotMatched
empty='{"kind":"peer-left-a-download-empty"}'
check_kind "an empty download while we answered every connection" "$empty" our-server "{\"connections\":[$(hconn a),$(hconn b)],\"exits\":{\"client\":0},\"runner\":{\"empty_downloads\":1}}" Matched
check_kind "an unanswered connection is not the signature" "$empty" our-server "{\"connections\":[$(hconn a),$(hconn b | sed 's/"fin_sent_streams":1/"fin_sent_streams":0/')],\"exits\":{\"client\":0},\"runner\":{\"empty_downloads\":1}}" NotMatched
# Run 44402991044, our-server-raised-streams against the stock quiche client: 1999 requests opened, 1992 answered, the
# other 7 began as short STREAM frames (3 bytes, no FIN: the client's send said Done) and were never finished; the
# client logged "completed 1992/1999 requests" after its idle timeout and exited 254.
unfin='"fin_sent_streams":1992,"peer_bidi_streams_opened":1999,"peer_unfinished_streams":7'
unf='{"kind":"peer-left-a-request-unfinished"}'
check_kind "requests begun and never finished, the rest answered" "$unf" our-server "{\"connections\":[$(hconn a "$unfin" | sed 's/"fin_sent_streams":1,//;s/"peer_bidi_streams_opened":0,//')],\"exits\":{\"client\":254},\"peer_unanswered_requests\":7}" Matched
check_kind "the peer not reporting a gave-up request is not the signature" "$unf" our-server "{\"connections\":[$(hconn a "$unfin" | sed 's/"fin_sent_streams":1,//;s/"peer_bidi_streams_opened":0,//')],\"exits\":{\"client\":254}}" NotMatched
check_kind "a whole request we left unanswered is not the signature" "$unf" our-server "{\"connections\":[$(hconn a "$unfin" | sed 's/"fin_sent_streams":1,//;s/"peer_bidi_streams_opened":0,//;s/"fin_sent_streams":1992/"fin_sent_streams":1991/')],\"exits\":{\"client\":254},\"peer_unanswered_requests\":7}" NotMatched
check_kind "no unfinished stream is not the signature" "$unf" our-server "{\"connections\":[$(hconn a "$unfin" | sed 's/"fin_sent_streams":1,//;s/"peer_bidi_streams_opened":0,//;s/"peer_unfinished_streams":7/"peer_unfinished_streams":0/')],\"exits\":{\"client\":254},\"peer_unanswered_requests\":7}" NotMatched
check_kind "a connection we closed is not the signature" "$unf" our-server "{\"connections\":[$(hconn a "$unfin" | sed 's/"fin_sent_streams":1,//;s/"peer_bidi_streams_opened":0,//;s/"local_closes":\[\]/"local_closes":[{"space":"transport","code":4}]/')],\"exits\":{\"client\":254},\"peer_unanswered_requests\":7}" NotMatched
expect "quiche multiplexing expects the unfinished-request signature as well" "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["our-server-raised-streams"]["quiche"]["multiplexing"]["flaky"]["signature"]; print(sorted(x["kind"] for x in s["signatures"]))' "$here/../../../test-harness/quic-interop/expectations.json")" "['peer-left-a-download-empty', 'peer-left-a-request-unfinished']"
never='{"kind":"peer-request-never-arrived","min_packets":5}'
check_kind "a confirmed connection that never got a request" "$never" our-server "{\"connections\":[$(hconn a '"handshake_done_sent_ms":35' | sed 's/"fin_sent_streams":1/"fin_sent_streams":0/;s/"rx_packets":5/"rx_packets":10/')]}" Matched
check_kind "a connection with a request is not the signature" "$never" our-server "{\"connections\":[$(hconn a '"handshake_done_sent_ms":35,"first_request_ms":40' | sed 's/"fin_sent_streams":1/"fin_sent_streams":0/;s/"rx_packets":5/"rx_packets":10/')]}" NotMatched
nohs='{"kind":"peer-handshake-packets-never-arrived","min_lost":5}'
stuck="$(hconn a '"close_reason":"Failed to establish connection"' | sed 's/"rx_handshake_crypto":1/"rx_handshake_crypto":0/;s/"rx_handshake_acks":1/"rx_handshake_acks":0/;s/"tx_crypto":1/"tx_crypto":19/')"
check_kind "no Handshake packet arrived while the simulator lost the client's" "$nohs" our-server "{\"connections\":[$stuck],\"sim_from_peer\":[{\"port\":34091,\"datagrams\":22,\"delivered\":12}]}" Matched
check_kind "nothing lost on the way to us is not the signature" "$nohs" our-server "{\"connections\":[$stuck],\"sim_from_peer\":[{\"port\":34091,\"datagrams\":22,\"delivered\":20}]}" NotMatched
gone='{"kind":"peer-gone-while-we-probed","min_probes":6,"silence_s":25}'
check_kind "probes unanswered for half a minute" "$gone" our-client "{\"connections\":[$(hconn a '"last_rx_ms":270,"end_ms":30286,"tx_after_last_rx":11' | sed 's/"bytes_in_flight_at_end":0/"bytes_in_flight_at_end":1144/')]}" Matched
check_kind "two unanswered packets are not the signature" "$gone" our-client "{\"connections\":[$(hconn a '"last_rx_ms":270,"end_ms":30286,"tx_after_last_rx":2' | sed 's/"bytes_in_flight_at_end":0/"bytes_in_flight_at_end":1144/')]}" NotMatched
partial='{"kind":"peer-client-hello-never-completed","min_lost":5}'
check_kind "a ClientHello that never completed" "$partial" our-server "{\"connections\":[$(hconn a | sed 's/"tx_crypto":1/"tx_crypto":0/;s/"fin_sent_streams":1/"fin_sent_streams":0/')],\"sim_from_peer\":[{\"port\":40869,\"datagrams\":9,\"delivered\":4}]}" Matched
check_kind "a ClientHello we answered is not the signature" "$partial" our-server "{\"connections\":[$(hconn a | sed 's/"fin_sent_streams":1/"fin_sent_streams":0/')],\"sim_from_peer\":[{\"port\":40869,\"datagrams\":9,\"delivered\":4}]}" NotMatched
# Run 44484652758, the stock quiche client against our server, handshakeloss: the ClientHello's first packet
# opened the connection (absent from the server's qlog), the rest never arrived, and only PINGs followed.
opening='{"kind":"peer-client-hello-only-in-its-opening-packet","min_lost":5}'
pings='s/"rx_initial_crypto":1,"rx_initial_acks":1,"rx_handshake_crypto":1,"rx_handshake_acks":1/"rx_initial_crypto":0,"rx_initial_acks":0,"rx_handshake_crypto":0,"rx_handshake_acks":0/;s/"tx_crypto":1/"tx_crypto":0/;s/"fin_sent_streams":1/"fin_sent_streams":0/'
cut38737="\"sim_from_peer\":[{\"port\":38737,\"datagrams\":9,\"delivered\":3}]"
opened="$(hconn a '"rx_initial_unlogged":1' | sed "$pings;"'s/"local_closes":\[\]/"local_closes":[{"space":"transport","code":0}]/')"
check_kind "a ClientHello only in its opening packet" "$opening" our-server "{\"connections\":[$opened],$cut38737}" Matched
check_kind "a later Initial with CRYPTO is not the signature" "$opening" our-server "{\"connections\":[${opened/\"rx_initial_crypto\":0/\"rx_initial_crypto\":1}],$cut38737}" NotMatched
check_kind "a ClientHello we answered is not the signature" "$opening" our-server "{\"connections\":[${opened/\"tx_crypto\":0/\"tx_crypto\":1}],$cut38737}" NotMatched
check_kind "a server that closed with an error is not the signature" "$opening" our-server "{\"connections\":[${opened/\"code\":0/\"code\":296}],$cut38737}" NotMatched
check_kind "an opening packet the qlog recorded is not the signature" "$opening" our-server "{\"connections\":[${opened/\"rx_initial_unlogged\":1/\"rx_initial_unlogged\":0}],$cut38737}" NotMatched
check_kind "too little loss is not the signature" "$opening" our-server "{\"connections\":[$opened],${cut38737/\"delivered\":3/\"delivered\":5}}" NotMatched
expect "quiche handshakeloss expects the opening-packet signature as well" "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["our-server"]["quiche"]["handshakeloss"]["flaky"]["signature"]; print(sorted(x["kind"] for x in s["signatures"]))' "$here/../../../test-harness/quic-interop/expectations.json")" "['peer-client-hello-never-completed', 'peer-client-hello-only-in-its-opening-packet', 'peer-handshake-packets-never-arrived', 'peer-move-never-reached-us', 'peer-packets-lost-while-we-probed-on-time']"
nochallenge='{"kind":"peer-server-did-not-challenge-new-path-first"}'
check_kind "the server's first packet on a new path had no challenge" "$nochallenge" our-client "{\"connections\":[$(hconn a)],\"exits\":{\"client\":0},\"runner\":{\"server_first_packets_without_path_challenge\":2}}" Matched
check_kind "a client that failed is not the signature" "$nochallenge" our-client "{\"connections\":[$(hconn a)],\"exits\":{\"client\":1},\"runner\":{\"server_first_packets_without_path_challenge\":2}}" NotMatched
# Run 44177059008 retry, our client against the stock quiche server, handshakeloss: 59 of the ServerHello's 90 bytes
# arrived, the rest never did, and the server acknowledged 2722 of our Initial probes until the time limit.
short='{"kind":"peer-server-hello-stopped-short","min_acks":1000}'
cut='"rx_initial_crypto_end":59,"end_ms":272778.7,"last_rx_ms":272536.7'
unread='s/"rx_initial_acks":1,"rx_handshake_crypto":1,"rx_handshake_acks":1/"rx_initial_acks":2722,"rx_handshake_crypto":0,"rx_handshake_acks":0/'
check_kind "a ServerHello that stopped short, acknowledged for minutes" "$short" our-client "{\"connections\":[$(hconn a "$cut" | sed "$unread")]}" Matched
check_kind "a whole ServerHello is not the signature" "$short" our-client "{\"connections\":[$(hconn a "${cut/:59,/:90,}" | sed "$unread")]}" NotMatched
check_kind "a short ServerHello with Handshake packets read is not the signature" "$short" our-client "{\"connections\":[$(hconn a "$cut" | sed 's/"rx_initial_acks":1,/"rx_initial_acks":2722,/')]}" NotMatched
check_kind "a short ServerHello on a connection we closed is not the signature" "$short" our-client "{\"connections\":[$(hconn a "$cut,\"close_reason\":\"Failed to establish connection\"" | sed "$unread")]}" NotMatched
# Run 44177059008, the same cell: our request went out 366 ms before the server's HANDSHAKE_DONE, three copies were
# never acknowledged, and the server was silent 48 s.
early='{"kind":"peer-never-acked-early-request","min_copies":3,"silence_s":25}'
sent='"handshake_done_rx_ms":5437.4,"first_stream_tx_ms":5071.4,"max_stream_copies":3,"last_rx_ms":5437.4,"end_ms":53507.6'
inflight='s/"bytes_in_flight_at_end":0/"bytes_in_flight_at_end":642/'
check_kind "an early request never acknowledged, then silence" "$early" our-client "{\"connections\":[$(hconn a "$sent" | sed "$inflight")]}" Matched
check_kind "an acknowledged request is not the signature" "$early" our-client "{\"connections\":[$(hconn a "$sent,\"first_stream_ack_ms\":5440" | sed "$inflight")]}" NotMatched
check_kind "a request sent after HANDSHAKE_DONE is not the signature" "$early" our-client "{\"connections\":[$(hconn a "${sent/5071.4/5500}" | sed "$inflight")]}" NotMatched
check_kind "a request we never re-sent is not the signature" "$early" our-client "{\"connections\":[$(hconn a "${sent/\"max_stream_copies\":3/\"max_stream_copies\":1}" | sed "$inflight")]}" NotMatched
check_kind "a request no longer in flight is not the signature" "$early" our-client "{\"connections\":[$(hconn a "$sent")]}" NotMatched
# Run 44177059008, msquic's client against our server, amplificationlimit: 3660 bytes sent on its one 1220-byte
# datagram, four PTO expiries with nothing sent, six of its datagrams dropped.
amp='{"kind":"peer-gave-up-while-we-were-amplification-limited","min_dropped":6}'
limited="$(hconn a '"pto_expiries_after_last_tx":4' | sed 's/"rx_packets":5/"rx_packets":0/;s/"bytes_in_flight_at_end":0/"bytes_in_flight_at_end":3660/')"
check_kind "the client gave up while we were amplification-limited" "$amp" our-server "{\"connections\":[$limited],\"sim_droplist_from_peer\":6}" Matched
check_kind "a server that heard a second datagram is not the signature" "$amp" our-server "{\"connections\":[${limited/\"rx_packets\":0/\"rx_packets\":1}],\"sim_droplist_from_peer\":6}" NotMatched
check_kind "a server that sent less than its budget is not the signature" "$amp" our-server "{\"connections\":[${limited/3660/2400}],\"sim_droplist_from_peer\":6}" NotMatched
check_kind "a server that sent on its PTO is not the signature" "$amp" our-server "{\"connections\":[${limited/\"pto_expiries_after_last_tx\":4/\"pto_expiries_after_last_tx\":0}],\"sim_droplist_from_peer\":6}" NotMatched
check_kind "fewer drops than the drop list is not the signature" "$amp" our-server "{\"connections\":[$limited],\"sim_droplist_from_peer\":2}" NotMatched
expect "quiche handshakeloss expects both of its signatures" "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["our-client"]["quiche"]["handshakeloss"]["flaky"]["signature"]; print(sorted(x["kind"] for x in s["signatures"]))' "$here/../../../test-harness/quic-interop/expectations.json")" "['peer-move-never-reached-us', 'peer-never-acked-early-request', 'peer-packets-lost-while-we-probed-on-time', 'peer-server-hello-stopped-short']"
anyof='{"kind":"any-of","signatures":[{"kind":"peer-request-arrived-late","after_s":20},{"kind":"sim-lost-every-datagram-to-the-peer","min_datagrams":5}]}'
check_kind "any-of matches its second signature" "$anyof" our-server "{\"connections\":[$(hconn a '"tx_crypto":0')],\"sim_to_peer\":[{\"port\":40390,\"datagrams\":5,\"delivered\":0}]}" Matched
check_kind "any-of matches neither" "$anyof" our-server "{\"connections\":[$(hconn a)]}" NotMatched
python3 "$script" judge <(echo '{"our-client":{"p":{"handshakeloss":{"flaky":{"why":"x","signature":{"kind":"peer-silent-until-our-timeout","silence_s":25}}}}}}') "$tmp/ok" >/dev/null 2>"$tmp/bad.err"
expect_rc "a missing field stops the gate" $? 1

# The extractor reads the handshake spaces, the last packet, what was in flight, answers, resets and the runner.
d="$tmp/logs2/p_ditchoom/handshakeloss"
mkdir -p "$d/client/qlog"
{
  printf '{"time":0,"name":"quic:packet_sent","data":{"frames":[{"frame_type":"crypto"}]}}\n'
  printf '{"time":1,"name":"quic:recovery_metrics_updated","data":{"bytes_in_flight":300}}\n'
  printf '{"time":1000,"name":"quic:packet_sent","data":{"frames":[{"frame_type":"crypto"}]}}\n'
  printf '{"time":1030,"name":"quic:packet_received","data":{"header":{"packet_type":"initial"},"frames":[{"frame_type":"ack"}]}}\n'
  printf '{"time":1040,"name":"quic:packet_received","data":{"header":{"packet_type":"handshake"},"frames":[{"frame_type":"crypto"}]}}\n'
  printf '{"time":1050,"name":"quic:packet_sent","data":{"frames":[{"frame_type":"stream","stream_id":0,"offset":0,"fin":true}]}}\n'
  printf '{"time":1060,"name":"quic:packet_sent","data":{"frames":[{"frame_type":"stream","stream_id":0,"offset":0,"fin":true}]}}\n'
  printf '{"time":1090,"name":"quic:packet_received","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"reset_stream","stream_id":0,"final_size":0}]}}\n'
  printf '{"time":1091,"name":"quic:recovery_metrics_updated","data":{"bytes_in_flight":0}}\n'
  printf '{"time":31091,"name":"quic:connection_closed","data":{"reason":"Failed to establish connection"}}\n'
} >"$d/client/qlog/quiche-client-1.sqlog"
d3="$tmp/logs2/p_ditchoom/handshake"
mkdir -p "$d3/client/qlog"
{
  printf '{"time":10,"name":"quic:packet_received","data":{"header":{"packet_type":"initial"},"frames":[{"frame_type":"crypto","offset":27,"raw":{"payload_length":32}}]}}\n'
  printf '{"time":11,"name":"quic:packet_received","data":{"header":{"packet_type":"initial"},"frames":[{"frame_type":"crypto","offset":0,"raw":{"payload_length":27}}]}}\n'
  printf '{"time":12,"name":"quic:packet_received","data":{"header":{"packet_type":"initial"},"frames":[{"frame_type":"crypto","offset":70,"length":20}]}}\n'
  printf '{"time":20,"name":"quic:packet_sent","data":{"header":{"packet_type":"1RTT","packet_number":9},"frames":[{"frame_type":"stream","stream_id":0,"offset":0,"fin":true}]}}\n'
  printf '{"time":30,"name":"quic:packet_received","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"handshake_done"},{"frame_type":"ack","acked_ranges":[[3,8]]}]}}\n'
  printf '{"time":40,"name":"quic:packet_received","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"ack","acked_ranges":[[1],[9,10]]}]}}\n'
  printf '{"time":50,"name":"quic:recovery_metrics_updated","data":{"pto_count":1}}\n'
  printf '{"time":60,"name":"quic:packet_sent","data":{"header":{"packet_type":"1RTT","packet_number":11},"frames":[{"frame_type":"ping"}]}}\n'
  printf '{"time":70,"name":"quic:recovery_metrics_updated","data":{"pto_count":1}}\n'
  printf '{"time":80,"name":"quic:recovery_metrics_updated","data":{"pto_count":2}}\n'
} >"$d3/client/qlog/quiche-client-3.sqlog"
printf "sim  | Packets: 1 Dropping packet 2 (1220 bytes) from 193.167.100.100\nsim  | Dropping packet 3 (1220 bytes) from 193.167.0.100\n" >"$d3/output.txt"
{
  printf "sim  | Corrupting 1200 bytes 193.167.100.100:443 -> 193.167.0.100:40000 offset 3 0x1 -> 0x2\n"
  printf "sim  | Forwarding 1200 bytes 193.167.100.100:443 -> 193.167.0.100:40001, corrupted 1/2\n"
  printf "First server packet on new path (('193.167.100.100', 443), ('193.167.0.224', 59022)) did not contain a PATH_CHALLENGE frame\n"
  printf "2026-11-14 15:23:49,438 Expected 50 handshakes. Got: 51\n"
} >"$d/output.txt"
python3 -I "$extract" "$tmp/logs2" our-client p "$tmp/ev2.json"
expect_rc "the extractor reads a handshake-loss test" $? 0
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["handshakeloss"]; c=t["connections"][0]; print(c["rx_initial_acks"], c["rx_initial_crypto"], c["rx_handshake_crypto"], c["last_rx_ms"], c["end_ms"], c["bytes_in_flight_at_end"], c["longest_wait_in_flight_ms"], c["peer_resets"], c["close_reason"], t["runner"], t["sim_to_us"])' "$tmp/ev2.json")"
expect "the extractor reads the handshake facts" "$ev" "1 0 1 1090.0 31091.0 0 1000.0 [{'final_size': 0, 'our_copies': 2, 'stream_id': 0}] Failed to establish connection {'handshakes': {'expected': 50, 'got': 51}, 'server_first_packets_without_path_challenge': 1} [{'datagrams': 1, 'delivered': 0, 'port': 40000}, {'datagrams': 1, 'delivered': 1, 'port': 40001}]"
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["handshake"]; c=t["connections"][0]; print(c["rx_initial_crypto_end"], c["first_stream_tx_ms"], c["handshake_done_rx_ms"], c["first_stream_ack_ms"], c["max_stream_copies"], c["pto_expiries_after_last_tx"], t["sim_droplist_from_peer"])' "$tmp/ev2.json")"
expect "the extractor reads crypto coverage, the request's timeline, PTO expiries and the drop list" "$ev" "59 20.0 30.0 40.0 1 2 1"

# The handshake family judged on the one connection that failed, from real captures (2026-11-19 runs 44543141897,
# 44540058179, 44564256526 and 44546515447, each trimmed to the failing connection: our qlog, the simulator's two
# captures on its 4-tuple, the runner's lines for its port, the peer's log lines naming it). Each kind asserts what
# we did right on that connection; every negative below is a look-alike in which we did something wrong, and each
# is first shown to match the symptom alone (the naive check), so the kind is what keeps it red.
fx="$here/fixtures/quic-interop-handshake"
for cell in our-server-quinn:our-server:quinn our-server-quiche:our-server:quiche our-server-quiche-answer-lost:our-server:quiche our-server-quiche-request-lost:our-server:quiche our-server-xquic:our-server:xquic our-client-quiche:our-client:quiche our-client-quiche-anti-deadlock:our-client:quiche our-client-kwik:our-client:kwik our-client-s2n-request-lost:our-client:s2n-quic our-client-haproxy:our-client:haproxy; do
  IFS=: read -r name direction peer <<<"$cell"
  python3 -I "$extract" "$fx/$name/logs" "$direction" "$peer" "$tmp/fx-$name.json"
  expect_rc "the extractor reads the $name capture" $? 0
done
conn_case() { # <name> <evidence json> <kind json> <python statements on c, the test's only connection, and test>
  python3 - "$script" "$2" "$3" "$4" "$1" <<'PY'
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("gate", sys.argv[1])
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
evidence = json.load(open(sys.argv[2]))
test = next(iter(evidence["tests"].values()))
c = test["connections"][0]
exec(sys.argv[4])
raw = json.loads(sys.argv[3])
kind = raw.pop("kind")
result = gate.SIGNATURES[kind](raw).match(test, gate.peer_role(evidence["direction"]))
# The symptom alone: nothing more arrived until the connection ended (or the peer says it gave up), with our data
# in flight for the probing kind and none for the other.
silent = c["end_ms"] - c["last_rx_ms"] >= 30000 or test.get("peer_unanswered_requests", 0) > 0 or bool(c.get("peer_closes"))
naive = silent and (c["bytes_in_flight_before_close"] > 0) == (kind == "peer-packets-lost-while-we-probed-on-time")
print(f"{sys.argv[5]}: {type(result).__name__}, naive {'Matched' if naive else 'NotMatched'}")
PY
}
check_conn() { # <name> <evidence> <kind> <mutation> <kind result> <naive result>
  expect "$1" "$(conn_case "$1" "$2" "$3" "$4")" "$1: $5, naive $6"
}
move='{"kind":"peer-move-never-reached-us","ended_by":"our-idle-timeout","idle_s":30}'
probed='{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"our-idle-timeout","idle_s":30}'
gaveup='{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"peer-gave-up"}'
quinn="$tmp/fx-our-server-quinn.json"
haproxy="$tmp/fx-our-client-haproxy.json"
qclient="$tmp/fx-our-client-quiche.json"
qserver="$tmp/fx-our-server-quiche.json"
# Negatives first: each matches the symptom, and must not match the kind.
check_conn "a client that went quiet before any Handshake ACK (its anti-deadlock duty, RFC 9002 §6.2.2.1)" "$haproxy" "$move" 'c["rx_handshake_acks"] = 0' NotMatched Matched
check_conn "a datagram of the peer's that reached us unchanged after our last receipt" "$quinn" "$move" 'c["wire"]["peer_delivered_after_last_rx"] = 1' NotMatched Matched
check_conn "a packet of the peer's that reached us and was never read" "$haproxy" "$move" 'c["wire"]["peer_packets_unread"] = 1' NotMatched Matched
check_conn "a connection our server closed itself instead of idling out" "$quinn" "$move" 'c["local_closes"] = [{"space": "transport", "code": 0}]' NotMatched Matched
check_conn "a sibling connection whose request arrived and was answered" "$quinn" "$move" 'c["first_request_ms"] = 3300.0; c["fin_sent_streams"] = 1' NotMatched Matched
check_conn "a connection the captures do not tie to its 4-tuple" "$quinn" "$move" 'del c["wire"]' NotMatched Matched
check_conn "data of ours still in flight is not the peer's move" "$quinn" "$move" 'c["bytes_in_flight_at_end"] = c["bytes_in_flight_before_close"] = 1200' NotMatched NotMatched
check_conn "a probe that left out a space with unacknowledged data" "$qclient" "$probed" 'c["probes_missing_a_space"] = 1' NotMatched Matched
check_conn "a probe later than its PTO, doubled per expiry" "$qclient" "$probed" 'c["late_probes"] = 1' NotMatched Matched
check_conn "a packet of the peer's that reached us while we probed and was never read" "$qclient" "$probed" 'c["wire"]["peer_packets_unread"] = 1' NotMatched Matched
check_conn "a peer report that names another connection" "$qserver" "$gaveup" 'c["peer_gave_up_requests"] = 0; test["peer_unanswered_requests"] = 1' NotMatched Matched
check_conn "a request that reached our server whole and got no answer" "$qserver" "$gaveup" 'c["first_request_ms"] = 16600.0; c["peer_bidi_streams_opened"] = 1' NotMatched Matched
check_conn "a peer that closed with an error code, a complaint about us" "$fx/our-client-nginx.evidence.json" '{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"peer-closed"}' 'c["peer_closes"] = [{"space": "transport", "code": 10}]' NotMatched Matched
# The real failures match.
check_conn "quinn's request, dropped once and never sent again in 30 s (run 44543141897)" "$quinn" "$move" '' Matched Matched
check_conn "haproxy's Handshake flight corrupted until our idle timeout (run 44546515447)" "$haproxy" "$move" '' Matched Matched
check_conn "the stock quiche server's HANDSHAKE_DONE lost while we probed on time (run 44564256526)" "$qclient" "$probed" '' Matched Matched
check_conn "the stock quiche client's request lost five times, the client gave up on this connection (run 44540058179)" "$qserver" "$gaveup" '' Matched Matched
# Run 44685812497: our answer lost three times; its probes waited the PTO of the RTT estimate when each fired (1.3 s
# smoothed after later samples), not of the estimate when the answer went out (1.9 s), so neither is late.
check_conn "the stock quiche client gave up while our answer was lost on every PTO (run 44685812497)" "$tmp/fx-our-server-quiche-answer-lost.json" "$gaveup" 'assert c["late_probes"] == 0 and c["fin_sent_streams"] == 1' Matched Matched
check_conn "the stock quiche client's request lost twice, then only its acknowledgements for 30 s (run 44683082845)" "$tmp/fx-our-server-quiche-request-lost.json" "$move" '' Matched Matched
# Run 44690803861: our probes judged from the timer as RFC 9002 arms it. kwik: a probe 2 ms after a receipt is the
# expiry quiche logged, and before confirmation only Handshake sends arm the timer. Stock quiche: with nothing in
# flight and no Handshake ACK yet, the anti-deadlock timer runs from our last receipt (§6.2.2.1).
check_conn "the xquic client's request lost three times, then nothing for 30 s and an exit 0 (run 44700038452)" "$tmp/fx-our-server-xquic.json" "$move" '' Matched Matched
# Run 44694699576 (#912), s2n-quic against our server, both attempts (their captures are 0.9 MB each: s2n reuses one
# port for every connection; the extracted evidence is the fixture).
check_conn "s2n-quic's request lost, its probes only its Finished, our HANDSHAKE_DONE lost three times" "$fx/our-server-s2n-confirmation-lost.evidence.json" "$probed" '' Matched Matched
check_conn "s2n-quic's request lost twice, then only PINGs until it gave up on this connection" "$fx/our-server-s2n-request-lost.evidence.json" '{"kind":"peer-move-never-reached-us","ended_by":"peer-gave-up"}' '' Matched Matched
check_conn "a connection we closed with an error is not one the peer gave up on" "$fx/our-server-s2n-request-lost.evidence.json" '{"kind":"peer-move-never-reached-us","ended_by":"peer-gave-up"}' 'c["local_closes"] = [{"space": "transport", "code": 10}]' NotMatched Matched
check_conn "data of ours in flight before our close is not the peer's move" "$fx/our-server-s2n-request-lost.evidence.json" '{"kind":"peer-move-never-reached-us","ended_by":"peer-gave-up"}' 'c["bytes_in_flight_before_close"] = 1200' NotMatched NotMatched
# Run 44712666803 (#991): every copy of our request to s2n-quic and every acknowledgement it sent back were lost.
check_conn "our request to s2n-quic lost on all five PTO copies (run 44712666803)" "$tmp/fx-our-client-s2n-request-lost.json" "$probed" '' Matched Matched
check_conn "the same connection with our request held back a doubling" "$tmp/fx-our-client-s2n-request-lost.json" "$probed" 'c["late_probes"] = 1' NotMatched Matched
# Run 44695470583's nginx connection ran 61 s (4 MB of captures); its extracted evidence is the fixture.
check_conn "nginx's ServerHello in 6 of 400 probes, all corrupted, then its NO_ERROR close (run 44695470583)" "$fx/our-client-nginx.evidence.json" '{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"peer-closed"}' 'assert c["late_probes"] == 0 and c["wire"]["peer_packets_unread"] == 0' Matched Matched
check_conn "kwik never sending HANDSHAKE_DONE nor its lost answer while we re-sent our Finished on time (run 44690803861)" "$tmp/fx-our-client-kwik.json" "$probed" 'assert c["late_probes"] == 0, c["late_probes"]' Matched Matched
check_conn "the stock quiche server's Handshake flight unread before its ServerHello, our anti-deadlock PINGs on time (run 44690803861)" "$tmp/fx-our-client-quiche-anti-deadlock.json" "$probed" 'assert c["late_probes"] == 0, c["late_probes"]' Matched Matched
for e in "$quinn" "$haproxy" "$qclient" "$qserver"; do
  ev="$(python3 -c 'import json,sys; c=next(iter(json.load(open(sys.argv[1]))["tests"].values()))["connections"][0]; print(c["probes_missing_a_space"], c["late_probes"], c["wire"]["peer_packets_unread"], c["wire"]["peer_delivered_after_last_rx"])' "$e")"
  expect "on time, every space, every packet read: $(basename "$e")" "$ev" "0 0 0 0"
done
expect "the stock quiche client's report is tied to its port" "$(python3 -c 'import json,sys; c=next(iter(json.load(open(sys.argv[1]))["tests"].values()))["connections"][0]; print(c["wire"]["port"], c["peer_gave_up_requests"])' "$qserver")" "49436 1"
# The same bugs from the raw captures: our qlog edited as a buggy endpoint would have written it.
mutated() { # <fixture> <direction> <peer> <python statements on lines, the qlog's records> <out evidence>
  rm -rf "$tmp/mut" && cp -R "$fx/$1" "$tmp/mut"
  local q
  q="$(find "$tmp/mut" -name '*.sqlog')"
  python3 - "$q" "$4" <<'PY'
import json, sys
path = sys.argv[1]
head, *lines = open(path).read().splitlines()
lines = [json.loads(l.lstrip("\x1e")) for l in lines if l.strip()]
exec(sys.argv[2])
open(path, "w").write("\n".join([head] + [json.dumps(l) for l in lines]) + "\n")
PY
  python3 -I "$extract" "$tmp/mut/logs" "$2" "$3" "$5"
}
# haproxy's Initial ACK at 7.04 s reached us unchanged, before two it sent later that we read; a qlog without it is an
# endpoint that dropped it.
mutated our-client-haproxy our-client haproxy 'lines = [l for l in lines if not (l.get("name") == "quic:packet_received" and l["data"]["header"].get("packet_number") == 7)]' "$tmp/mut-unread.json"
check_conn "the extractor sees a packet that reached us unread" "$tmp/mut-unread.json" "$move" 'assert c["wire"]["peer_packets_unread"] == 1, c["wire"]' NotMatched Matched
# Our probe at 150.97 s carried our Finished and our request; without the request it covers one space only.
mutated our-client-quiche our-client quiche 'lines = [l for l in lines if not (l.get("name") == "quic:packet_sent" and l["data"]["header"].get("packet_number") == 22)]' "$tmp/mut-space.json"
check_conn "the extractor sees a probe that left the request out" "$tmp/mut-space.json" "$probed" 'assert c["probes_missing_a_space"] == 1, c["probes_missing_a_space"]' NotMatched Matched
# The same probe one doubling later: a backoff that was never reset.
mutated our-client-quiche our-client quiche 'lines = [dict(l, time=l["time"] + 11950) if 150900 < l.get("time", 0) < 151000 else l for l in lines]' "$tmp/mut-late.json"
check_conn "the extractor sees a probe a doubling late" "$tmp/mut-late.json" "$probed" 'assert c["late_probes"] == 1, c["late_probes"]' NotMatched Matched
# No probe at all after our last receipt, with our Finished and request unacknowledged.
mutated our-client-quiche our-client quiche 'lines = [l for l in lines if not (l.get("name") == "quic:packet_sent" and l["time"] > 139100)]' "$tmp/mut-silent.json"
check_conn "the extractor sees a timer that never fired" "$tmp/mut-silent.json" "$probed" 'assert c["late_probes"] == 1, c["late_probes"]' NotMatched Matched
# A peer report that names no port is tied to connections by elimination: as many of our server's connections never
# received a request as the peer says it gave up on.
python3 - "$extract" <<'PY' >"$tmp/elim.out"
import importlib.util, sys
spec = importlib.util.spec_from_file_location("ev", sys.argv[1])
ev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ev)
def conns():
    return [{"qlog": "a", "wire": {}, "first_request_ms": 1.0, "peer_gave_up_requests": 0}, {"qlog": "b", "wire": {}, "peer_gave_up_requests": 0}]
one = conns(); ev.attribute_by_elimination(one, "server", {}, 1)
two = conns(); ev.attribute_by_elimination(two, "server", {}, 2)
named = conns(); ev.attribute_by_elimination(named, "server", {40000: 1}, 1)
print([c["peer_gave_up_requests"] for c in one], [c["peer_gave_up_requests"] for c in two], [c["peer_gave_up_requests"] for c in named])
PY
expect "elimination ties the report to the one connection without a request, and only then" "$(cat "$tmp/elim.out")" "[0, 1] [0, 0] [0, 0]"
# Run 44717833402: every Initial of one quinn connection was lost, so our server never began that connection.
fromlost='{"kind":"sim-lost-every-datagram-from-the-peer","min_datagrams":5}'
wired="$(hconn a '"wire":{"port":33276,"peer_sent_after_last_rx":0,"peer_delivered_after_last_rx":0,"peer_packets_unread":0}')"
check_kind "a peer port whose every datagram was lost, with no connection of ours there" "$fromlost" our-server "{\"connections\":[$wired],\"sim_from_peer\":[{\"port\":41604,\"datagrams\":8,\"delivered\":0},{\"port\":33276,\"datagrams\":9,\"delivered\":6}]}" Matched
check_kind "a datagram from that port that reached us, and no connection of ours (we dropped it), is not the signature" "$fromlost" our-server "{\"connections\":[$wired],\"sim_from_peer\":[{\"port\":41604,\"datagrams\":8,\"delivered\":1}]}" NotMatched
check_kind "a port a connection of ours is tied to is not the signature" "$fromlost" our-server "{\"connections\":[$wired],\"sim_from_peer\":[{\"port\":33276,\"datagrams\":8,\"delivered\":0}]}" NotMatched
# The idled-out kind judges the lost datagrams on the probing connection's own port, not on any port of the test.
idled='{"kind":"peer-idled-out-while-we-probed","min_probes":3,"min_lost":5}'
probing="$(hconn a '"tx_after_last_rx":15,"wire":{"port":39352,"peer_sent_after_last_rx":0,"peer_delivered_after_last_rx":0,"peer_packets_unread":0}')"
check_kind "our probes lost on the probing connection's port (run 43947308066)" "$idled" our-server "{\"connections\":[$probing],\"peer_unanswered_requests\":1,\"sim_to_peer\":[{\"port\":39352,\"datagrams\":9,\"delivered\":2}]}" Matched
check_kind "datagrams lost on another connection's port are not the signature" "$idled" our-server "{\"connections\":[$probing],\"peer_unanswered_requests\":1,\"sim_to_peer\":[{\"port\":40001,\"datagrams\":9,\"delivered\":2}]}" NotMatched
check_kind "a probing connection the captures do not tie to a port is not the signature" "$idled" our-server "{\"connections\":[$(hconn a '"tx_after_last_rx":15')],\"peer_unanswered_requests\":1,\"sim_to_peer\":[{\"port\":39352,\"datagrams\":9,\"delivered\":2}]}" NotMatched
# xquic's response, sent three times on its inflated PTO and lost each time: our request was acknowledged and our
# client holds HANDSHAKE_DONE, so the next move is the peer's (two real connections, trimmed to the failing one).
xa="$fx/our-client-xquic-response-lost-a.evidence.json"
xb="$fx/our-client-xquic-response-lost-b.evidence.json"
xf="$fx/our-client-xquic-finished-lost.evidence.json"
xt="$fx/our-client-xquic-two-attempts.evidence.json"
check_conn "xquic's response lost on all three of its PTO sends, our request acknowledged (run 45038591312)" "$xa" "$move" 'assert c["bytes_in_flight_before_close"] == 0 and "handshake_done_rx_ms" in c and c["our_streams_unfinished"] == 1 and c["wire"]["peer_delivered_after_last_rx"] == 0 and c["wire"]["peer_packets_unread"] == 0' Matched Matched
check_conn "xquic's response lost again on a connection of the same shape (run 45037712761)" "$xb" "$move" 'assert c["bytes_in_flight_before_close"] == 0 and "handshake_done_rx_ms" in c' Matched Matched
check_conn "the same connection with data of ours still in flight before our close" "$xa" "$move" 'c["bytes_in_flight_at_end"] = c["bytes_in_flight_before_close"] = 1200' NotMatched NotMatched
check_conn "the same connection with a datagram of the peer's that reached us after our last receipt" "$xa" "$move" 'c["wire"]["peer_delivered_after_last_rx"] = 1' NotMatched Matched
check_conn "the same connection with a packet of the peer's that reached us and was never read" "$xb" "$move" 'c["wire"]["peer_packets_unread"] = 1' NotMatched Matched
check_conn "the same connection before HANDSHAKE_DONE and any Handshake ACK reached us" "$xa" "$move" 'del c["handshake_done_rx_ms"]; c["rx_handshake_acks"] = 0' NotMatched Matched
check_conn "the same connection closed by us instead of idling out" "$xa" "$move" 'c["local_closes"] = [{"space": "transport", "code": 0}]' NotMatched Matched
check_conn "the same connection ended before our idle timeout" "$xa" "$move" 'c["end_ms"] = c["last_rx_ms"] + 29000' NotMatched NotMatched
check_conn "the same connection whose response arrived whole" "$xa" "$move" 'c["our_streams_unfinished"] = 0' NotMatched Matched
# Its second attempt: all eight copies of our Finished lost while our 1-RTT datagrams arrived, our probes on time.
check_conn "the Finished of ours lost on every copy while our probes ran on time (run 45038591312, attempt 2)" "$xf" "$probed" 'assert c["bytes_in_flight_before_close"] > 0 and c["probes_missing_a_space"] == 0 and c["late_probes"] == 0' Matched Matched
check_conn "that connection is not one where the peer's move was missing: our data was in flight" "$xf" "$move" '' NotMatched NotMatched
check_conn "that connection with a probe that left a space out" "$xf" "$probed" 'c["probes_missing_a_space"] = 1' NotMatched Matched

# A failed cell is judged on every attempt that failed: a first attempt that shows the signature does not excuse a
# retry that does not.
both='{"kind":"any-of","signatures":[{"kind":"peer-move-never-reached-us","ended_by":"our-idle-timeout","idle_s":30},{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"our-idle-timeout","idle_s":30}]}'
attempts_case() { # <name> <signature json> <evidence file> <retry result or none> <python on e and t, or empty> <row text> <exit>
  rm -rf "$tmp/att" && mkdir "$tmp/att"
  printf '{"our-client":{"xq":{"handshakecorruption":{"flaky":{"why":"peer and simulator: lost","signature":%s}}}}}' "$2" >"$tmp/att-exp.json"
  runner "$tmp/att/our-client__xq.json" handshakecorruption:failed
  [ "$4" = none ] || runner "$tmp/att/our-client__xq.retry" "handshakecorruption:$4"
  python3 - "$3" "$5" "$tmp/att/our-client__xq.evidence.json" <<'PY'
import json, sys
e = json.load(open(sys.argv[1]))
e["peer"] = "xq"
t = e["tests"]["handshakecorruption"]
exec(sys.argv[2])
json.dump(e, open(sys.argv[3], "w"))
PY
  local out rc
  out="$(python3 "$script" judge "$tmp/att-exp.json" "$tmp/att")"
  rc=$?
  expect_rc "$1: exit" "$rc" "$7"
  expect "$1" "$out" "$6"
}
move_only='{"kind":"peer-move-never-reached-us","ended_by":"our-idle-timeout","idle_s":30}'
attempts_case "one failed attempt that shows the signature is accepted" "$move_only" "$xa" none '' \
  "| flaky (signature) | our-client | xq | handshakecorruption | failed | flaky | quiche-client-b3343f80e8af00001784df4e5e4c4627.sqlog: everything of ours acknowledged" 0
attempts_case "one failed attempt that does not show the signature is a regression" "$move_only" "$xa" none 't["connections"][0]["wire"]["peer_delivered_after_last_rx"] = 1' \
  "| REGRESSION | our-client | xq | handshakecorruption | failed | flaky | failed, but not as peer-move-never-reached-us" 1
attempts_case "two failed attempts that each show a listed signature are accepted" "$both" "$xt" failed '' \
  "attempt 1: peer-move-never-reached-us: quiche-client-b3343f80e8af00001784df4e5e4c4627.sqlog" 0
attempts_case "each attempt's own signature is named" "$both" "$xt" failed '' \
  "attempt 2: peer-packets-lost-while-we-probed-on-time: quiche-client-7893e318b57de01a3a725eaba6620052.sqlog" 0
attempts_case "a second attempt the signature does not show is a regression though the first shows it" "$move_only" "$xt" failed '' \
  "| REGRESSION | our-client | xq | handshakecorruption | failed | flaky | failed in attempt 2, but not as peer-move-never-reached-us" 1
attempts_case "a first attempt the signature does not show is a regression though the second shows it" "$move_only" "$xt" failed \
  'e["tests"]["handshakecorruption"] = dict(t["retry"], retry=t)' \
  "| REGRESSION | our-client | xq | handshakecorruption | failed | flaky | failed in attempt 1, but not as peer-move-never-reached-us" 1
attempts_case "a failed retry with no evidence of it is a regression" "$both" "$xa" failed '' \
  "| REGRESSION | our-client | xq | handshakecorruption | failed | flaky | no evidence of attempt 2 to check the any-of signature against" 1
attempts_case "a retry that passed leaves the first attempt's failure as flaky" "$move_only" "$xt" succeeded '' \
  "| flaky | our-client | xq | handshakecorruption | retried | flaky |" 0
# The checked-in expectation for xquic's handshakecorruption accepts the two real response-lost connections and
# nothing that is not the peer's move.
real_cell() { # <name> <evidence file> <python on e and t> <row text>
  rm -rf "$tmp/real" && mkdir "$tmp/real"
  runner "$tmp/real/our-client__xquic.json" handshakecorruption:failed
  python3 - "$2" "$3" "$tmp/real/our-client__xquic.evidence.json" <<'PY'
import json, sys
e = json.load(open(sys.argv[1]))
t = e["tests"]["handshakecorruption"]
exec(sys.argv[2])
json.dump(e, open(sys.argv[3], "w"))
PY
  expect "$1" "$(python3 "$script" judge "$here/../../../test-harness/quic-interop/expectations.json" "$tmp/real")" "$4"
}
row="| flaky (signature) | our-client | xquic | handshakecorruption | failed | flaky |"
reg="| REGRESSION | our-client | xquic | handshakecorruption | failed | flaky |"
real_cell "the expectation accepts the first real connection" "$xa" '' "$row"
real_cell "the expectation accepts the second real connection" "$xb" '' "$row"
real_cell "the expectation rejects data of ours in flight" "$xa" 't["connections"][0]["bytes_in_flight_before_close"] = 1200' "$reg"
real_cell "the expectation rejects a peer datagram that arrived after our last receipt" "$xb" 't["connections"][0]["wire"]["peer_delivered_after_last_rx"] = 1' "$reg"
real_cell "the expectation rejects a client that never held HANDSHAKE_DONE nor a Handshake ACK" "$xa" 'c = t["connections"][0]; del c["handshake_done_rx_ms"]; c["rx_handshake_acks"] = 0' "$reg"
real_cell "the expectation rejects the Finished-lost connection of the second attempt" "$xf" '' "$reg"

# The extractor keeps the retry's own record beside the first attempt's, and nothing when there was none.
python3 -I "$extract" "$tmp/logs2" our-client p "$tmp/ev-retry.json" "$tmp/logs2"
expect_rc "the extractor reads a retry's logs" $? 0
expect "the retry's record has the shape of the first attempt's" "$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["handshake"]; r=t["retry"]; print(sorted(set(t)-{"retry"}) == sorted(r), len(r["connections"]) == len(t["connections"]), "retry" in r)' "$tmp/ev-retry.json")" "True True False"
python3 -I "$extract" "$tmp/logs2" our-client p "$tmp/ev-noretry.json" "$tmp/no-such-retry-logs"
expect "a test with no retry has no retry record" "$(python3 -c 'import json,sys; print(any("retry" in t for t in json.load(open(sys.argv[1]))["tests"].values()))' "$tmp/ev-noretry.json")" "False"
python3 -I "$extract" "$tmp/logs2" our-client p "$tmp/ev-plain.json"
expect "the four-argument form is unchanged" "$(python3 -c 'import json,sys; print(any("retry" in t for t in json.load(open(sys.argv[1]))["tests"].values()))' "$tmp/ev-plain.json")" "False"

bad="$(python3 "$script" judge <(echo '{"our-client":{"p":{"handshakeloss":{"flaky":{"why":"x","signature":{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"peer-gave-up","idle_s":30}}}}}}') "$tmp/ok" 2>&1 >/dev/null)"
expect "peer-gave-up takes no idle_s" "$bad" "takes exactly ['ended_by']"
bad="$(python3 "$script" judge <(echo '{"our-client":{"p":{"handshakeloss":{"flaky":{"why":"x","signature":{"kind":"peer-packets-lost-while-we-probed-on-time","ended_by":"whenever"}}}}}}') "$tmp/ok" 2>&1 >/dev/null)"
expect "ended_by is a closed set" "$bad" "ended_by is one of ['our-idle-timeout', 'peer-gave-up', 'peer-closed']"

# Run 45430954348, our client against kwik, rebind-port. Attempt 1: the transfer finished at the simulator's last
# rebinding, our client closed with NO_ERROR from the new port, kwik answered that CONNECTION_CLOSE with a
# PATH_CHALLENGE (its qlog: close received at 10384 ms, challenge sent at 10385 ms) and the runner failed the test
# for a challenge without a response. Attempt 2: the rebinding dropped the 15 datagrams in flight to us, we had
# nothing of ours in flight to send, and kwik retransmitted to the old port for 30 s until our idle timeout.
rebind="$here/fixtures/quic-interop-rebind-kwik"
unchallenged='{"kind":"peer-challenge-never-reached-our-open-connection"}'
closed="$rebind/closed-before-the-challenge.evidence.json"
check_kind "a challenge that came after our close" "$unchallenged" our-client "$(cat "$closed")" Matched
check_kind "a challenge we received and did not answer is ours" "$unchallenged" our-client "$(sed 's/"path_responses_tx": 2/"path_responses_tx": 1/' "$closed")" NotMatched
check_kind "a client that failed is not the signature" "$unchallenged" our-client "$(sed 's/"client": 0/"client": 1/' "$closed")" NotMatched
check_kind "a close with an error is not the signature" "$unchallenged" our-client "$(sed 's/"code": 0,/"code": 10,/' "$closed")" NotMatched
check_kind "a connection we never closed is not the signature" "$unchallenged" our-client "$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["connections"][0]["local_closes"]=[]; print(json.dumps(d))' "$closed")" NotMatched
check_kind "no unanswered challenge is not the signature" "$unchallenged" our-client "$(sed 's/"unanswered_path_challenges": 1/"unanswered_path_challenges": 0/' "$closed")" NotMatched
check_kind "a request of ours left unfinished is not the signature" "$unchallenged" our-client "$(sed 's/"our_streams_unfinished": 0/"our_streams_unfinished": 1/' "$closed")" NotMatched
stalled='{"kind":"peer-move-never-reached-us","ended_by":"our-idle-timeout","idle_s":30}'
check_kind "a flight lost to the rebinding, then 30 s of silence" "$stalled" our-client "$(cat "$rebind/stalled-after-rebind.evidence.json")" Matched
expect "kwik rebind-port expects both of its failures" "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["our-client"]["kwik"]["rebind-port"]["flaky"]["signature"]; print(sorted(x["kind"] for x in s["signatures"]))' "$here/../../../test-harness/quic-interop/expectations.json")" "['peer-challenge-never-reached-our-open-connection', 'peer-move-never-reached-us']"

# The extractor counts the challenges that reached our connection and the responses it sent, and the runner's
# verdict on a challenge that got no response.
d="$tmp/logs3/p_ditchoom/rebind-port"
mkdir -p "$d/client/qlog"
{
  printf '{"time":10,"name":"quic:packet_received","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"path_challenge"}]}}\n'
  printf '{"time":11,"name":"quic:packet_sent","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"path_response"}]}}\n'
  printf '{"time":20,"name":"quic:packet_received","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"path_challenge"},{"frame_type":"stream","stream_id":1}]}}\n'
  printf '{"time":21,"name":"quic:packet_sent","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"path_response"}]}}\n'
  printf '{"time":30,"name":"quic:packet_sent","data":{"header":{"packet_type":"1RTT"},"frames":[{"frame_type":"connection_close","error_space":"transport","error_code":0}]}}\n'
} >"$d/client/qlog/quiche-client-4.sqlog"
printf "2026-11-24 16:14:55,150 PATH_CHALLENGE without a PATH_RESPONSE: ['ea:66:37:ec:24:d1:0e:f3']\nclient exited with code 0\n" >"$d/output.txt"
python3 -I "$extract" "$tmp/logs3" our-client p "$tmp/ev3.json"
expect_rc "the extractor reads a rebinding test" $? 0
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["rebind-port"]; c=t["connections"][0]; print(c["path_challenges_rx"], c["path_responses_tx"], t["runner"], t["exits"])' "$tmp/ev3.json")"
expect "the extractor counts challenges, responses and the runner's unanswered challenge" "$ev" "2 2 {'unanswered_path_challenges': 1} {'client': 0}"

# neqo's zerortt against our server (2026-11-26): the client declares its own 0-RTT stream data lost about 5 ms
# after sending it, before any ACK can arrive, and re-sends it in 1-RTT, so the runner fails it for sending too
# much there. The fixtures are the real failing run trimmed to the relevant lines; the two negatives change one
# fact each (no early Lost line; a 0-RTT packet our server dropped), and both still carry the runner's symptom.
zr="$here/fixtures/quic-interop-zerortt-neqo"
zrkind='{"kind":"peer-resent-0rtt-it-declared-lost"}'
for v in failing no-early-lost server-dropped-0rtt; do
  python3 -I "$extract" "$zr/$v/logs" our-server neqo "$tmp/zr-$v.json"
  expect_rc "the extractor reads the zerortt $v capture" $? 0
done
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["zerortt"]; c=t["connections"][0]; print(t["runner"], t["peer_resumed_connections"], c["rx_0rtt_packets"], c["dropped_0rtt_packets"])' "$tmp/zr-failing.json")"
expect "the extractor reads the runner's verdict, the client's losses before its first ACK and our 0-RTT receipts" "$ev" "{'one_rtt_overrun': True} [{'connection': '58028b24b8e2d20c', 'lost_stream_before_first_ack': 6}] 2 0"
ev="$(python3 -c 'import json,sys; t=json.load(open(sys.argv[1]))["tests"]["zerortt"]; print(t["runner"], t["peer_resumed_connections"], t["connections"][0]["dropped_0rtt_packets"])' "$tmp/zr-no-early-lost.json")"
expect "no Lost line means none before the first ACK" "$ev" "{'one_rtt_overrun': True} [{'connection': '58028b24b8e2d20c', 'lost_stream_before_first_ack': 0}] 0"
ev="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["tests"]["zerortt"]["connections"][0]["dropped_0rtt_packets"])' "$tmp/zr-server-dropped-0rtt.json")"
expect "the extractor counts the 0-RTT packet our server dropped" "$ev" "1"
zr_case() { # <name> <fixture> <expected>
  expect "$1" "$(python3 - "$script" "$zrkind" "$tmp/zr-$2.json" "$1" <<'PY'
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("gate", sys.argv[1])
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
raw = json.loads(sys.argv[2])
kind = raw.pop("kind")
test = json.load(open(sys.argv[3]))["tests"]["zerortt"]
print(f"{sys.argv[4]}: {type(gate.SIGNATURES[kind](raw).match(test, 'client')).__name__}")
PY
)" "$1: $3"
}
zr_case "a client that re-sent 0-RTT data it declared lost, with every 0-RTT packet received" failing Matched
zr_case "a runner failure with no early Lost line is not the signature" no-early-lost NotMatched
zr_case "a 0-RTT packet our server dropped is not the signature" server-dropped-0rtt NotMatched
# The checked-in expectation: a matching failure and a pass are green, any other failure is red.
python3 - "$here/../../../test-harness/quic-interop/expectations.json" "$tmp/zr-exp.json" <<'PY'
import json, sys
entry = json.load(open(sys.argv[1]))["our-server"]["neqo"]["zerortt"]
json.dump({"our-server": {"neqo": {"zerortt": entry}}}, open(sys.argv[2], "w"))
PY
mkdir "$tmp/zr"
runner "$tmp/zr/our-server__neqo.json" zerortt:failed
cp "$tmp/zr-failing.json" "$tmp/zr/our-server__neqo.evidence.json"
out="$(python3 "$script" judge "$tmp/zr-exp.json" "$tmp/zr" --stale=fail)"
expect_rc "the checked-in zerortt expectation keeps the matching failure green" $? 0
expect "the matching failure is shown as a proven flaky" "$out" "| flaky (signature) | our-server | neqo | zerortt | failed | flaky |"
runner "$tmp/zr/our-server__neqo.json" zerortt:succeeded
out="$(python3 "$script" judge "$tmp/zr-exp.json" "$tmp/zr" --stale=fail)"
expect_rc "the checked-in zerortt expectation keeps a pass green" $? 0
runner "$tmp/zr/our-server__neqo.json" zerortt:failed
for v in no-early-lost server-dropped-0rtt; do
  cp "$tmp/zr-$v.json" "$tmp/zr/our-server__neqo.evidence.json"
  out="$(python3 "$script" judge "$tmp/zr-exp.json" "$tmp/zr" --stale=fail)"
  expect_rc "a zerortt failure shown as $v fails the gate" $? 1
  expect "a zerortt failure shown as $v is a regression" "$out" "| REGRESSION | our-server | neqo | zerortt | failed | flaky | failed, but not as peer-resent-0rtt-it-declared-lost"
done

echo "quic-interop-gate: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
