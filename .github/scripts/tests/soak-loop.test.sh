#!/usr/bin/env bash
#
# Tests for soak-loop.sh, driven by fake test binaries: one that passes, one that fails on its third
# run, one that hangs, one whose filter matches nothing, and one that prints a [stall] line.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../soak-loop.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0

# A fake kexe whose behaviour is chosen by the first line of its mode file.
cat > "$tmp/kexe" <<'KEXE'
#!/usr/bin/env bash
dir="$(cd "$(dirname "$0")" && pwd)"
mode="$(cat "$dir/mode")"
n=$(($(cat "$dir/count" 2>/dev/null || echo 0) + 1)); echo "$n" > "$dir/count"
case "$mode" in
  pass)    echo "[ RUN      ] a.B.c"; echo "[       OK ] a.B.c (1 ms)"; exit 0 ;;
  failthird)
    echo "[ RUN      ] a.B.c"
    if [ "$n" -eq 3 ]; then echo "phase report: client 7 stuck in handshake"; echo "[stall] cancel-path wait pending"; exit 1; fi
    exit 0 ;;
  hang)    echo "[ RUN      ] a.B.c"; exec sleep 30 ;;
  nothing) echo "[==========] Running 0 tests from 0 test cases."; exit 0 ;;
  stall)   echo "[ RUN      ] a.B.c"; echo "[nw-udp] cancel pending 6s"; exit 0 ;;
esac
KEXE
chmod +x "$tmp/kexe"

run() { # <mode> <budget> <run-timeout> -> sets RC, RES
  echo "$1" > "$tmp/mode"; rm -f "$tmp/count"; rm -rf "$tmp/out"
  "$script" "$tmp/kexe" '*Soak*' "$2" "$3" "$tmp/out" > "$tmp/stdout" 2>&1
  RC=$?
  RES="$(cat "$tmp/out/result.txt" 2>/dev/null)"
}

check() { # <name> <condition-status>
  if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; cat "$tmp/stdout"; echo "$RES"; fail=$((fail + 1)); fi
}

run pass 2 5
[ "$RC" -eq 0 ] && grep -q '^result=ok iterations=[1-9]' <<<"$RES"; check "passing binary spends the budget and exits 0" $?

run failthird 30 5
[ "$RC" -eq 1 ] && grep -q '^result=fail iterations=3 ' <<<"$RES" && grep -q 'phase report: client 7' "$tmp/out/failure.log"; check "stops at the first failure and keeps its full output" $?

run hang 30 1
[ "$RC" -eq 1 ] && grep -q '^result=hang iterations=1 ' <<<"$RES" && [ -f "$tmp/out/failure.log" ]; check "a run past the per-run timeout is a hang" $?

run nothing 30 5
[ "$RC" -eq 1 ] && grep -q '^result=vacuous iterations=1 ' <<<"$RES"; check "a filter that matches nothing is not a pass" $?

run stall 2 5
[ "$RC" -eq 0 ] && grep -q 'stall_runs=[1-9]' <<<"$RES" && grep -q '^iter 1: \[nw-udp\]' "$tmp/out/stall-lines.log"; check "stall lines of passing runs are counted and kept" $?

"$script" "$tmp/nope" f 1 1 "$tmp/o" >/dev/null 2>&1; [ $? -eq 2 ]; check "a missing binary is a usage error" $?
"$script" "$tmp/kexe" f x 1 "$tmp/o" >/dev/null 2>&1; [ $? -eq 2 ]; check "a non-numeric budget is a usage error" $?

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
