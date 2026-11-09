#!/usr/bin/env bash
#
# Tests for scripts/emulator-shutdown.sh, driven by a fake adb and a fake qemu-system process.
#
# The script must end the emulator in bounded time either way: a healthy emulator that exits on
# `adb emu kill` leaves no capture; a wedged one that acknowledges the kill and stays alive (#665, the
# benchmark lane's 25-minute timeout in run 42633798118) is captured, SIGKILLed, and reported as a
# warning, with exit status 0 so it never becomes the caller's test result.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../../../scripts/emulator-shutdown.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }

tmp="$(mktemp -d)"
fake_qemu=""
cleanup() { [ -n "$fake_qemu" ] && kill -9 "$fake_qemu" 2>/dev/null; rm -rf "$tmp"; }
trap cleanup EXIT

# A name no real emulator has, so the script under test can only ever act on the fake.
fake_name="qemu-system-fake-$$"

mkdir -p "$tmp/bin"
# `adb emu kill`: acknowledge, and kill the fake emulator only when it is healthy.
cat >"$tmp/bin/adb" <<EOF2
#!/usr/bin/env bash
if [ "\$*" = "emu kill" ]; then
  echo "OK: killing emulator, bye bye"
  [ -f "$tmp/healthy" ] && pkill -f $fake_name
  exit 0
fi
exit 0
EOF2
chmod +x "$tmp/bin/adb"
# macOS has no coreutils `timeout`; the CI runner does. Unbounded is fine for a fake adb.
if ! command -v timeout >/dev/null 2>&1; then
  printf '#!/usr/bin/env bash\nshift\nexec "$@"\n' >"$tmp/bin/timeout"
  chmod +x "$tmp/bin/timeout"
fi

start_fake_qemu() {
  bash -c 'exec -a '"$fake_name"' sleep 300' &
  fake_qemu=$!
  sleep 0.2
}

run() { # -> OUT, RC, SECS
  local t0=$SECONDS
  OUT=$(cd "$tmp" && PATH="$tmp/bin:$PATH" EMULATOR_QEMU_PATTERN="$fake_name" EMULATOR_FAMILY_PATTERN="$fake_name" EMULATOR_KILL_GRACE_S=2 EMULATOR_SIGKILL_GRACE_S=2 bash "$script" 34 1 2>&1)
  RC=$?
  SECS=$((SECONDS - t0))
}

# 1. Healthy: exits on `adb emu kill`; no capture, no warning.
touch "$tmp/healthy"
start_fake_qemu
run
if [ "$RC" = "0" ] && ! kill -0 "$fake_qemu" 2>/dev/null &&
  [ ! -e "$tmp/emulator-diagnostics/emulator-shutdown-wedge-api34.txt" ] &&
  grep -q "exited within 2s" <<<"$OUT" && ! grep -q "::warning" <<<"$OUT"; then
  ok "a healthy emulator is shut down with no capture"
else
  bad "a healthy emulator is shut down with no capture" "rc=$RC" "$OUT"
fi
kill -9 "$fake_qemu" 2>/dev/null
wait "$fake_qemu" 2>/dev/null

# 2. Wedged: acknowledges the kill and stays alive. Captured, SIGKILLed, warned about, exit 0, bounded.
rm -f "$tmp/healthy"
start_fake_qemu
run
if [ "$RC" = "0" ] && ! kill -0 "$fake_qemu" 2>/dev/null &&
  [ -s "$tmp/emulator-diagnostics/emulator-shutdown-wedge-api34.txt" ] &&
  grep -q "::warning title=Emulator shutdown wedge (API 34, tests exit 1)" <<<"$OUT" &&
  grep -q "SIGKILLed qemu-system; it exited." <<<"$OUT" && [ "$SECS" -lt 30 ]; then
  ok "a wedged emulator is captured, SIGKILLed and reported as a warning, in ${SECS}s"
else
  bad "a wedged emulator is captured, SIGKILLed and reported as a warning" "rc=$RC secs=$SECS alive=$(kill -0 "$fake_qemu" 2>/dev/null && echo yes || echo no)" "$OUT"
fi
kill -9 "$fake_qemu" 2>/dev/null
wait "$fake_qemu" 2>/dev/null

echo "$pass passed, $fail failed"
[ "$fail" = "0" ]
