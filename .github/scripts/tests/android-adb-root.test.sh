#!/usr/bin/env bash
#
# Tests for scripts/android-adb-root.sh: no device command runs until the adbd that `adb root` restarted
# answers as root steadily. The case is run 45222410247 (API 35): the transport answered, dropped and came
# back within about two seconds of `adb root`.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/android-adb-root.sh
source "$here/../../../scripts/android-adb-root.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; shift; for l in "$@"; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); }
gh_escape() { printf '%s' "$1"; }
sleep() { SECONDS=$((SECONDS + 1)); }
timeout() { shift; "$@"; }

# A fake device: each `adb shell id -u` takes the next answer from ANSWERS ("x" is a dropped transport). The
# count is kept in a file, since the answer is read in a command substitution.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ANSWERS=()
adb() {
  case "$*" in
    root) echo "restarting adbd as root" ;;
    "shell id -u")
      local asked a
      asked=$(cat "$tmp/asked")
      a="${ANSWERS[$asked]:-x}"
      echo $((asked + 1)) >"$tmp/asked"
      if [ "$a" = "x" ]; then echo "adb: device 'emulator-5554' not found" >&2; return 1; fi
      printf '%s\r\n' "$a"
      ;;
  esac
}

# The old adbd answers as shell, the new one as root, then the transport drops once, then it is steady.
ANSWERS=(2000 0 x 0 0 0)
echo 0 >"$tmp/asked"
out=$(adb_root_settled 35 60; echo "asked=$(cat "$tmp/asked")")
if grep -q 'answers as root' <<<"$out" && grep -q 'asked=6' <<<"$out"; then
  ok "a root answer followed by a drop is not steady; three in a row are"
else
  bad "the restart was taken as settled too early or too late" "$out"
fi

ANSWERS=(0 0 0)
echo 0 >"$tmp/asked"
out=$(adb_root_settled 35 60; echo "asked=$(cat "$tmp/asked")")
if grep -q 'answers as root' <<<"$out" && grep -q 'asked=3' <<<"$out"; then
  ok "an adbd that was already root settles at once"
else
  bad "an already-root adbd was not settled" "$out"
fi

ANSWERS=(2000 2000 2000 2000 2000 2000 2000 2000 2000 2000)
echo 0 >"$tmp/asked"
out=$(adb_root_settled 35 5; echo "status=$?")
if grep -q '::warning title=adbd is not steadily root (API 35)' <<<"$out" && grep -q 'last `id -u`: 2000' <<<"$out" &&
  grep -q 'status=0' <<<"$out"; then
  ok "an adbd that never answers as root is a warning that names the last answer"
else
  bad "an unrootable adbd was misreported" "$out"
fi

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
