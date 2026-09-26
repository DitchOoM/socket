#!/usr/bin/env bash
#
# Tests for gradle-retry.sh, driven by a fake ./gradlew replaying real failure text.
#
# A Central 403 or 429 is retried; a 403 from any other host and a genuine build failure fail on the
# FIRST attempt with their output intact.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../gradle-retry.sh"
pass=0
fail=0

indent() { while IFS= read -r line; do printf '    %s\n' "$line"; done <<<"$1"; }

check() { # <name> <expected-exit> <expected-attempts> <actual-exit> <actual-attempts> <output> <must-contain>
  local name="$1" want="$2" want_n="$3" got="$4" got_n="$5" out="$6" needle="$7"
  if [ "$want" != "$got" ] || [ "$want_n" != "$got_n" ]; then
    echo "FAIL: $name — expected exit $want after $want_n attempt(s), got exit $got after $got_n"
    indent "$out"; fail=$((fail + 1)); return
  fi
  if ! grep -qF "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; indent "$out"; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

run() { # <failure-text-file> <attempts-that-fail> -> sets OUT/RC/N
  local text="$1" failing="$2"
  local tmp; tmp="$(mktemp -d)"
  cat >"$tmp/gradlew" <<EOF
#!/usr/bin/env bash
n=\$(cat "$tmp/n" 2>/dev/null || echo 0); n=\$((n + 1)); echo "\$n" >"$tmp/n"
if [ "\$n" -le $failing ]; then cat "$text"; exit 1; fi
echo "BUILD SUCCESSFUL"
EOF
  chmod +x "$tmp/gradlew"
  OUT="$(cd "$tmp" && RUNNER_TEMP="$tmp" GRADLE_RETRY_BACKOFF_SECONDS=0 bash "$script" help 2>&1)"
  RC=$?
  N="$(cat "$tmp/n")"
  rm -rf "$tmp"
}

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

# Verbatim from run 36266351401 (job 108472720292), timestamps stripped.
cat >"$fixtures/central-403" <<'EOF'
* What went wrong:
Execution failed for task ':socket-http3:kspCommonMainKotlinMetadata' (registered by plugin 'org.jetbrains.kotlin.multiplatform').
> Could not resolve all files for configuration ':socket-http3:detachedConfiguration23'.
   > Could not resolve com.google.devtools.ksp:symbol-processing-common-deps:2.3.9.
     Required by:
         project ':socket-http3'
      > Could not resolve com.google.devtools.ksp:symbol-processing-common-deps:2.3.9.
         > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-common-deps/2.3.9/symbol-processing-common-deps-2.3.9.pom'.
            > Could not HEAD 'https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-common-deps/2.3.9/symbol-processing-common-deps-2.3.9.pom'. Received status code 403 from server: Forbidden
   > Could not resolve org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2.
     Required by:
         project ':socket-http3'
      > Could not resolve org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2.
         > Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.pom'.
            > Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.pom'. Received status code 403 from server: Forbidden
BUILD FAILED in 4m 6s
EOF

# The same failure from a repository that is not Central: a real answer.
sed 's#https://repo\.maven\.apache\.org/maven2#https://maven.pkg.github.com/o/r#g' \
  "$fixtures/central-403" >"$fixtures/other-403"

cat >"$fixtures/central-429" <<'EOF'
            > Could not HEAD 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.pom'. Received status code 429 from server: Too Many Requests
EOF

# Verbatim from run 36266351401 (job 108472720254): a genuine test failure.
cat >"$fixtures/test-failure" <<'EOF'
14 tests completed, 1 failed
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':socket-webtransport:wasmJsBrowserTest'.
> There were failing tests. See the report at: file:///home/runner/work/socket/socket/socket-webtransport/build/reports/tests/wasmJsBrowserTest/index.html
BUILD FAILED in 40s
EOF

run "$fixtures/central-403" 1
check "a Central 403 that clears is retried to success" 0 2 "$RC" "$N" "$OUT" "transient repository failure"

run "$fixtures/central-403" 99
check "a Central 403 that never clears exhausts the attempts" 1 3 "$RC" "$N" "$OUT" "after 3 attempts"

run "$fixtures/central-429" 1
check "a Central 429 on a HEAD is retried" 0 2 "$RC" "$N" "$OUT" "transient repository failure"

run "$fixtures/other-403" 1
check "a 403 from another repository is not retried" 1 1 "$RC" "$N" "$OUT" "non-transient"

run "$fixtures/test-failure" 1
check "a failing test is not retried and keeps its output" 1 1 "$RC" "$N" "$OUT" "There were failing tests"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
