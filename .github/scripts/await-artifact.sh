#!/usr/bin/env bash
#
# Waits until this workflow run holds the named artifact. Used by .github/actions/await-artifact and,
# from inside an emulator step, by scripts/android-lane-inputs.sh (the emulator boots while it waits).
#
# Usage: await-artifact.sh <artifact name> <producing job name, or a unique part of it> <timeout minutes>
# Needs GH_TOKEN (actions: read) and the Actions environment (GITHUB_REPOSITORY, GITHUB_RUN_ID).
#
# Fails at once when the producing job ended without the artifact, and after the timeout.
set -euo pipefail

NAME="${1:?usage: await-artifact.sh <name> <producer> <timeout-minutes>}"
PRODUCER="${2:?usage: await-artifact.sh <name> <producer> <timeout-minutes>}"
TIMEOUT_MIN="${3:?usage: await-artifact.sh <name> <producer> <timeout-minutes>}"

run="repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
deadline=$((SECONDS + TIMEOUT_MIN * 60))
polls=0
while :; do
  count=$(gh api "$run/artifacts?name=$NAME" --jq '.total_count' 2>/dev/null || echo 0)
  if [ "$count" -gt 0 ]; then
    echo "$NAME is in this run after ${SECONDS}s"
    exit 0
  fi
  # Every fourth poll: has the producer finished without it?
  if [ $((polls % 4)) -eq 0 ]; then
    ended=$(gh api --paginate "$run/jobs?per_page=100" \
      --jq ".jobs[] | select(.name | contains(\"$PRODUCER\")) | select(.status == \"completed\") | .conclusion" 2>/dev/null || true)
    if [ -n "$ended" ]; then
      count=$(gh api "$run/artifacts?name=$NAME" --jq '.total_count' 2>/dev/null || echo 0)
      [ "$count" -gt 0 ] && { echo "$NAME is in this run after ${SECONDS}s"; exit 0; }
      echo "::error::$PRODUCER ended ($ended) without uploading $NAME"
      exit 1
    fi
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::$NAME did not appear in this run within $TIMEOUT_MIN minutes"
    exit 1
  fi
  polls=$((polls + 1))
  sleep 30
done
