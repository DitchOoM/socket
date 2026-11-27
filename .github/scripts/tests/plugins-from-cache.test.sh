#!/usr/bin/env bash
#
# Tests for plugins-from-cache.sh, driven by a fake Gradle replaying real output and a fake Gradle home.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../plugins-from-cache.sh"
pass=0
fail=0

indent() { while IFS= read -r line; do printf '    %s\n' "$line"; done <<<"$1"; }

check() { # <name> <expected-exit> <actual-exit> <output> <must-contain>
  local name="$1" want="$2" got="$3" out="$4" needle="$5"
  if [ "$want" != "$got" ]; then
    echo "FAIL: $name — expected exit $want, got $got"; indent "$out"; fail=$((fail + 1)); return
  fi
  if ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL: $name — output missing '$needle'"; indent "$out"; fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

run() { # <gradle-output-file> <gradle-exit> <cached-coordinate or ""> -> OUT/RC
  local text="$1" code="$2" cached="$3" tmp group name version
  tmp="$(mktemp -d)"
  mkdir -p "$tmp/home"
  if [ -n "$cached" ]; then
    IFS=: read -r group name version <<<"$cached"
    mkdir -p "$tmp/home/caches/modules-2/metadata-2.107/descriptors/$group/$name/$version/2f8748e965ecb2135dad092e9ef808de"
    : >"$tmp/home/caches/modules-2/metadata-2.107/descriptors/$group/$name/$version/2f8748e965ecb2135dad092e9ef808de/descriptor.bin"
  fi
  cat >"$tmp/gradle" <<EOF
#!/usr/bin/env bash
echo "GRADLE-ARGS: \$*"
cat "$text"
exit $code
EOF
  chmod +x "$tmp/gradle"
  OUT="$(cd "$tmp" && RUNNER_TEMP="$tmp" GRADLE_USER_HOME="$tmp/home" GRADLE_RETRY_COMMAND="$tmp/gradle" \
    bash "$script" -PlinuxTargets=false 2>&1)"
  RC=$?
  rm -rf "$tmp"
}

# A build whose plugins all resolve from the cache, trimmed from a local --info run.
cat >"$fixtures/resolved" <<'EOF'
Resolved plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0', artifact: 'org.gradle.toolchains:foojay-resolver:1.0.0']
Resolved plugin [id: 'com.vanniktech.maven.publish', version: '0.37.0', artifact: 'com.vanniktech:gradle-maven-publish-plugin:0.37.0']
Resolved plugin [id: 'com.vanniktech.maven.publish', version: '0.37.0', artifact: 'com.vanniktech:gradle-maven-publish-plugin:0.37.0']
BUILD SUCCESSFUL in 16s
EOF
run "$fixtures/resolved" 0 ""
check "a build that resolves from the cache passes" 0 "$RC" "$OUT" "::notice::2 plugin requests resolved from the Gradle cache with the network closed"
check "it lists what resolved" 0 "$RC" "$OUT" "artifact: 'com.vanniktech:gradle-maven-publish-plugin:0.37.0'"
check "it closes the network and expires cached 'not found' answers" 0 "$RC" "$OUT" \
  "/gradle help --info --no-scan --no-daemon --no-configuration-cache -PlinuxTargets=false -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dorg.gradle.internal.test.clockoffset=90000000"

# Verbatim from job 139275213518 (run 45632903201): the marker's metadata is in the cache, yet the
# build needed the network for it.
cat >"$fixtures/marker-cached" <<'EOF'
* What went wrong:
Plugin [id: 'com.vanniktech.maven.publish', version: '0.37.0'] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0')
  Searched in the following repositories:
    MavenLocal(file:/home/runner/.m2/repository)
    Google
    Gradle Central Plugin Repository
    MavenRepo

BUILD FAILED in 19s
EOF
run "$fixtures/marker-cached" 1 "com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0"
check "a cached plugin that still needs the network fails" 1 "$RC" "$OUT" \
  "::error::com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0 is in the Gradle cache"

# From a local run with the network closed: a plugin version the cache has never seen.
cat >"$fixtures/absent" <<'EOF'
* What went wrong:
Plugin [id: 'com.vanniktech.maven.publish', version: '0.37.99', artifact: 'com.vanniktech:gradle-maven-publish-plugin:0.37.99', apply: false] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'com.vanniktech:gradle-maven-publish-plugin:0.37.99')
  Searched in the following repositories:
    Google
    Gradle Central Plugin Repository
    MavenRepo

BUILD FAILED in 1s
EOF
run "$fixtures/absent" 1 ""
check "a plugin version the cache does not hold is a warning" 0 "$RC" "$OUT" \
  "::warning::com.vanniktech:gradle-maven-publish-plugin:0.37.99 is not in the restored Gradle cache"

# A configuration-time dependency the cache holds but Gradle still fetched.
cat >"$fixtures/dependency-cached" <<'EOF'
* What went wrong:
Could not resolve all files for configuration ':detachedConfiguration1'.
> Could not resolve org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.21.
EOF
run "$fixtures/dependency-cached" 1 "org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.21"
check "a cached dependency that still needs the network fails" 1 "$RC" "$OUT" \
  "::error::org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.21 is in the Gradle cache"

cat >"$fixtures/no-module" <<'EOF'
* What went wrong:
A problem occurred evaluating root project 'socket'.
> No version supplied to a CI build.
EOF
run "$fixtures/no-module" 1 ""
check "a failure that names no module fails" 1 "$RC" "$OUT" "named no module"

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
