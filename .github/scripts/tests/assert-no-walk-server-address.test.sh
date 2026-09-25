#!/usr/bin/env bash
#
# The repository names no forbidden address, and the check really finds one: proved against a
# throwaway repository and a hash list of documentation prefixes, so no real address appears here.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../assert-no-walk-server-address.py"
root="$(cd "$here/../../.." && pwd)"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> <needle>
  local name="$1" want="$2" got="$3" out="$4" needle="$5"
  if [ "$want" != "$got" ] || ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL: $name — expected exit $want and '$needle', got exit $got"; echo "$out" | sed 's/^/    /'
    fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

hash() { printf %s "$1" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'; }

# The real repository, with the built-in list.
out="$(python3 "$script" --root "$root" 2>&1)"; rc=$?
check "tracked files name no forbidden address" 0 "$rc" "$out" "ok:"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q
{ hash "v4/24:192.0.2"; hash "v6/48:2001:db8:7"; hash "v6/32:2001:db9"; } > "$tmp/hashes"

run() { # <file-content> -> OUT/RC
  printf '%s\n' "$1" > "$tmp/f.txt"
  git -C "$tmp" add f.txt
  OUT="$(python3 "$script" --root "$tmp" --hashes "$tmp/hashes" 2>&1)"; RC=$?
}

run 'host = "192.0.2.77:44433"'
check "an IPv4 in a forbidden /24 fails" 1 "$RC" "$OUT" "f.txt:1: a forbidden v4/24"
run 'SERVER_HOST="198.51.100.1,[2001:0DB8:07::1]:44433"'
check "an IPv6 in a forbidden /48 fails, however it is written" 1 "$RC" "$OUT" "a forbidden v6/48"
run 'local=2001:db9:ffff::3'
check "an IPv6 in a forbidden /32 fails" 1 "$RC" "$OUT" "a forbidden v6/32"
run 'SERVER_HOST="198.51.100.1,2001:db8:8::1" version 1.192.0.2'
check "neighbouring prefixes pass" 0 "$RC" "$OUT" "ok:"
grep -q "192.0.2" <<<"$OUT" && { echo "FAIL: the check printed an address"; fail=$((fail + 1)); }

echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
