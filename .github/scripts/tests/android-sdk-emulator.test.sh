#!/usr/bin/env bash
#
# Tests for scripts/android-sdk-emulator.sh verify, driven by a fake SDK: a working emulator passes; a
# failing version check reports its exit status, stderr and the emulator processes alive, without a second
# try when there are none; with another emulator still running it is tried once more.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../../../scripts/android-sdk-emulator.sh"
tmp="$(mktemp -d)"
trap 'kill "${other:-}" 2>/dev/null; rm -rf "$tmp"' EXIT
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; printf '    %s\n' "$@"; fail=$((fail + 1)); }

sdk="$tmp/sdk"
mkdir -p "$sdk/emulator" "$sdk/system-images/android-29/default/x86_64"
echo img >"$sdk/system-images/android-29/default/x86_64/system.img"
echo "Pkg.Revision=36.1.9" >"$sdk/emulator/source.properties"
# The fake emulator fails while $tmp/fail-times holds a positive count, then reports a version.
cat >"$sdk/emulator/emulator" <<EOF2
#!/usr/bin/env bash
n=\$(cat "$tmp/fail-times" 2>/dev/null || echo 0)
echo "run" >>"$tmp/runs"
if [ "\$n" -gt 0 ]; then
  echo \$((n - 1)) >"$tmp/fail-times"
  echo "qemu: could not lock the avd" >&2
  exit 3
fi
echo "Android emulator version 36.1.9.0 (build_id 1)"
EOF2
chmod +x "$sdk/emulator/emulator"
# A host without coreutils' timeout (macOS) gets a pass-through one.
mkdir -p "$tmp/bin"
if ! command -v timeout >/dev/null; then
  printf '#!/usr/bin/env bash\nshift\nexec "$@"\n' >"$tmp/bin/timeout"
  chmod +x "$tmp/bin/timeout"
fi
run() { PATH="$tmp/bin:$PATH" ANDROID_HOME="$sdk" EMULATOR_RECHECK_WAIT_S=1 bash "$script" verify 29 2>&1; }

echo 0 >"$tmp/fail-times"; : >"$tmp/runs"
out=$(run); rc=$?
[ "$rc" = 0 ] && grep -q "Android emulator version 36.1.9.0" <<<"$out" && ok "a working emulator verifies" || bad "a working emulator verifies" "rc=$rc" "$out"

echo 5 >"$tmp/fail-times"; : >"$tmp/runs"
out=$(run); rc=$?
if [ "$rc" = 1 ] && grep -q "exited 3" <<<"$out" && grep -q "stderr: qemu: could not lock the avd" <<<"$out" &&
  grep -q "installed: Pkg.Revision=36.1.9" <<<"$out" && grep -q "emulator processes at that moment: none" <<<"$out" &&
  [ "$(wc -l <"$tmp/runs" | tr -d ' ')" = 1 ]; then
  ok "a failure with no emulator running is reported once, with its status, stderr and processes"
else
  bad "a failure with no emulator running is reported once, with its status, stderr and processes" "rc=$rc runs=$(wc -l <"$tmp/runs")" "$out"
fi

# Another emulator still running: a process whose command is .../qemu-system-x86_64-headless.
bash -c 'exec -a /sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless sleep 30' &
other=$!
echo 1 >"$tmp/fail-times"; : >"$tmp/runs"
out=$(run); rc=$?
if [ "$rc" = 0 ] && grep -q "passed 1 s later" <<<"$out" && grep -q "qemu-system-x86_64-headless" <<<"$out" &&
  [ "$(wc -l <"$tmp/runs" | tr -d ' ')" = 2 ]; then
  ok "a failure beside a running emulator is tried once more, and the first failure is still reported"
else
  bad "a failure beside a running emulator is tried once more, and the first failure is still reported" "rc=$rc runs=$(wc -l <"$tmp/runs")" "$out"
fi
echo 5 >"$tmp/fail-times"; : >"$tmp/runs"
out=$(run); rc=$?
[ "$rc" = 1 ] && grep -q -- "--- again after 1 s ---" <<<"$out" && [ "$(wc -l <"$tmp/runs" | tr -d ' ')" = 2 ] &&
  ok "a second failure fails, with both reports" || bad "a second failure fails, with both reports" "rc=$rc" "$out"

echo "android-sdk-emulator: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
