#!/usr/bin/env bash
#
# Tests for artifact-download-retry.sh, driven by a fake `gh` on PATH.
#
# The assumption under test is the one that cost run 34249480099 three attempts: a 403 is transient and
# must be retried, while a genuine error must fail on the FIRST attempt with its output intact.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../artifact-download-retry.sh"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> [<must-contain>]
  local name="$1" want="$2" got="$3" out="$4" needle="${5:-}"
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  if [ -n "$needle" ] && ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; echo "$out" | sed 's/^/    /'; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

run() { # <fake-gh-body> -> sets OUT/RC
  local body="$1"; shift
  local tmp; tmp="$(mktemp -d)"
  printf '#!/usr/bin/env bash\n%s\n' "$body" >"$tmp/gh"
  chmod +x "$tmp/gh"
  OUT="$(PATH="$tmp:$PATH" GITHUB_RUN_ID=1 GITHUB_REPOSITORY=o/r ARTIFACT_RETRY_DELAY=0 \
         ATTEMPT_FILE="$tmp/n" bash "$script" "$@" 2>&1)"
  RC=$?
  rm -rf "$tmp"
}

# A 403 that clears: must be retried and must succeed.
run 'n=$(cat "$ATTEMPT_FILE" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" >"$ATTEMPT_FILE"
     if [ "$1" = "run" ] && [ "$n" -lt 3 ]; then echo "Failed request: (403) Forbidden: Error from intermediary" >&2; exit 1; fi
     exit 0' art dest
check "a 403 that clears is retried to success" 0 "$RC" "$OUT" "retrying"

# A real error: must NOT be retried, and must say so.
run 'echo "artifact not found" >&2; exit 1' art dest
check "a genuine failure is not retried" 1 "$RC" "$OUT" "non-transient"

# A 403 that never clears: exhausts attempts, then captures which kind of 403 it was.
run 'if [ "$1" = "run" ]; then echo "(403) Forbidden" >&2; exit 1; fi
     if [ "$1" = "api" ]; then echo "quiche-linux-natives-full	expired=false	23528734b"; exit 0; fi
     exit 0' art dest
check "a persistent 403 is captured, not guessed at" 1 "$RC" "$OUT" "edge rejection, not permissions"

# A 403 whose listing ALSO fails is a different diagnosis and must say the other thing.
run 'echo "(403) Forbidden" >&2; exit 1' art dest
check "a token that cannot list is diagnosed as permissions" 1 "$RC" "$OUT" "not an edge rejection"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
