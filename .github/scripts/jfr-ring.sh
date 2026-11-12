#!/usr/bin/env bash
#
# Flight Recorder ring for Gradle test workers — the shell half of .github/actions/jvm-flight-recorder
# and .github/gradle/jfr-ring.init.gradle (which starts the ring inside every Test worker JVM).
#
#   start   <dir> <idle-seconds>     install the init script, arm the hang watchdog
#   dump    <dir> <reason>           jcmd JFR.dump of every live test worker (failure + hang paths)
#   watch   <dir> <idle-seconds>     (internal) dump a worker that has made no progress for that long
#   collect <dir> <out-dir>          compress, cap at ~20 MB newest-kept, say what was cut
#
# Never changes a job's result: bounded commands, every failure reported and skipped. Deliberately
# NOT `set -e`. Tested by tests/jfr-ring.test.sh against a real JVM.
set -uo pipefail

CAP_BYTES="${JFR_RING_CAP_BYTES:-20971520}"   # 20 MB of compressed recordings per job
MAX_DUMPS_PER_PID="${JFR_RING_MAX_DUMPS_PER_PID:-4}"
MIN_GAP_SECONDS="${JFR_RING_MIN_GAP_SECONDS:-15}" # a cascade of failures shares one dump
POLL_SECONDS="${JFR_RING_POLL_SECONDS:-15}"
WORKER_PATTERN="${JFR_RING_WORKER_PATTERN:-Gradle Test Executor}"
ROOT="${JFR_RING_ROOT:-$PWD}"

# The test workers' pids. `jcmd -l` rather than pgrep: it exists wherever a JDK does (the Windows
# runner's git-bash has no pgrep) and it lists only JVMs this user can attach to.
# JFR_RING_OWNER (the Gradle daemon's pid, passed by the init script) narrows this to workers that
# daemon forked, so a local run never attaches to somebody else's build. Unset in CI, where the
# runner is ours alone and the watchdog has no daemon pid to name.
workers() {
  local p
  for p in $(jcmd -l 2>/dev/null | grep -F "$WORKER_PATTERN" | awk '{print $1}'); do
    if [ -z "${JFR_RING_OWNER:-}" ] || [ "$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ')" = "$JFR_RING_OWNER" ]; then echo "$p"; fi
  done
}

