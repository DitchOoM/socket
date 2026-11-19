#!/usr/bin/env bash
#
# Tests for runner-health.sh: the hang watchdog's wait fits inside the job's budget, and the report warns on a
# starved disk and stays quiet otherwise.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../runner-health.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0
fail=0
expect() { # <name> <got> <want>
  if [ "$2" = "$3" ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1: got '$2', want '$3'"; fail=$((fail + 1)); fi
}
contains() { # <name> <text> <needle>
  if grep -qF -- "$3" <<<"$2"; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1: '$3' not in: $2"; fail=$((fail + 1)); fi
}

now="$(date +%s)"
# A job that has used 5 of its 40 minutes leaves the whole 20-minute wait.
expect "an early test step waits the full cap" "$(JOB_STARTED_AT=$((now - 300)) bash "$script" hang-wait 1200 40)" 1200
# Run 43918562845: the test step began 21 minutes in, so the stacks must come before minute 37, not at minute 41.
w="$(JOB_STARTED_AT=$((now - 1260)) bash "$script" hang-wait 1200 40)"
expect "a late test step dumps before the job timeout" "$([ "$w" -le 960 ] && [ "$w" -ge 900 ] && echo fits)" fits
expect "no budget left still dumps at once" "$(JOB_STARTED_AT=$((now - 2400)) bash "$script" hang-wait 1200 40)" 1
expect "an unknown start waits the cap" "$(env -u JOB_STARTED_AT bash "$script" hang-wait 600 40)" 600

# Samples: a starved disk warns, a quiet one does not.
printf '10:00:00 io=2.10 cpu=0.50 mem=0.00 load=0.4 disk=40%%\n' >"$tmp/runner-health.log"
out="$(RUNNER_TEMP="$tmp" bash "$script" report)"
contains "a quiet runner prints its samples" "$out" "io=2.10"
expect "a quiet runner raises no warning" "$(grep -c '::warning' <<<"$out")" 0
printf '10:00:20 io=63.40 cpu=1.00 mem=0.00 load=9.0 disk=41%%\n' >>"$tmp/runner-health.log"
out="$(RUNNER_TEMP="$tmp" bash "$script" report)"
contains "a starved disk warns with its worst figure" "$out" "up to 63.40%"

# start records the job's start and samples in the background.
: >"$tmp/env"
GITHUB_ENV="$tmp/env" RUNNER_TEMP="$tmp" RUNNER_HEALTH_PERIOD=1 bash "$script" start
sleep 2
contains "start records the job's start" "$(cat "$tmp/env")" "JOB_STARTED_AT="
expect "start samples the runner" "$([ -s "$tmp/runner-health.log" ] && echo yes)" yes
# A pattern kill (`pkill -f runner-health`) also matches this script's own command line and ends the whole run.
sampler="$(cat "$tmp/runner-health.pid" 2>/dev/null)"
RUNNER_TEMP="$tmp" bash "$script" stop
sleep 1
expect "stop ends the sampler" "$(kill -0 "${sampler:-0}" 2>/dev/null && echo alive || echo gone)" gone
expect "stop clears the pid file" "$([ -e "$tmp/runner-health.pid" ] && echo left || echo cleared)" cleared

echo "---"
echo "runner-health: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
