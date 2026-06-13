#!/usr/bin/env bash
# Bring up the named docker-compose harness services, retrying transient
# failures. `docker compose up --wait <svc>` builds/pulls images on the way up,
# and the base-image pulls (e.g. eclipse-temurin:21-jre-noble for quic-echo) hit
# Docker Hub — which intermittently times out on CI runners
# ("dial tcp ...:443: i/o timeout" / "DeadlineExceeded"), reddening the
# build-linux and android-emulator lanes for no real reason.
#
# Re-running `up` is idempotent: already-running services are reconciled and
# only the failed build/pull is re-attempted, so a retry recovers cleanly.
#
# Usage: test-harness/compose-up-retry.sh <service> [<service> ...]
set -uo pipefail

cd "$(dirname "$0")"

attempts=3
for i in $(seq 1 "$attempts"); do
    if docker compose up -d --wait "$@"; then
        exit 0
    fi
    if [ "$i" -lt "$attempts" ]; then
        backoff=$((i * 15))
        echo "::warning::'docker compose up --wait $*' failed (attempt $i/$attempts) — likely a transient Docker Hub pull; retrying in ${backoff}s"
        sleep "$backoff"
    fi
done

echo "::error::docker harness failed to come up after $attempts attempts ($*)"
exit 1
