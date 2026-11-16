#!/usr/bin/env bash
#
# Run ./gradlew, retrying ONLY a transient repository failure.
#
# WHY this exists: build-linux jobs fetch from Maven Central whatever the restored `gradle-linux-`
# cache does not hold (native-deps-freshness.yaml's cache-warm job writes it with
# `resolveAllDependencies`; issue #427). Central answers such fetches with rate limiting or edge
# refusals: on 2026-08-20 every module answered 429 at once in "Publish Linux targets to Maven Local"
# (run 32406698331) and "QUIC quiche JVM (JNI)" (run 32411168329).
#
# Gradle does not retry a 429, so one rate-limit answer deletes a 40-minute job. This makes that
# answer survivable, the same way this lane already treats its other transient fetches (the
# sdkmanager license retry in build-linux.yaml, apt-install-cached, compose-up-retry.sh).
#
# It must never mask a real failure. The grep is anchored on Gradle's OWN "Could not GET/HEAD" /
# "Could not get resource" wording plus a rate-limit / unavailability / timeout code, or on a
# repository host plus a 403 or a name-resolution failure, so a compile error, a failing test or a
# publication problem matches nothing here and fails on the FIRST attempt with its output intact.
# tests/gradle-retry.test.sh replays real logs both ways.
#
# Deliberately NOT `set -e`: every failure below is handled explicitly, and errexit would abort at the
# first failing gradle run before the transient check could classify it. pipefail IS required — without
# it `gradlew | tee` reports tee's status and a real failure would read as success.
set -uo pipefail

attempts="${GRADLE_RETRY_ATTEMPTS:-3}"
# X's last: BSD mktemp (macOS) only replaces trailing X's.
log="$(mktemp "${RUNNER_TEMP:-/tmp}/gradle-retry.log.XXXXXX")"
trap 'rm -f "$log"' EXIT

# Rate limited (429), unavailable (502/503/504), or the connection died mid-fetch. All are answers
# about the SERVER's availability, none is an answer about this commit.
transient='Could not (GET|HEAD|get resource).*(429|502|503|504|Too Many Requests|Read timed out|Connection reset|Connection timed out)'
# Maven Central answering 403 for artifacts it serves publicly: its edge refusing this runner, not a
# missing or private artifact (run 36266351401: three KSP artifacts, 403 on every request at once).
# Central's hosts only — a 403 from any other repository is a real answer and fails first time.
# compute-version.yaml treats Central's 403 the same way.
transient+="|Could not (GET|HEAD) 'https://(repo\.maven\.apache\.org|repo1\.maven\.org)/[^']*'\. Received status code 403 from server"
# The runner could not resolve a repository host's name (run 36274263792: "repo.maven.apache.org:
# nodename nor servname provided, or not known" on macOS). Anchored on the hosts settings.gradle.kts
# resolves from, so a test asserting on a DNS failure of its own host never matches.
repo_hosts='(repo\.maven\.apache\.org|repo1\.maven\.org|plugins\.gradle\.org|plugins-artifacts\.gradle\.org|dl\.google\.com|maven\.google\.com)'
transient+="|> ${repo_hosts}: (nodename nor servname provided, or not known|Temporary failure in name resolution|Name or service not known)"
transient+="|UnknownHostException: ${repo_hosts}"
# A plugin the settings/build script asks for that no plugin repository could serve. Gradle words it
# on two lines and never says why ("Plugin [id: 'com.gradle.develocity', version: '4.3'] was not
# found ..." then "- Plugin Repositories (could not resolve plugin artifact '<group>:<name>:<version>')"),
# with no host or HTTP status unless run with --info. A job whose Gradle cache holds no plugin jars
# fetches them from the portal on every run, so a portal timeout lands here. Retried, and the portal
# is probed so the failure names its status.
plugin_failure="Plugin Repositories \(could not resolve plugin artifact '"
transient+="|${plugin_failure}"
# Prints what the plugin portal answers for each plugin marker the failed build could not resolve:
# status, remote address and timings, or curl's own error (timeout, DNS, TLS). Failure path only.
# The portal answers 303 and sends Gradle on to Maven Central, which is where a refusal (403, 429) happens, and
# Gradle's own message names no status; so the probe follows the redirect and reports the final host's answer.
# GRADLE_RETRY_PROBE swaps curl out for tests/gradle-retry.test.sh.
probe_plugin_portal() {
  local group name version url
  sed -n "s/.*could not resolve plugin artifact '\([^']*\)'.*/\1/p" "$log" | sort -u |
    while IFS=: read -r group name version; do
      url="https://plugins.gradle.org/m2/${group//.//}/${name}/${version}/${name}-${version}.pom"
      echo "::warning::plugin portal probe for ${group}:${name}:${version}: GET ${url}"
      ${GRADLE_RETRY_PROBE:-curl} -sSL -o /dev/null --max-time 20 \
        -w 'status=%{http_code} final=%{url_effective} redirects=%{num_redirects} remote_ip=%{remote_ip} connect=%{time_connect}s total=%{time_total}s\n' "$url" 2>&1 ||
        echo "::warning::plugin portal probe failed (exit $?)"
    done
}
backoff_unit="${GRADLE_RETRY_BACKOFF_SECONDS:-30}"
# Gradle words the server's answer either on the request's own line ("Could not HEAD '…'. Received
# status code 403 …") or, for an artifact download, on the line beneath it ("> Received status code 403
# from server: Forbidden", job 110651316487). Fold the second form onto its request line so one
# line-anchored pattern judges both.
fold_status_lines() {
  awk '/^[[:space:]]*> Received status code / { sub(/^[[:space:]]*> /, ""); line = line " " $0; next }
       { if (NR > 1) print line; line = $0 }
       END { if (NR > 0) print line }' "$1"
}

