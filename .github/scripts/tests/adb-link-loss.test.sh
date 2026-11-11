#!/usr/bin/env bash
#
# Tests for the lost-adb-link handling: scripts/adb-link-loss.sh names a run whose test engine lost its
# adb link before installing anything, and scripts/adb-install-guard.sh records what the host and the
# device looked like when an adb command ended with 255, without changing what the engine sees.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
guard_src="$here/../../../scripts/adb-install-guard.sh"
# shellcheck source=scripts/adb-link-loss.sh
source "$here/../../../scripts/adb-link-loss.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Verbatim from the journal of an API 24 run whose engine lost its link: healthy device queries, then
# two shell commands ending 255 within 50 ms, and no install after them.
cat >"$tmp/lost.txt" <<'EOF'
00:02:24.246 start pid=11849 caller=java(4736) adb -s emulator-5554 emu avd id
00:02:24.272 end   pid=11849 rc=0 24ms
00:02:27.610 start pid=11964 caller=java(11877) adb -s emulator-5554 shell getprop ro.build.version.release
00:02:27.651 end   pid=11964 rc=0 43ms
00:02:27.773 start pid=11985 caller=java(11877) adb -s emulator-5554 shell getprop
00:02:27.851 end   pid=11985 rc=0 79ms
00:02:27.936 start pid=12005 caller=java(11877) adb -s emulator-5554 shell cat /proc/meminfo
00:02:27.970 end   pid=12005 rc=0 35ms
00:02:28.023 start pid=12025 caller=java(11877) adb -s emulator-5554 shell cat /proc/cpuinfo
00:02:28.085 end   pid=12025 rc=255 51ms FAILED adb -s emulator-5554 shell cat /proc/cpuinfo | stderr: 
00:02:28.266 start pid=12048 caller=java(11877) adb -s emulator-5554 shell getprop ro.build.version.sdk
00:02:28.321 end   pid=12048 rc=255 46ms FAILED adb -s emulator-5554 shell getprop ro.build.version.sdk | stderr: 
EOF
# The same queries followed by an install: the 255 was survived.
cat >"$tmp/survived.txt" <<'EOF'
00:02:24.246 start pid=11849 caller=java(4736) adb -s emulator-5554 emu avd id
00:02:24.272 end   pid=11849 rc=0 24ms
00:02:27.610 start pid=11964 caller=java(11877) adb -s emulator-5554 shell getprop ro.build.version.release
00:02:27.651 end   pid=11964 rc=0 43ms
00:02:27.773 start pid=11985 caller=java(11877) adb -s emulator-5554 shell getprop
00:02:27.851 end   pid=11985 rc=0 79ms
00:02:27.936 start pid=12005 caller=java(11877) adb -s emulator-5554 shell cat /proc/meminfo
00:02:27.970 end   pid=12005 rc=0 35ms
00:02:28.023 start pid=12025 caller=java(11877) adb -s emulator-5554 shell cat /proc/cpuinfo
00:02:28.085 end   pid=12025 rc=255 51ms FAILED adb -s emulator-5554 shell cat /proc/cpuinfo | stderr: 
00:02:28.266 start pid=12048 caller=java(11877) adb -s emulator-5554 shell getprop ro.build.version.sdk
00:02:28.321 end   pid=12048 rc=255 46ms FAILED adb -s emulator-5554 shell getprop ro.build.version.sdk | stderr: 
00:02:30.000 start pid=13000 (install guard) adb -s emulator-5554 install -t -g --user 0 /work/app.apk
00:02:31.000 end   pid=13000 (install guard) rc=0
EOF
# Healthy queries only.
head -n 6 "$tmp/lost.txt" >"$tmp/healthy.txt"

if out=$(engine_lost_adb_link "$tmp/lost.txt") && grep -q 'getprop ro.build.version.sdk' <<<"$out"; then
  ok "a 255 on a device query with no install after it is a lost link, naming the command"
else
  bad "a 255 with no install after it was not reported" "$out"
fi
if engine_lost_adb_link "$tmp/survived.txt" >/dev/null; then
  bad "a 255 followed by an install was reported"
else
  ok "a 255 followed by an install is not reported"
fi
if engine_lost_adb_link "$tmp/healthy.txt" >/dev/null; then
  bad "a journal with no 255 was reported"
else
  ok "a journal with no 255 is not reported"
fi
if engine_lost_adb_link "$tmp/missing.txt" >/dev/null; then
  bad "a missing journal was reported"
else
  ok "a missing journal is not reported"
fi

# The guard, over a fake adb whose `shell lose` ends 255 as the real one did.
diag="$tmp/diag"
cat >"$tmp/adb.real" <<EOF2
#!/usr/bin/env bash
[ -n "\${ADB_TRACE:-}" ] && echo "trace-line for \$*" >&2
while [ "\$1" = "-s" ]; do shift 2; done
case "\$1 \${2:-}" in
  "shell lose") echo "stdout-of-lose"; exit 255 ;;
  "shell echo") echo ok; exit 0 ;;
  "get-state "*) echo device; exit 0 ;;
  "get-state") echo device; exit 0 ;;
  "devices -l") echo "emulator-5554 device"; exit 0 ;;
  "shell fine") echo "fine"; exit 0 ;;
  *) echo "unexpected: \$*" >&2; exit 99 ;;
esac
EOF2
chmod +x "$tmp/adb.real"
sed -e "s#@REAL_ADB@#$tmp/adb.real#" -e "s#@DIAG_DIR@#$diag#" \
  -e "s#@STALL_S@#20#" -e "s#@RETRY_S@#20#" "$guard_src" >"$tmp/adb"
chmod +x "$tmp/adb"

out=$("$tmp/adb" -s emulator-5554 shell lose 2>"$tmp/err"); rc=$?
if [ "$rc" = "255" ] && [ "$out" = "stdout-of-lose" ] && [ ! -s "$tmp/err" ]; then
  ok "the engine still sees the command's status, stdout and (empty) stderr"
else
  bad "the guard changed what the engine sees" "rc=$rc out=$out err=$(cat "$tmp/err")"
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ -e "$diag/adb-link-loss-1.txt" ] && grep -q 'adb shell echo ok, traced' "$diag/adb-link-loss-1.txt" && break
  sleep 1
done
report="$diag/adb-link-loss-1.txt"
for want in 'adb ended with status 255: adb -s emulator-5554 shell lose' '--- adb get-state ---' '--- adb devices -l ---' \
  '--- adb shell echo ok ---' '--- adb server processes' '--- emulator processes' 'trace-line for' '[exit 0]'; do
  if grep -qF -- "$want" "$report" 2>/dev/null; then ok "the capture holds: $want"; else bad "the capture lacks: $want" "$(cat "$report" 2>/dev/null)"; fi
done
if grep -q 'ADB_TRACE' "$diag/adb-journal.txt"; then bad "trace output reached the journal"; else ok "no trace output in the journal"; fi

"$tmp/adb" -s emulator-5554 shell fine >/dev/null 2>&1
if [ -e "$diag/adb-link-loss-2.txt" ]; then bad "a command that succeeded produced a capture"; else ok "a successful command produces no capture"; fi

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