# Run a command for at most <secs>. No `timeout` on macOS.
bounded() {
  local secs="$1"; shift
  "$@" & local pid=$!
  ( sleep "$secs"; kill "$pid" 2>/dev/null; sleep 2; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  local killer=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$killer" 2>/dev/null; wait "$killer" 2>/dev/null
  return "$rc"
}

now() { date +%s; }

do_dump() { # <dir> <reason>
  local dir="$1" reason="${2:-unspecified}" pid n last file stamp
  mkdir -p "$dir"
  local pids; pids="$(workers)"
  if [ -z "$pids" ]; then echo "no live test worker to dump ($reason)"; return 0; fi
  for pid in $pids; do
    n=$(ls "$dir" 2>/dev/null | grep -c "^worker-$pid-.*\.jfr$")
    if [ "$n" -ge "$MAX_DUMPS_PER_PID" ]; then echo "worker $pid: $n dumps already, skipping ($reason)"; continue; fi
    last=$(cat "$dir/.last-$pid" 2>/dev/null || echo 0)
    if [ $(( $(now) - last )) -lt "$MIN_GAP_SECONDS" ]; then
      echo "worker $pid: dumped $MIN_GAP_SECONDS s ago, this one is part of the same cascade ($reason)"; continue
    fi
    now >"$dir/.last-$pid"
    stamp="$(date -u +%H%M%S)-$((n + 1))"
    file="$dir/worker-$pid-$stamp.jfr"
    if bounded 60 jcmd "$pid" JFR.dump name=ci-ring "filename=$file" >"$file.log" 2>&1 && [ -s "$file" ]; then
      printf 'reason: %s\nworker: %s\nwhen: %s\n' "$reason" "$pid" "$(date -u +%FT%TZ)" >"$file.txt"
      # The recorder only writes a park/monitor/socket wait when it ENDS, so a worker that is hung
      # right now shows its parked threads in the periodic jdk.ThreadDump events; keep a plain
      # thread dump next to it for reading without tools.
      bounded 30 jcmd "$pid" Thread.print >"$dir/worker-$pid-$stamp.threads.txt" 2>&1 || true
      echo "worker $pid: dumped $(wc -c <"$file" | tr -d ' ') bytes to $(basename "$file") ($reason)"
    else
      echo "worker $pid: JFR.dump produced nothing ($reason): $(tr '\n' ' ' <"$file.log" | head -c 200)"
      rm -f "$file" "$file.log"
    fi
  done
}

do_watch() { # <dir> <idle-seconds>  — a test worker alive but no test-results file touched for that long
  local dir="$1" idle="$2" ref="$1/.progress" last
  mkdir -p "$dir"; touch "$ref"; last=$(now)
  local -a seen=()
  while sleep "$POLL_SECONDS"; do
    [ -f "$dir/.stop" ] && return 0
    if [ -n "$(find "$ROOT" -path '*/test-results/*' -type f -newer "$ref" -print -quit 2>/dev/null)" ]; then last=$(now); fi
    touch "$ref"
    local pids; pids="$(workers)"
    [ -z "$pids" ] && { last=$(now); continue; }
    if [ $(( $(now) - last )) -ge "$idle" ]; then
      do_dump "$dir" "hang: test workers alive, no test result written for $(( $(now) - last )) s" || true
      last=$(now)   # one dump per idle window, bounded by MAX_DUMPS_PER_PID
    fi
  done
}

do_collect() { # <dir> <out-dir>
  local dir="$1" out="$2" f total=0 kept=0 cut=0 size
  [ -d "$dir" ] || { echo "jfr-ring: nothing recorded"; return 0; }
  touch "$dir/.stop" 2>/dev/null
  mkdir -p "$out"
  # newest first, so the cap drops the oldest recordings
  for f in $(ls -t "$dir"/*.jfr 2>/dev/null); do
    gzip -9 -c "$f" >"$out/$(basename "$f").gz" 2>/dev/null || { rm -f "$out/$(basename "$f").gz"; continue; }
    size=$(wc -c <"$out/$(basename "$f").gz" | tr -d ' ')
    if [ $(( total + size )) -gt "$CAP_BYTES" ]; then
      rm -f "$out/$(basename "$f").gz"; cut=$((cut + 1)); echo "jfr-ring: TRUNCATED, dropped $(basename "$f") ($size bytes) to stay under $CAP_BYTES"
      continue
    fi
    total=$((total + size)); kept=$((kept + 1))
    cp "$f.txt" "$out/" 2>/dev/null; cp "${f%.jfr}.threads.txt" "$out/" 2>/dev/null
    echo "jfr-ring: kept $(basename "$f").gz ($size bytes) $(tr '\n' ' ' <"$f.txt" 2>/dev/null)"
  done
  if [ "$kept" -gt 0 ]; then
    cat >"$out/README.txt" <<'TXT'
Flight Recorder ring (about the last 60 s before the dump) of a Gradle test worker JVM.
  gunzip worker-*.jfr.gz
  jfr summary worker-*.jfr
  jfr print --events jdk.ThreadDump worker-*.jfr    # who was parked where, every 10 s: a thread in the same stack across dumps is the stall
  jfr print --events jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.SocketRead worker-*.jfr
  jfr print --events jdk.GCPhasePause,jdk.CPULoad worker-*.jfr
or open in JDK Mission Control / IntelliJ. *.threads.txt is a plain jcmd Thread.print taken with the dump.
TXT
  fi
  echo "jfr-ring: $kept recording(s), $total bytes compressed, $cut dropped"
}

case "${1:-}" in
  start)
    dir="$2"; idle="${3:-600}"; gh="$(cd "$(dirname "$0")/.." && pwd)"
    mkdir -p "$dir" "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
    cp "$gh/gradle/jfr-ring.init.gradle" "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/jfr-ring.init.gradle"
    if [ "$idle" -gt 0 ]; then
      JFR_RING_ROOT="$ROOT" nohup "$0" watch "$dir" "$idle" >"$dir/watch.log" 2>&1 &
      disown 2>/dev/null || true
    fi
    echo "jfr-ring: armed, dir=$dir hang-idle=${idle}s init=${GRADLE_USER_HOME:-$HOME/.gradle}/init.d" ;;
  dump) do_dump "$2" "${3:-}" ;;
  watch) do_watch "$2" "$3" ;;
  collect) do_collect "$2" "$3" ;;
  *) echo "usage: $0 start|dump|watch|collect ..." >&2; exit 2 ;;
esac
exit 0
