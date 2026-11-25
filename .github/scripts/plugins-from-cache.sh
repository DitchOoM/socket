#!/usr/bin/env bash
#
# Configures the build with the network closed and Gradle's clock a day ahead, so it passes only when
# the Gradle cache answers the build's plugin and configuration-time requests without a repository.
#
# Gradle keeps a module it found for good and a "not found" answer for 24 hours. The day-ahead clock
# (org.gradle.internal.test.clockoffset) makes every "not found" answer count as expired, as it is in
# any cache older than a day, so a request that leans on one shows up here and not only on old caches.
# The network is closed by pointing the JVM's HTTP(S) proxy at a port nothing listens on, in a
# single-use daemon so no later build inherits it.
#
# Each module Gradle could not resolve is judged against the cache:
#   - the cache holds it (metadata or files): Gradle asked the network for something the cache has, so
#     the cache cannot serve this build on its own. Fails.
#   - the cache does not hold it (a version this branch changed since the cache was written): a
#     warning; the build step fetches it.
# A failure that names no module fails with Gradle's output. Gradle stops at the first plugin it cannot
# resolve, so a warning leaves the plugins after it unchecked on that run.
#
# Arguments go to Gradle after `help`. GRADLE_RETRY_COMMAND names the Gradle to run (default ./gradlew).
set -uo pipefail

gradle_command="${GRADLE_RETRY_COMMAND:-./gradlew}"
gradle_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
day_ahead_ms=$((25 * 3600 * 1000))
closed_port=9
log="$(mktemp "${RUNNER_TEMP:-/tmp}/plugins-from-cache.log.XXXXXX")"
trap 'rm -f "$log"' EXIT

args=(help --info --no-scan --no-daemon --no-configuration-cache "$@"
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort="$closed_port"
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort="$closed_port"
  -Dorg.gradle.internal.test.clockoffset="$day_ahead_ms")
echo "running: $gradle_command ${args[*]}"
"$gradle_command" "${args[@]}" >"$log" 2>&1
status=$?

if [ "$status" -eq 0 ]; then
  resolved="$(grep -E '^Resolved plugin \[' "$log" | sort -u)"
  printf '%s\n' "$resolved"
  echo "::notice::$(grep -c . <<<"$resolved") plugin requests resolved from the Gradle cache with the network closed and every cached 'not found' answer expired."
  exit 0
fi

cat "$log"
modules="$({
  sed -n "s/.*could not resolve plugin artifact '\([^']*\)'.*/\1/p" "$log"
  sed -nE 's/.*Could not resolve ([A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[A-Za-z0-9._+-]+)\.$/\1/p' "$log"
} | sort -u)"
if [ -z "$modules" ]; then
  echo "::error::gradle failed with the network closed and named no module; see the output above."
  exit 1
fi

asked_for_cached=0
while IFS=: read -r group name version; do
  cached="$(find "$gradle_home"/caches/modules-2/metadata-*/descriptors/"$group"/"$name"/"$version" \
    "$gradle_home"/caches/modules-2/files-2.1/"$group"/"$name"/"$version" -type f 2>/dev/null | head -n 1)"
  if [ -n "$cached" ]; then
    echo "::error::${group}:${name}:${version} is in the Gradle cache ($cached) yet resolving it needed the network."
    asked_for_cached=1
  else
    echo "::warning::${group}:${name}:${version} is not in the restored Gradle cache; the build step fetches it."
  fi
done <<<"$modules"
exit "$asked_for_cached"
