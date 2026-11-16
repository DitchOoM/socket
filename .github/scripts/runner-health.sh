#!/usr/bin/env bash
#
# What a slow runner looked like, and a hang watchdog that fits inside the job's own budget.
#
#   start                    record the job's start time and sample the runner every RUNNER_HEALTH_PERIOD seconds
#   report                   print the samples and warn when the disk or CPU was starved (run with `if: always()`)
#   hang-wait <cap> <job-min> seconds to wait before dumping thread stacks: <cap>, or less when the job's timeout
#                            would arrive first
#
# Why: run 43918562845's "QUIC quiche JVM (JDK 17 / JNI)" spent 21 of its 40 minutes before its test step began
# (an apt install of 15 minutes where its siblings took one, dpkg's "Reading database" crawling for minutes, docker
# image pulls taking ten), then the job timeout cancelled it. The test step's lid (24 min) and its 20-minute hang
# dump both counted from the step's start, so neither fired and the log held no stack and no sign of why the host
# was slow. The samples name a starved disk; the shortened wait puts the stacks into the log first.
#
# Never fails the job.
set -uo pipefail

period="${RUNNER_HEALTH_PERIOD:-20}"
dir="${RUNNER_TEMP:-/tmp}"
log="$dir/runner-health.log"

pressure() { # <file>: the avg10 of the "some" line, or nothing where the kernel has no pressure files
  [ -r "$1" ] && sed -n 's/^some avg10=\([0-9.]*\).*/\1/p' "$1"
}

sample() {
  {
    printf '%s io=%s cpu=%s mem=%s load=%s' "$(date -u +%H:%M:%S)" "$(pressure /proc/pressure/io)" \
      "$(pressure /proc/pressure/cpu)" "$(pressure /proc/pressure/memory)" "$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)"
    printf ' disk=%s\n' "$(df -P / 2>/dev/null | awk 'NR == 2 { print $5 }')"
  } >>"$log"
}

case "${1:-}" in
  start)
    [ -n "${GITHUB_ENV:-}" ] && echo "JOB_STARTED_AT=$(date +%s)" >>"$GITHUB_ENV"
    : >"$log"
    ( while true; do sample; sleep "$period"; done ) >/dev/null 2>&1 &
    disown 2>/dev/null || true
    ;;
  report)
    [ -s "$log" ] || { echo "no runner health samples"; exit 0; }
    echo "::group::runner health (time, I/O, CPU and memory stall % over 10 s, load, root disk used)"
    tail -n 150 "$log"
    echo "::endgroup::"
    worst="$(sed -n 's/.* io=\([0-9.]*\) .*/\1/p' "$log" | sort -n | tail -1)"
    if [ -n "$worst" ] && awk -v w="$worst" 'BEGIN { exit !(w >= 40) }'; then
      echo "::warning title=Runner disk starved::tasks waited on I/O up to ${worst}% of a 10 s window; a slow setup or test phase on this job is the host's, not the commit's."
    fi
    ;;
  hang-wait)
    cap="${2:?cap seconds}"; job_min="${3:?job minutes}"
    elapsed=$(( $(date +%s) - ${JOB_STARTED_AT:-$(date +%s)} ))
    left=$(( job_min * 60 - elapsed - 180 ))
    if [ "$left" -lt "$cap" ]; then cap=$left; fi
    [ "$cap" -ge 1 ] || cap=1
    echo "$cap"
    ;;
  *)
    echo "usage: $0 start | report | hang-wait <cap-seconds> <job-minutes>" >&2
    exit 2
    ;;
esac
