#!/usr/bin/env bash
# Puts the yarn the Kotlin/JS + Wasm builds need on this machine WITHOUT GitHub, and exports its path
# as KOTLIN_JS_YARN, which makes build.gradle.kts turn the Kotlin plugin's yarn download off.
#
# Why: the plugin otherwise resolves
#   https://github.com/yarnpkg/yarn/releases/download/v<ver>/yarn-v<ver>.tar.gz
# on every run that reaches :kotlinYarnSetup / :kotlinWasmYarnSetup. On 2026-11-08 that URL answered
# 504 Gateway Time-out for long enough that gradle-retry.sh's three attempts all failed, and two
# merged.yaml runs (42463746806, 42465877153) went red with no code at fault.
#
# Where the yarn comes from, in order:
#   1. The runner image's own yarn, when it is exactly the pinned version. No network at all.
#   2. `npm install yarn@<ver>` from the npm registry, retried. That registry is the host every
#      `yarn install` in this build already fetches its packages from, so this adds no new host the
#      run depends on; it only moves yarn itself off GitHub.
#
# The version is `kotlinJsYarnVersion` in gradle.properties, the same value the build pins, so CI and
# local builds cannot drift apart. Anything other than that exact version fails this step loudly
# rather than handing the build a yarn it did not ask for.
set -euo pipefail

# Seconds per retry step (attempt N waits N times this). Overridable so the tests need not sleep.
retry_delay="${KOTLIN_JS_YARN_RETRY_DELAY:-15}"

root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
want=$(sed -nE 's/^kotlinJsYarnVersion[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\1/p' "$root/gradle.properties")
if [ -z "$want" ]; then
  echo "::error::kotlinJsYarnVersion is not set in gradle.properties; build.gradle.kts and this script both read it."
  exit 1
fi

# Bounded: a corepack-managed `yarn` shim can try to fetch on first use; never let a probe hang.
# (`timeout` is coreutils, so absent on a stock macOS; the probe then just runs unbounded.)
version_of() {
  if command -v timeout >/dev/null 2>&1; then timeout 60 "$1" --version; else "$1" --version; fi
}

have=""
if runner_yarn=$(command -v yarn 2>/dev/null); then
  have=$(version_of "$runner_yarn" 2>/dev/null || true)
fi

if [ "$have" = "$want" ]; then
  yarn_path="$runner_yarn"
  echo "Using the runner's yarn $have at $yarn_path (no download)."
else
  echo "Runner yarn is '${have:-absent}', want $want: installing yarn@$want from the npm registry."
  prefix="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kotlin-js-yarn-$want"
  installed=false
  for attempt in 1 2 3 4 5; do
    if npm install --global --no-audit --no-fund --prefix "$prefix" "yarn@$want"; then
      installed=true
      break
    fi
    [ "$attempt" -lt 5 ] || break
    echo "npm install yarn@$want failed (attempt $attempt of 5); retrying in $((attempt * retry_delay))s" >&2
    sleep $((attempt * retry_delay))
  done
  if [ "$installed" != true ]; then
    echo "::error::Could not install yarn@$want from the npm registry after 5 attempts. The JS npm install would need the same registry, so this run cannot build JS either way."
    exit 1
  fi
  yarn_path="$prefix/bin/yarn"
  have=$(version_of "$yarn_path" 2>/dev/null || true)
  if [ "$have" != "$want" ]; then
    echo "::error::$yarn_path reports $have, want $want."
    exit 1
  fi
  echo "Installed yarn $have at $yarn_path."
fi

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "KOTLIN_JS_YARN=$yarn_path" >> "$GITHUB_ENV"
else
  echo "KOTLIN_JS_YARN=$yarn_path"
fi
