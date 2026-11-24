#!/usr/bin/env bash
#
# What machine a JVM test lane ran on: cores, memory, load and whether it is a VM.
#
#   runner-facts.sh <label> [file]   print the facts under <label>; also append them to [file] when given
#
# Run at the start and the end of each JVM test step, so a stall in between can be weighed against the host:
# a reader starved on 3 vCPUs, a host that paused the VM (Linux shows it as steal time), or neither.
# The file goes in build/jvm-logs/ beside the safepoint and GC logs, so a failure's artifact carries all three.
# The busiest processes are listed too: a load average is runnable threads inside this VM, so on a CI runner the
# CPUs may be held by this job's own Gradle daemons and workers.
#
# `start` with a file also starts a sampler that appends `mid` facts every RUNNER_FACTS_PERIOD seconds (60) during
# the step; `end` stops it.
#
# Never fails the job: a fact the host cannot report prints as "unknown".
set -uo pipefail

label="${1:?usage: $0 <label> [file]}"
file="${2:-}"

# The first line of a command's output, or "unknown" when the command is missing or fails.
first() { local out; out="$("$@" 2>/dev/null | head -n 1)" && [ -n "$out" ] && echo "$out" || echo unknown; }

facts() {
  local os
  os="$(uname -s 2>/dev/null || echo unknown)"
  echo "runner facts ($label) at $(date -u +%Y-%m-%dT%H:%M:%SZ) on $os"
  case "$os" in
    Darwin)
      echo "cores: $(first sysctl -n hw.ncpu)"
      local total page free
      total="$(sysctl -n hw.memsize 2>/dev/null)"
      page="$(sysctl -n hw.pagesize 2>/dev/null)"
      # Free, inactive and speculative pages: what the kernel can hand out without paging.
      free="$(vm_stat 2>/dev/null | awk -F: '/^Pages (free|inactive|speculative)/ { gsub(/[ .]/, "", $2); n += $2 } END { print n + 0 }')"
      if [ -n "$total" ] && [ -n "$page" ]; then
        echo "memory: total $((total / 1048576)) MiB, available $((free * page / 1048576)) MiB"
      else
        echo "memory: unknown"
      fi
      echo "load: $(first uptime)"
      echo "vm: hv_vmm_present=$(first sysctl -n kern.hv_vmm_present) cpu=$(first sysctl -n machdep.cpu.brand_string)"
      ;;
    *)
      echo "cores: $(first nproc)"
      if command -v free >/dev/null 2>&1; then
        echo "memory: $(free -m 2>/dev/null | awk '/^Mem:/ { print "total " $2 " MiB, available " $7 " MiB" }')"
      elif [ -r /proc/meminfo ]; then
        echo "memory: $(awk '/^MemTotal:/ { t = $2 } /^MemAvailable:/ { a = $2 } END { print "total " int(t / 1024) " MiB, available " int(a / 1024) " MiB" }' /proc/meminfo)"
      else
        echo "memory: unknown"
      fi
      echo "load: $(first uptime)"
      # systemd-detect-virt prints "none" and exits 1 on bare metal: its word is the fact either way.
      local vm=unknown
      command -v systemd-detect-virt >/dev/null 2>&1 && vm="$(systemd-detect-virt 2>/dev/null)"
      echo "vm: ${vm:-unknown} cpu=$(awk -F': ' '/^model name/ { print $2; exit }' /proc/cpuinfo 2>/dev/null)"
      # Cumulative CPU steal (USER_HZ ticks) since boot: the start and end lines differ by what the host took.
      [ -r /proc/stat ] && echo "steal: $(awk '/^cpu / { print $9 }' /proc/stat) ticks since boot"
      ;;
  esac
  if command -v ps >/dev/null 2>&1; then
    echo "busiest processes (pid ppid %cpu rssKB cputime command):"
    ps -A -o pid=,ppid=,%cpu=,rss=,time=,comm= 2>/dev/null | sort -k3 -nr | head -n 12 | sed 's/^ */  /'
  fi
  return 0
}

pidfile="${RUNNER_TEMP:-/tmp}/runner-facts-sampler.pid"
if [ "$label" = end ] && [ -s "$pidfile" ]; then
  kill "$(cat "$pidfile")" 2>/dev/null
  rm -f "$pidfile"
fi

out="$(facts)"
echo "$out"
if [ -n "$file" ]; then
  mkdir -p "$(dirname "$file")" 2>/dev/null && echo "$out" >>"$file" || echo "runner-facts: could not write $file"
  if [ "$label" = start ]; then
    script="$0"
    ( while sleep "${RUNNER_FACTS_PERIOD:-60}"; do bash "$script" mid "$file" >/dev/null; done ) >/dev/null 2>&1 &
    echo "$!" >"$pidfile"
    disown 2>/dev/null || true
  fi
fi
exit 0
