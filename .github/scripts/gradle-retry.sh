#!/usr/bin/env bash
#
# Run ./gradlew, retrying ONLY a transient repository failure.
#
# WHY this exists: several build-linux jobs re-resolve their half of the dependency graph from Maven
# Central COLD on every run, because the `gradle-linux-` cache has one writer (`natives`) and that job
# runs only cargo tasks, so the entry it saves cannot serve them. The key is a hash of the build
# scripts, not of the contents, so those jobs get an exact key hit, log "Cache restored successfully",
# and fetch from Central anyway. That standing burst is what draws rate limiting — issue #427 tracks
# removing it. On 2026-08-20 it killed two different jobs: "Publish Linux targets to Maven Local"
# (run 32406698331, on :jsNpmAggregated) and "QUIC quiche JVM (JNI)" (run 32411168329, on
# :socket-quic-quiche's test classpath), each with every module answering 429 at once.
#
# Gradle does not retry a 429, so one rate-limit answer deletes a 40-minute job. This makes that
# answer survivable, the same way this lane already treats its other transient fetches (the
# sdkmanager license retry in build-linux.yaml, apt-install-cached, compose-up-retry.sh).
#
# It must never mask a real failure. The grep is anchored on Gradle's OWN "Could not GET" /
# "Could not get resource" wording plus a rate-limit / unavailability / timeout code, so a compile
# error, a failing test or a publication problem matches nothing here and fails on the FIRST attempt
# with its output intact. Verified both ways against real logs: 7 matches in the 429 log that
# motivated this, 0 in a log whose failure is a genuine test assertion.
#
# Deliberately NOT `set -e`: every failure below is handled explicitly, and errexit would abort at the
# first failing gradle run before the transient check could classify it. pipefail IS required — without
# it `gradlew | tee` reports tee's status and a real failure would read as success.
set -uo pipefail

attempts="${GRADLE_RETRY_ATTEMPTS:-3}"
log="$(mktemp "${RUNNER_TEMP:-/tmp}/gradle-retry.XXXXXX.log")"
trap 'rm -f "$log"' EXIT

# Rate limited (429), unavailable (502/503/504), or the connection died mid-fetch. All are answers
# about the SERVER's availability, none is an answer about this commit.
transient='Could not (GET|get resource).*(429|502|503|504|Too Many Requests|Read timed out|Connection reset|Connection timed out)'

for i in $(seq 1 "$attempts"); do
  if ./gradlew "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  if ! grep -qE "$transient" "$log"; then
    echo "::error::gradle failed for a non-transient reason — not retrying. See the output above."
    exit 1
  fi
  if [ "$i" -lt "$attempts" ]; then
    backoff=$((i * 30))
    echo "::warning::gradle hit a transient repository failure (attempt $i/$attempts); retrying in ${backoff}s. See issue #427 for why this job talks to Maven Central at all."
    sleep "$backoff"
  fi
done

echo "::error::gradle still failing on a transient repository error after $attempts attempts"
exit 1
