#!/usr/bin/env bash
#
# Tests for audit-android-quic-harness.py, against fixtures shaped like what AGP 9.5 writes for an
# instrumented run: `TEST-test(AVD) - <ver>.xml` with a bare `<skipped />`, and the skip reason only
# in the per-test `logcat-<class>-<method>.txt` beside it.
#
# The case that matters most is the first failing one: a harness class that is absent from the
# results must fail. Its absence is exactly what the old `OK=0, SKIP=0` line looked like.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../audit-android-quic-harness.py"
root="$(cd "$here/../../.." && pwd)"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> <needle>...
  local name="$1" want="$2" got="$3" out="$4"
  shift 4
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  local needle
  for needle in "$@"; do
    if ! grep -qF -- "$needle" <<<"$out"; then
      echo "FAIL: $name — output missing '$needle'"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
    fi
  done
  echo "ok: $name"; pass=$((pass + 1))
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

src="$tmp/src"
mkdir -p "$src/kotlin/x"
cat >"$src/kotlin/x/AndroidEchoTests.kt" <<'EOF'
package com.example.quic
class AndroidEchoTests { fun f() = HarnessEndpoints.quicEcho.addressOrSkip(AndroidEchoTests::class) }
EOF
cat >"$src/kotlin/x/HarnessEndpoints.kt" <<'EOF'
package com.example.quic
/** [HarnessEndpoints.quicEcho] is named in its own docs and is not a test class. */
object HarnessEndpoints
EOF
cat >"$src/kotlin/x/AndroidUnrelatedTests.kt" <<'EOF'
package com.example.quic
class AndroidUnrelatedTests
EOF

# <testcase xml> -> a report root holding one result file, plus whatever logcat files follow.
report() {
  local dir="$tmp/report-$RANDOM"
  mkdir -p "$dir/connected/debug/test(AVD) - 16"
  {
    echo "<?xml version='1.0' encoding='UTF-8' ?>"
    echo '<testsuites tests="1"><testsuite name="s" tests="1">'
    printf '%s\n' "$1"
    echo '</testsuite></testsuites>'
  } >"$dir/connected/debug/TEST-test(AVD) - 16.xml"
  echo "$dir"
}

run() { # <report-root> [env...] -> OUT/RC
  local r="$1"
  shift
  OUT="$(env "$@" python3 -I "$script" --report-root "$r" --source-dir "$src" --api-level 36 2>&1)"
  RC=$?
}

pass_xml='<testcase name="echo" classname="com.example.quic.AndroidEchoTests" time="0.1" />'
skip_xml='<testcase name="echo" classname="com.example.quic.AndroidEchoTests" time="0.1"><skipped /></testcase>'
other_xml='<testcase name="t" classname="com.example.quic.AndroidUnrelatedTests" time="0.1" />'

r="$(report "$pass_xml")"
run "$r"
check "a harness class that passed is clean, and the line says why QuicHarnessIntegrationTests is absent" 0 "$RC" "$OUT" \
  "not run, by design" "com.example.quic.AndroidEchoTests: ran=1 passed=1 skipped=0 failed=0"
if grep -qF "HarnessEndpoints:" <<<"$OUT"; then
  echo "FAIL: the resolver object was audited as a test class"; fail=$((fail + 1))
fi

r="$(report "$other_xml")"
run "$r"
check "a harness class missing from the results fails and names what the results do hold" 1 "$RC" "$OUT" \
  "::error" "has no test in the results" "com.example.quic.AndroidUnrelatedTests"

r="$(report "$skip_xml")"
printf 'I System.out: [TEST-SKIPPED] site=AndroidEchoTests reason=harness-unreachable-from-device detail=quic-echo did not answer at 10.0.2.2:14433\n' \
  >"$r/connected/debug/test(AVD) - 16/logcat-com.example.quic.AndroidEchoTests-echo.txt"
run "$r"
check "every harness test skipped fails and quotes the logcat reason" 1 "$RC" "$OUT" \
  "::error" "Every test in com.example.quic.AndroidEchoTests skipped" "did not answer at 10.0.2.2:14433"

r="$(report "$skip_xml")"
run "$r"
check "a skip with no logcat says the evidence is missing instead of inventing one" 1 "$RC" "$OUT" \
  "no logcat-com.example.quic.AndroidEchoTests-echo.txt"

r="$(report "$pass_xml
<testcase name=\"echo2\" classname=\"com.example.quic.AndroidEchoTests\"><skipped message=\"no netem\" /></testcase>")"
run "$r"
check "a partial skip warns and does not fail" 0 "$RC" "$OUT" "::warning" "xml: no netem"

run "$tmp/absent"
check "no results on a job that otherwise passed fails" 1 "$RC" "$OUT" "::error" "No instrumented-test result XML"

run "$tmp/absent" JOB_STATUS=failure
check "no results on a job that already failed defers to that failure" 0 "$RC" "$OUT" "that earlier failure is the report"

mkdir -p "$tmp/empty-src"
OUT="$(python3 -I "$script" --report-root "$tmp/absent" --source-dir "$tmp/empty-src" 2>&1)"; RC=$?
check "a source tree with no harness class fails rather than passing vacuously" 1 "$RC" "$OUT" "nothing to check"

# The real tree: the classes this lane depends on must still be found.
OUT="$(python3 -I "$script" --list-classes --source-dir "$root/socket-quic-quiche/src/androidInstrumentedTest" 2>&1)"; RC=$?
check "the repository's own harness classes are discovered" 0 "$RC" "$OUT" \
  "com.ditchoom.socket.quic.AndroidQuicConnectivityTests" "com.ditchoom.socket.quic.AndroidQuicMigrationTests"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