# Gradle caches a repository's "not found" answer for 24 hours, and a plugin that no repository could
# serve is such an answer. A retry that reuses the cache re-reads the first attempt's failure instead
# of asking the repositories again, and fails in seconds however healthy they are (job 133587218454:
# the portal probe answered 200 while three attempts failed identically in 13 s, 4 s and 4 s). The
# retry after a plugin failure therefore re-asks every repository.
# `${extra[@]+...}` keeps bash 3.2 (macOS) happy under `set -u` while the array is empty. The retry also
# runs with --stacktrace: Gradle puts the cause (the HTTP status, the timeout) only in the stack trace.
extra=()
# Lists what the Gradle cache holds for each plugin the failed build could not resolve, so a cached
# answer is visible in the log instead of inferred. Failure path only.
show_cached_plugin_entries() {
  local group name version dir
  sed -n "s/.*could not resolve plugin artifact '\([^']*\)'.*/\1/p" "$log" | sort -u |
    while IFS=: read -r group name version; do
      for dir in "${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/metadata-*/descriptors/"$group"/"$name"/"$version"; do
        [ -d "$dir" ] || continue
        echo "::warning::cached resolution of ${group}:${name}:${version}: $(find "$dir" -type f -exec ls -l {} + 2>&1 | tr '\n' ';')"
      done
    done
}

for i in $(seq 1 "$attempts"); do
  if ./gradlew "$@" ${extra[@]+"${extra[@]}"} 2>&1 | tee "$log"; then
    exit 0
  fi
  # Folded once into a variable. Piping fold_status_lines into `grep -q` under pipefail let awk's
  # write to the closed pipe (grep exits at its first match) fail the pipeline, so a transient failure
  # read as non-transient whenever awk lost that race (job 133587218454: "awk: i/o error occurred
  # while closing /dev/stdout", then "not retrying").
  folded="$(fold_status_lines "$log")"
  if ! grep -qE "$transient" <<<"$folded"; then
    echo "::error::gradle failed for a non-transient reason — not retrying. See the output above."
    exit 1
  fi
  if grep -qE "$plugin_failure" <<<"$folded"; then
    probe_plugin_portal
    show_cached_plugin_entries
    extra=(--refresh-dependencies --stacktrace)
  fi
  if [ "$i" -lt "$attempts" ]; then
    backoff=$((i * backoff_unit))
    echo "::warning::gradle hit a transient repository failure (attempt $i/$attempts); retrying in ${backoff}s. See issue #427 for why this job talks to Maven Central at all."
    sleep "$backoff"
  fi
done

echo "::error::gradle still failing on a transient repository error after $attempts attempts"
exit 1
