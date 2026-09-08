#!/usr/bin/env bash
#
# Download one artifact from this run, retrying only a transient failure.
#
# actions/download-artifact classifies HTTP 403 as NON-retryable and aborts in ~400ms. The 403 seen in
# run 34249480099 came from an edge intermediary, not the artifact service: the artifact existed, was
# unexpired, and its producer job was green. One such answer deletes a 20-minute job, and unlike every
# other transient fetch here (gradle-retry.sh, apt-install-cached, the sdkmanager retry) nothing
# retried it.
#
# On persistent failure this prints the artifact listing as this job's own token sees it, which
# separates the two causes a 403 can have: a listing that succeeds and names the artifact means an
# edge/transient rejection; a listing that itself fails means the token cannot read artifacts at all.
#
# Usage: artifact-download-retry.sh <artifact-name> <destination-dir>
#
# Deliberately not `set -e`: every failure below is classified explicitly.
set -uo pipefail

name="${1:?artifact name required}"
dest="${2:?destination dir required}"
attempts="${ARTIFACT_RETRY_ATTEMPTS:-4}"
run_id="${ARTIFACT_RUN_ID:-${GITHUB_RUN_ID}}"
delay="${ARTIFACT_RETRY_DELAY:-10}"

# Answers about the SERVER's availability, never about this commit.
transient='403|429|500|502|503|504|Forbidden|Too Many Requests|timed out|timeout|connection reset|unexpected EOF'

log="$(mktemp "${RUNNER_TEMP:-/tmp}/artifact-download.XXXXXX.log")"
trap 'rm -f "$log"' EXIT

for i in $(seq 1 "$attempts"); do
  if gh run download "$run_id" --name "$name" --dir "$dest" >"$log" 2>&1; then
    cat "$log"
    exit 0
  fi
  cat "$log"
  if ! grep -qiE "$transient" "$log"; then
    echo "::error::Downloading '$name' failed for a non-transient reason — not retrying."
    exit 1
  fi
  if [ "$i" -lt "$attempts" ]; then
    echo "::warning::Transient failure downloading '$name' (attempt ${i}/${attempts}); retrying in ${delay}s."
    sleep "$delay"
  fi
done

echo "::error::'$name' was still undownloadable after ${attempts} attempts."
echo "--- artifacts in this run, as this job's token sees them ---"
if gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/artifacts" \
     --jq '.artifacts[] | "\(.name)\texpired=\(.expired)\t\(.size_in_bytes)b"'; then
  echo "::error::The listing above succeeded, so this token CAN read artifacts: the 403 was an edge rejection, not permissions."
else
  echo "::error::The listing itself failed, so this token cannot read artifacts at all: a permissions problem, not an edge rejection."
fi
exit 1
