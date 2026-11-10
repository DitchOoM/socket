#!/usr/bin/env bash
#
# Turns one run's job list and artifact list into the nightly deep-checks report.
#
#   deep-checks-report.sh <jobs.json> <artifacts.json> <report-job-name>
#
# <jobs.json> is the `.jobs` array of GET /repos/{repo}/actions/runs/{id}/jobs and <artifacts.json> the
# `.artifacts` array of GET /repos/{repo}/actions/runs/{id}/artifacts. <report-job-name> is the
# reporting job itself, left out of the table.
#
# Environment: GITHUB_REPOSITORY and GITHUB_RUN_ID (artifact links), GITHUB_OUTPUT (optional).
#
# Prints the markdown report on stdout, and sets `failed=<n>` in $GITHUB_OUTPUT. A check counts as
# failed unless it concluded `success`: a job that did not run is a night on which that check was not
# made, which is reported the same way as one that found a bug.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <jobs.json> <artifacts.json> <report-job-name>" >&2
  exit 2
fi
jobs_json="$1" artifacts_json="$2" self="$3"
base="https://github.com/${GITHUB_REPOSITORY:?}/actions/runs/${GITHUB_RUN_ID:?}"

rows=$(jq -r --arg self "$self" '
  [.[] | select(.name != $self)] | sort_by(.name)[] |
  (if .conclusion == "success" then "pass"
   elif .conclusion == "skipped" then "NOT RUN"
   else "FAILED (\(.conclusion // "unfinished"))" end) as $r |
  "| \(.name) | \($r) | [log](\(.html_url)) |"' "$jobs_json")

failed=$(jq --arg self "$self" '[.[] | select(.name != $self and .conclusion != "success")] | length' "$jobs_json")
total=$(jq --arg self "$self" '[.[] | select(.name != $self)] | length' "$jobs_json")

echo "| Check | Result | |"
echo "|---|---|---|"
echo "$rows"
echo
if [ "$failed" -eq 0 ]; then
  echo "All ${total} deep checks passed."
else
  echo "**${failed} of ${total} deep checks did not pass.**"
  echo
  evidence=$(jq -r --arg base "$base" '
    .[] | select(.name | startswith("deep-")) |
    "- [\(.name)](\($base)/artifacts/\(.id))"' "$artifacts_json")
  if [ -n "$evidence" ]; then
    echo "Evidence (crash inputs, ASAN reports, full soak output):"
    echo "$evidence"
  else
    echo "No evidence artifact was uploaded; read the logs linked above."
  fi
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "failed=${failed}" >> "$GITHUB_OUTPUT"
fi
