#!/usr/bin/env bash
#
# Tests for patch-pyshark-reap.py: it rewrites pyshark's cleanup once, is a no-op the second time, leaves a
# file that still compiles, and fails loudly on a cleanup it does not recognise.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../patch-pyshark-reap.py"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; fail=$((fail + 1)); }

cat >"$tmp/capture.py" <<'PY'
import asyncio
import os


class Capture:
    async def _cleanup_subprocess(self, process):
        if process.returncode is None:
            try:
                process.kill()
                return await asyncio.wait_for(process.wait(), 1)
            except ProcessLookupError:
                pass
PY

python3 -I "$script" "$tmp/capture.py" >/dev/null && grep -q "os.kill(process.pid, _signal.SIGKILL)" "$tmp/capture.py" &&
  ! grep -q "process.kill()$" "$tmp/capture.py" && ok "the kill is rewritten" || bad "the kill is rewritten"
python3 -I -m py_compile "$tmp/capture.py" && ok "the patched file compiles" || bad "the patched file compiles"
before=$(cksum <"$tmp/capture.py")
python3 -I "$script" "$tmp/capture.py" | grep -q "already patched" && [ "$before" = "$(cksum <"$tmp/capture.py")" ] &&
  ok "a second run changes nothing" || bad "a second run changes nothing"

printf 'import os\n\ndef cleanup(process):\n    process.terminate()\n' >"$tmp/other.py"
python3 -I "$script" "$tmp/other.py" >/dev/null 2>"$tmp/err"
[ $? = 1 ] && grep -q "not found exactly once" "$tmp/err" && ok "an unrecognised cleanup fails loudly" || bad "an unrecognised cleanup fails loudly"

echo "patch-pyshark-reap: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
