#!/usr/bin/env bash
#
# Tests for scripts/adb-install-guard.sh's pass-through and its adb journal, driven by a fake adb.
#
# The guard stands in for the SDK's adb on the emulator lane, so everything AGP's test engine runs
# goes through it. A pass-through must stay transparent (stdout, stdin, stderr, exit status, and a
# SIGTERM from Process.destroy() reaching adb), and each command must land in the journal with its
# status, so a failed one can be named when a module ran no tests.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
guard_src="$here/../../../scripts/adb-install-guard.sh"
pass=0
fail=0

ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
diag="$tmp/diag"
journal="$diag/adb-journal.txt"

# The fake adb: `shell <what>` does what the test asks for; `install ... <apk>` succeeds.
cat >"$tmp/adb.real" <<EOF
#!/usr/bin/env bash
while [ "\$1" = "-s" ]; do shift 2; done
case "\$1 \${2:-}" in
  "shell ok") echo "out-ok"; exit 0 ;;
  "shell fail") echo "out-before-fail"; echo "error: device offline" >&2; exit 3 ;;
  "shell cat") cat; exit 0 ;;
  "shell sleep") echo \$\$ >"$tmp/sleeper.pid"; sleep 30; exit 0 ;;
  "install "*) echo "Success"; exit 0 ;;
  *) echo "unexpected: \$*" >&2; exit 99 ;;
esac
EOF
chmod +x "$tmp/adb.real"
sed -e "s#@REAL_ADB@#$tmp/adb.real#" -e "s#@DIAG_DIR@#$diag#" \
  -e "s#@STALL_S@#20#" -e "s#@RETRY_S@#20#" "$guard_src" >"$tmp/adb"
chmod +x "$tmp/adb"

# 1. A command that succeeds: its stdout and status pass through, and the journal records both ends.
out=$("$tmp/adb" -s emulator-5554 shell ok 2>"$tmp/err")
rc=$?
if [ "$rc" = "0" ] && [ "$out" = "out-ok" ] && [ ! -s "$tmp/err" ]; then
  ok "a succeeding command passes its stdout and exit 0 through"
else
  bad "a succeeding command passes its stdout and exit 0 through" "rc=$rc out=$out err=$(cat "$tmp/err")"
fi
if grep -q 'start pid=[0-9]* caller=.* adb -s emulator-5554 shell ok$' "$journal" &&
  grep -q 'end   pid=[0-9]* rc=0 [0-9]*ms$' "$journal"; then
  ok "the journal records a succeeding command's start and its rc=0"
else
  bad "the journal records a succeeding command's start and its rc=0" "$(cat "$journal" 2>/dev/null)"
fi

# 2. A command that fails: status, stdout and stderr pass through, and the journal names it.
out=$("$tmp/adb" -s emulator-5554 shell fail 2>"$tmp/err")
rc=$?
if [ "$rc" = "3" ] && [ "$out" = "out-before-fail" ] && [ "$(cat "$tmp/err")" = "error: device offline" ]; then
  ok "a failing command passes its exit status, stdout and stderr through"
else
  bad "a failing command passes its exit status, stdout and stderr through" "rc=$rc out=$out err=$(cat "$tmp/err")"
fi
if grep -q 'rc=3 [0-9]*ms FAILED adb -s emulator-5554 shell fail | stderr: error: device offline' "$journal"; then
  ok "the journal names a failed command with its status and stderr"
else
  bad "the journal names a failed command with its status and stderr" "$(cat "$journal")"
fi

# 3. stdin reaches adb (a backgrounded command would otherwise read /dev/null).
out=$(printf 'from-stdin\n' | "$tmp/adb" shell cat)
if [ "$out" = "from-stdin" ]; then
  ok "stdin reaches adb"
else
  bad "stdin reaches adb" "out=$out"
fi

# 4. SIGTERM to the guard (Process.destroy()) stops adb too, and the guard exits.
rm -f "$tmp/sleeper.pid"
"$tmp/adb" shell sleep >/dev/null 2>&1 &
guard_pid=$!
for _ in $(seq 50); do [ -s "$tmp/sleeper.pid" ] && break; sleep 0.1; done
sleeper=$(cat "$tmp/sleeper.pid" 2>/dev/null || true)
kill -TERM "$guard_pid"
gone=""
for _ in $(seq 50); do
  if ! kill -0 "$guard_pid" 2>/dev/null && { [ -z "$sleeper" ] || ! kill -0 "$sleeper" 2>/dev/null; }; then
    gone=1
    break
  fi
  sleep 0.1
done
if [ -n "$sleeper" ] && [ -n "$gone" ]; then
  ok "SIGTERM to the guard stops adb and the guard"
else
  bad "SIGTERM to the guard stops adb and the guard" "sleeper=${sleeper:-never started} guard alive=$(kill -0 "$guard_pid" 2>/dev/null && echo yes || echo no)"
  [ -n "$sleeper" ] && kill -9 "$sleeper" 2>/dev/null
fi
wait "$guard_pid" 2>/dev/null

# 5. An install goes through the install path, and is journaled too.
touch "$tmp/test.apk"
out=$("$tmp/adb" -s emulator-5554 install -t -g "$tmp/test.apk" 2>&1)
rc=$?
if [ "$rc" = "0" ] && [ "$out" = "Success" ] &&
  grep -q '(install guard) adb -s emulator-5554 install -t -g' "$journal" &&
  grep -q '(install guard) rc=0$' "$journal"; then
  ok "an install passes through and is journaled"
else
  bad "an install passes through and is journaled" "rc=$rc out=$out" "$(cat "$journal")"
fi

echo "$pass passed, $fail failed"
[ "$fail" = "0" ]
