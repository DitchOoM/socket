#!/usr/bin/env bash
#
# Tests for provision-kotlin-js-yarn.sh, driven by a fake `yarn` and `npm` on PATH.
#
# What it must guarantee: the yarn it hands the build is exactly the version gradle.properties pins,
# taken from the runner when the runner has that version (no network), otherwise installed from the
# npm registry with retries, and anything else fails the step instead of reaching Gradle.

# The fake npm bodies below are single-quoted on purpose: they expand inside the fake, not here.
# shellcheck disable=SC2016
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../provision-kotlin-js-yarn.sh"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> [<must-contain>]
  local name="$1" want="$2" got="$3" out="$4" needle="${5:-}"
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; printf '%s\n' "$out" | while IFS= read -r l; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); return
  fi
  if [ -n "$needle" ] && ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; printf '%s\n' "$out" | while IFS= read -r l; do printf '    %s\n' "$l"; done; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

# run <runner-yarn-version or ""> <fake-npm-body> [<gradle.properties line>] -> sets OUT/RC/ENVFILE/T
run() {
  local runner_version="$1" npm_body="$2" prop="${3-kotlinJsYarnVersion=1.22.22}"
  T="$(mktemp -d)"
  mkdir -p "$T/bin" "$T/sys" "$T/ws" "$T/tmp"
  # PATH is only these two directories, so a yarn installed on the machine running the tests (the
  # ubuntu runner has one) cannot stand in for the fake, or for its absence.
  local tool
  for tool in bash cat chmod mkdir sed sleep timeout; do
    if command -v "$tool" >/dev/null 2>&1; then ln -s "$(command -v "$tool")" "$T/sys/$tool"; fi
  done
  printf '%s\n' "$prop" >"$T/ws/gradle.properties"
  if [ -n "$runner_version" ]; then
    printf '#!/usr/bin/env bash\necho %s\n' "$runner_version" >"$T/bin/yarn"
    chmod +x "$T/bin/yarn"
  fi
  printf '#!/usr/bin/env bash\necho "npm $*" >>"%s/npm-calls"\n%s\n' "$T" "$npm_body" >"$T/bin/npm"
  chmod +x "$T/bin/npm"
  ENVFILE="$T/github-env"
  OUT="$(PATH="$T/bin:$T/sys" GITHUB_WORKSPACE="$T/ws" RUNNER_TEMP="$T/tmp" GITHUB_ENV="$ENVFILE" \
         KOTLIN_JS_YARN_RETRY_DELAY=0 FAKE_T="$T" bash "$script" 2>&1)"
  RC=$?
}
done_with() { rm -rf "$T"; }
holds() { if "$@"; then echo 0; else echo 1; fi; } # a condition, as an exit status `check` can compare

# Installs a fake yarn reporting $FAKE_VERSION (default 1.22.22) under the --prefix npm was given.
npm_installs='prefix=""; while [ $# -gt 0 ]; do [ "$1" = "--prefix" ] && prefix="$2"; shift; done
  mkdir -p "$prefix/bin"; printf "#!/usr/bin/env bash\necho %s\n" "${FAKE_VERSION:-1.22.22}" >"$prefix/bin/yarn"
  chmod +x "$prefix/bin/yarn"'

# The runner already has the pinned version: used as-is, npm never runs.
run 1.22.22 'exit 1'
check "the runner's yarn at the pinned version is used" 0 "$RC" "$OUT" "no download"
check "  ...and exported for Gradle" 0 "$(holds grep -qxF "KOTLIN_JS_YARN=$T/bin/yarn" "$ENVFILE")" ""
check "  ...without touching the registry" 0 "$(holds [ ! -e "$T/npm-calls" ])" ""
done_with

# The runner has a different yarn: the pinned one comes from the npm registry instead.
run 1.22.19 "$npm_installs"
check "a different runner yarn is not used" 0 "$RC" "$OUT" "Runner yarn is '1.22.19'"
check "  ...the pinned version is installed from npm" 0 "$(holds grep -qF "yarn@1.22.22" "$T/npm-calls")" ""
check "  ...and that install is what Gradle gets" 0 "$(holds grep -qxF "KOTLIN_JS_YARN=$T/tmp/kotlin-js-yarn-1.22.22/bin/yarn" "$ENVFILE")" ""
done_with

# No yarn at all, and the registry fails twice before answering: retried, not failed.
run "" 'n=$(cat "$FAKE_T/n" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" >"$FAKE_T/n"
  [ "$n" -ge 3 ] || exit 1
  '"$npm_installs"
check "a registry failure that clears is retried" 0 "$RC" "$OUT" "attempt 2 of 5"
done_with

# The registry never answers: the step fails, loudly, after five attempts.
run "" 'exit 1'
check "a registry that never answers fails the step" 1 "$RC" "$OUT" "after 5 attempts"
check "  ...after exactly five attempts" 0 "$(holds [ "$(wc -l <"$T/npm-calls")" -eq 5 ])" ""
check "  ...and exports nothing" 0 "$(holds [ ! -s "$ENVFILE" ])" ""
done_with

# npm "succeeds" but leaves a yarn of another version: refused rather than handed to Gradle.
FAKE_VERSION=1.0.0 run "" "$npm_installs"
check "an installed yarn of the wrong version is refused" 1 "$RC" "$OUT" "reports 1.0.0, want 1.22.22"
done_with

# No pin in gradle.properties: nothing to provision against.
run 1.22.22 'exit 1' "kotlin.code.style=official"
check "a missing kotlinJsYarnVersion fails the step" 1 "$RC" "$OUT" "kotlinJsYarnVersion is not set"
done_with

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
