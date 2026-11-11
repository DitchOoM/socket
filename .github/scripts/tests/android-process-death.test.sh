#!/usr/bin/env bash
#
# Tests for scripts/android-process-death.sh: a test process that died is named from the saved logcat.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/android-process-death.sh
source "$here/../../../scripts/android-process-death.sh"
fixtures="$here/fixtures/api24-abort"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

out=$(named_process_deaths "$fixtures/logcat.txt")
for want in 'com.ditchoom.socket.quic.quiche.test' 'SIGABRT' 'quic-loop-0' 'pid 3757' \
  'mark_sweep.cc:413] Tried to mark 0xebadde09 not contained by any spaces' \
  'theServersRoutingTableIsAProjectionOfQuichesSourceIds(com.ditchoom.socket.quic.AndroidSourceIdReadbackTests)'; do
  if grep -qF -- "$want" <<<"$out"; then ok "the report names: $want"; else bad "the report lacks: $want" "$out"; fi
done
if grep -qF 'captureWifiAndAirplaneFlap' <<<"$out"; then
  bad "the last test of ANOTHER process was named" "$out"
else
  ok "the last test is the dead process's own"
fi
if [ "$(wc -l <<<"$out" | tr -d ' ')" = "1" ]; then ok "one death is one line"; else bad "one death was not one line" "$out"; fi

# A death with no native crash recorded (killed from outside): named, with no signal claimed.
grep -v 'Fatal signal\|DEBUG' "$fixtures/logcat.txt" >"$tmp/killed.txt"
out=$(named_process_deaths "$tmp/killed.txt")
if grep -qF 'com.ditchoom.socket.quic.quiche.test' <<<"$out" && grep -qF 'no native crash' <<<"$out" && ! grep -qF SIG <<<"$out"; then
  ok "a death without a native crash says so and claims no signal"
else
  bad "a death without a native crash was misreported" "$out"
fi

# Nothing died: nothing is reported (and a missing file is nothing too).
grep -v 'Fatal signal\|DEBUG\|has died' "$fixtures/logcat.txt" >"$tmp/clean.txt"
if named_process_deaths "$tmp/clean.txt" | grep -q .; then bad "a clean logcat reported a death"; else ok "a clean logcat reports nothing"; fi
if named_process_deaths "$tmp/missing.txt" | grep -q .; then bad "a missing logcat reported a death"; else ok "a missing logcat reports nothing"; fi

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
