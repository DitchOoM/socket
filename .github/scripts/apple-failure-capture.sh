#!/usr/bin/env bash
#
# System evidence for an Apple CI job that failed, crashed or hung. Driven by
# .github/actions/apple-failure-capture; tests/apple-failure-capture.test.sh exercises it against a
# real crash and a real hang.
#
#   start   <dir> <budget-minutes> <kill:0|1> [ring: none|sample|xctrace]
#                                               drop the marker, arm the hang watchdog and, for a ring
#                                               mode, the timeline recorder
#   collect <dir> <out-dir> [simulator-udid]     gather everything since the marker
#   watch   <dir> <budget-minutes> <kill:0|1>   (internal) the detached watchdog
#   ring    <dir> <mode>                         (internal) the detached timeline recorder
#   summarize-ips <file.ips>                     (internal) print the crashed thread
#
# WHY: on 2026-11-11 a macOS test binary died with "Segmentation fault: 11" and nothing else, and it
# took a throwaway probe PR to get a stack (#850 then collected the crash report for ONE job). The
# same day an iOS-simulator public-endpoint test stalled in a QUIC handshake three times and the job
# had no stacks of the stuck process. The Android lanes keep logcat and the Linux lane keeps core
# dumps; this is the Apple equivalent, shared by every Apple test job.
#
# Never changes a job's result: every command here is bounded in time and size and a failure of any
# one piece is reported and skipped. Deliberately NOT `set -e`.
set -uo pipefail

# ── caps (the job log says what was cut) ─────────────────────────────────────────────────────────
CRASH_WAIT_SECONDS="${CAPTURE_CRASH_WAIT_SECONDS:-30}" # the crash reporter writes seconds after death
LOG_SHOW_SECONDS="${CAPTURE_LOG_SHOW_SECONDS:-75}"     # per `log show` (host and simulator run in parallel)
LOG_CAP_BYTES="${CAPTURE_LOG_CAP_BYTES:-20971520}"     # last 20 MB of each log, then gzip
FILE_CAP_BYTES="${CAPTURE_FILE_CAP_BYTES:-5242880}"    # any single crash report / hs_err / sim log file
TOTAL_CAP_KB="${CAPTURE_TOTAL_CAP_KB:-46080}"          # 45 MB: the whole artifact stays under ~50 MB
SAMPLE_SECONDS="${CAPTURE_SAMPLE_SECONDS:-5}"
MAX_PIDS=6
RING_CAP_BYTES="${CAPTURE_RING_CAP_BYTES:-20971520}"          # 20 MB of ring per job, compressed, newest kept
RING_SAMPLE_INTERVAL="${CAPTURE_RING_SAMPLE_INTERVAL:-5}"     # seconds between `sample <pid> 1` snapshots
RING_SAMPLE_KEEP="${CAPTURE_RING_SAMPLE_KEEP:-10}"            # the last ~50 s of snapshots
RING_XCTRACE_WINDOW="${CAPTURE_RING_XCTRACE_WINDOW:-30}"      # seconds per Time Profiler recording
RING_XCTRACE_KEEP="${CAPTURE_RING_XCTRACE_KEEP:-2}"           # recordings kept (the last ~60 s)
RING_XCTRACE_SAVE_WAIT="${CAPTURE_RING_XCTRACE_SAVE_WAIT:-45}" # xctrace needs this long to save a stopped recording
NATIVE_NAME="${CAPTURE_NATIVE_NAME:-test.kexe}"        # exact process name of a K/N test binary
JVM_PATTERN="${CAPTURE_JVM_PATTERN:-GradleWorkerMain}" # command-line match for a Gradle test worker
KILL_PATTERNS="${CAPTURE_KILL_PATTERNS:-$JVM_PATTERN GradleDaemon}"
DIAG_DIRS="${CAPTURE_DIAG_DIRS:-$HOME/Library/Logs/DiagnosticReports:/Library/Logs/DiagnosticReports}"

# Run a command for at most <secs>; macOS has no `timeout`. Output goes wherever the caller pointed
# it. Returns the command's status, or 143 if the budget killed it.
bounded() {
  local secs="$1"
  shift
  "$@" &
  local pid=$!
  (
    sleep "$secs"
    kill "$pid" 2>/dev/null
    sleep 2
    kill -9 "$pid" 2>/dev/null
  ) >/dev/null 2>&1 &
  local killer=$!
  wait "$pid" 2>/dev/null
  local rc=$?
  pkill -P "$killer" 2>/dev/null
  kill "$killer" 2>/dev/null
  wait "$killer" 2>/dev/null
  return "$rc"
}

# Keep the last <cap> bytes of <file>, gzip it, and say so when something was cut.
cap_and_gzip() { # <file> <cap-bytes>
  local f="$1" cap="$2" size
  [ -s "$f" ] || { rm -f "$f"; return 0; }
  size=$(wc -c <"$f" | tr -d ' ')
  if [ "$size" -gt "$cap" ]; then
    tail -c "$cap" "$f" >"$f.tail" && mv "$f.tail" "$f"
    echo "(capped: kept the last $cap of $size bytes)" >>"$f.capped.txt"
  fi
  gzip -f "$f" 2>/dev/null || true
}

# ── crash report summary ─────────────────────────────────────────────────────────────────────────
# .ips = one JSON header line, then a JSON body: exception/signal, the faulting thread's frames, and
# usedImages to turn an imageIndex into a library name. Readable in the job log without a download.
summarize_ips() {
  python3 -I - "$1" <<'PY'
import json, sys

path = sys.argv[1]
text = open(path, errors="replace").read()
header, _, body = text.partition("\n")
doc = None
for candidate in (body, text):
    try:
        doc = json.loads(candidate)
        break
    except ValueError:
        continue
if not isinstance(doc, dict):
    print("  (could not parse as JSON; first lines follow)")
    print("\n".join(text.splitlines()[:20]))
    sys.exit(0)

exc = doc.get("exception") or {}
term = doc.get("termination") or {}
print("  process : %s (pid %s)" % (doc.get("procName", "?"), doc.get("pid", "?")))
print("  time    : %s" % doc.get("captureTime", doc.get("timestamp", "?")))
print("  signal  : %s %s %s" % (exc.get("type", "?"), exc.get("signal", ""), exc.get("subtype", "")))
if term:
    print("  termination: %s %s" % (term.get("namespace", ""), term.get("indicator", term.get("reason", ""))))
images = doc.get("usedImages") or []
threads = doc.get("threads") or []
idx = doc.get("faultingThread")
if idx is None:
    idx = next((i for i, t in enumerate(threads) if t.get("triggered")), None)
if idx is None or idx >= len(threads):
    print("  no faulting thread recorded")
    sys.exit(0)
t = threads[idx]
print("  crashed thread %d%s:" % (idx, " (%s)" % t["name"] if t.get("name") else (" [queue %s]" % t["queue"] if t.get("queue") else "")))
for n, f in enumerate(t.get("frames") or []):
    if n >= 30:
        print("    ... %d more" % (len(t["frames"]) - n))
        break
    ii = f.get("imageIndex")
    name = images[ii].get("name", "?") if isinstance(ii, int) and ii < len(images) else "?"
    sym = f.get("symbol")
    where = "%s+%s" % (sym, f.get("symbolLocation", 0)) if sym else "0x%x" % (f.get("imageOffset", 0))
    print("    %2d  %-28s %s" % (n, name[:28], where))
PY
}

summarize_crash_text() { # legacy .crash: header + the crashed thread's block
  grep -aE '^(Process|Path|Exception Type|Exception Codes|Termination Reason|Crashed Thread):' "$1" | head -8 | sed 's/^/  /'
  awk '/^Thread [0-9]+ Crashed/ { p = 1 } p && /^$/ { exit } p' "$1" | head -40 | sed 's/^/  /'
}

# ── hang evidence ────────────────────────────────────────────────────────────────────────────────
# All thread stacks of every live test process. `sample` needs no privilege for a same-user process,
# which a test.kexe and a Gradle worker are; spindump is only kept if it runs without sudo.
dump_stacks() { # <outdir>
  local out="$1" pid n=0 pids
  mkdir -p "$out"
  {
    date -u
    ps -axo pid,ppid,etime,rss,command 2>/dev/null | grep -E "${NATIVE_NAME//./\\.}|${JVM_PATTERN}|GradleDaemon" | grep -v grep
  } >"$out/hang-processes.txt" 2>&1
  pids=$(pgrep -x "$NATIVE_NAME" 2>/dev/null | head -"$MAX_PIDS")
  for pid in $pids; do
    # In parallel: each `sample` holds its target for SAMPLE_SECONDS.
    (
      if command -v sample >/dev/null 2>&1; then
        bounded $((SAMPLE_SECONDS + 40)) sample "$pid" "$SAMPLE_SECONDS" -file "$out/sample-$NATIVE_NAME-$pid.txt" >"$out/sample-$pid.stdout" 2>&1 ||
          echo "sample $pid failed; see sample-$pid.stdout" >>"$out/hang-errors.txt"
      fi
      if command -v spindump >/dev/null 2>&1; then
        # Unprivileged only: it fails with "must be run as root" on most hosts, which is the answer.
        bounded 60 spindump "$pid" "$SAMPLE_SECONDS" -file "$out/spindump-$pid.txt" >"$out/spindump-$pid.stdout" 2>&1 ||
          echo "spindump $pid failed without sudo: $(head -c 200 "$out/spindump-$pid.stdout" | tr '\n' ' ')" >>"$out/hang-errors.txt"
        [ -s "$out/spindump-$pid.txt" ] || rm -f "$out/spindump-$pid.txt"
      fi
    ) &
  done
  for pid in $(pgrep -f "$JVM_PATTERN" 2>/dev/null | head -"$MAX_PIDS"); do
    (
      if command -v jcmd >/dev/null 2>&1; then
        bounded 30 jcmd "$pid" Thread.print -l >"$out/jvm-threads-$pid.txt" 2>&1
      elif command -v jstack >/dev/null 2>&1; then
        bounded 30 jstack -l "$pid" >"$out/jvm-threads-$pid.txt" 2>&1
      fi
    ) &
  done
  wait
  # A pid that exited between pgrep and sample leaves an empty stdout; drop empties.
  find "$out" -type f -size 0 -exec rm -f {} + 2>/dev/null || true
  n=$(find "$out" -type f | wc -l | tr -d ' ')
  echo "$n"
}

watch() { # <dir> <budget-minutes> <kill>
  local dir="$1" budget="$2" kill="$3" start deadline
  start=$(cat "$dir/start.epoch")
  deadline=$((start + budget * ${CAPTURE_MINUTE_SECONDS:-60}))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 10
    [ -e "$dir/stop" ] && exit 0
  done
  # Nothing running means the test step already ended; there is nothing to look at.
  if ! pgrep -x "$NATIVE_NAME" >/dev/null 2>&1 && ! pgrep -f "$JVM_PATTERN" >/dev/null 2>&1; then
    echo "$(date -u) budget reached, no test process alive" >"$dir/watchdog.log"
    exit 0
  fi
  echo "$(date -u) budget of ${budget} min reached with test processes alive; dumping stacks" >"$dir/watchdog.log"
  dump_stacks "$dir/hang" >>"$dir/watchdog.log" 2>&1
  touch "$dir/watchdog-fired"
  if [ "$kill" = "1" ]; then
    # Now that the stacks exist, end the step so the job keeps time to run the capture and upload
    # steps. A job-level timeout would cancel it with neither.
    # Only this job's own test processes: the K/N binary by exact name, then the Gradle worker and
    # daemon by command line (KILL_PATTERNS; the daemon too, or `--continue` would start the next
    # module's tests with the budget already spent).
    pkill -x "$NATIVE_NAME" 2>/dev/null
    for pattern in $KILL_PATTERNS; do pkill -f "$pattern" 2>/dev/null; done
    echo "$(date -u) killed the test processes" >>"$dir/watchdog.log"
  fi
}


# ── timeline ring ────────────────────────────────────────────────────────────────────────────────
# The hang watchdog above takes stacks of ONE instant. The ring is the minute before it: where each
# thread of a test process was, over time, kept on disk and uploaded only if the job fails.
#
#   sample   `sample <pid> 1` every few seconds into a ring of the last RING_SAMPLE_KEEP text files.
#            No privilege, ~8 KB per snapshot of a small process (a few hundred KB all told).
#   xctrace  `xctrace record --template 'Time Profiler' --attach <pid>` in RING_XCTRACE_WINDOW-second
#            recordings, the last RING_XCTRACE_KEEP kept. Measured on a hosted macos-26 runner
#            (Xcode 26.6): attaching to one's own process needs no sudo and no GUI session, a 10 s
#            recording of a one-thread busy loop is a 3 MB bundle (1 MB zstd), and saving it takes
#            ~12 s, during which nothing is recorded: the ring has a blind spot after each window.
#
# Both attach to the exact-name K/N test binary ($NATIVE_NAME); a JVM test worker has JFR instead.
ring_pid_loop() { # <ringdir> <pid> <mode>
  local d="$1/pid-$2" pid="$2" mode="$3" seq=0 f
  mkdir -p "$d"
  while kill -0 "$pid" 2>/dev/null; do
    [ -e "$1/stop" ] && return 0
    seq=$((seq + 1))
    f=$(printf '%s/%s-%06d' "$d" "$mode" "$seq")
    case "$mode" in
      sample)
        bounded 20 sample "$pid" 1 -file "$f.txt" >/dev/null 2>&1 || rm -f "$f.txt"
        ls -t "$d"/sample-*.txt 2>/dev/null | tail -n +$((RING_SAMPLE_KEEP + 1)) | while IFS= read -r old; do rm -f "$old"; done
        sleep "$RING_SAMPLE_INTERVAL" ;;
      xctrace)
        # --no-prompt: never ask. A window that ends because the process died saves a final partial one.
        xcrun xctrace record --template 'Time Profiler' --attach "$pid" --time-limit "${RING_XCTRACE_WINDOW}s" \
          --output "$f.trace" --no-prompt >"$f.log" 2>&1 </dev/null &
        echo $! >"$d/xctrace.pid"
        wait $! 2>/dev/null
        ls -td "$d"/xctrace-*.trace 2>/dev/null | tail -n +$((RING_XCTRACE_KEEP + 1)) | while IFS= read -r old; do rm -rf "$old" "${old%.trace}.log"; done ;;
    esac
  done
}

ring() { # <dir> <mode>
  local dir="$1" mode="$2" rd="$1/ring" pid
  mkdir -p "$rd"
  while sleep 2; do
    [ -e "$rd/stop" ] && exit 0
    for pid in $(pgrep -x "$NATIVE_NAME" 2>/dev/null | head -"$MAX_PIDS"); do
      [ -d "$rd/pid-$pid" ] && continue
      ring_pid_loop "$rd" "$pid" "$mode" </dev/null >>"$rd/ring.log" 2>&1 &
    done
  done
}

# Stop the recorders (a recording being saved needs RING_XCTRACE_SAVE_WAIT s), then compress what is
# there into <out>/ring, newest first, under RING_CAP_BYTES; the log says what was cut.
collect_ring() { # <dir> <out>
  local dir="$1" out="$2/ring" rd="$1/ring" total=0 kept=0 cut=0 f size name waited=0 t
  [ -d "$rd" ] || return 0
  touch "$rd/stop"
  for f in "$rd"/pid-*/xctrace.pid; do
    [ -f "$f" ] || continue
    kill -INT "$(cat "$f")" 2>/dev/null
  done
  # xctrace saves on SIGINT; wait for every recorder to finish writing.
  while pgrep -f "xctrace record --template" >/dev/null 2>&1 && [ "$waited" -lt "$RING_XCTRACE_SAVE_WAIT" ]; do sleep 2; waited=$((waited + 2)); done
  pkill -f "xctrace record --template" 2>/dev/null
  mkdir -p "$out"
  for t in $(ls -td "$rd"/pid-*/xctrace-*.trace "$rd"/pid-*/sample-*.txt 2>/dev/null); do
    name="$(basename "$(dirname "$t")")-$(basename "$t")"
    case "$t" in
      *.trace)
        [ -d "$t/Trace1.run" ] || { echo "ring: $name is not a finished recording (the save was cut short), skipped"; continue; }
        if command -v zstd >/dev/null 2>&1; then
          tar -cf - -C "$(dirname "$t")" "$(basename "$t")" 2>/dev/null | zstd -q -19 -T0 -o "$out/$name.tar.zst" 2>/dev/null
          f="$out/$name.tar.zst"
        else
          tar -czf "$out/$name.tgz" -C "$(dirname "$t")" "$(basename "$t")" 2>/dev/null
          f="$out/$name.tgz"
        fi ;;
      *) gzip -9 -c "$t" >"$out/$name.gz" 2>/dev/null; f="$out/$name.gz" ;;
    esac
    size=$(wc -c <"$f" | tr -d ' ')
    if [ $((total + size)) -gt "$RING_CAP_BYTES" ]; then
      rm -f "$f"; cut=$((cut + 1)); echo "ring: TRUNCATED, dropped $(basename "$f") ($size bytes) to stay under $RING_CAP_BYTES"; continue
    fi
    total=$((total + size)); kept=$((kept + 1))
  done
  if [ "$kept" -gt 0 ]; then
    cat >"$out/README.txt" <<'TXT'
Timeline ring of a test process (the minutes before the job failed).
  pid-*-xctrace-*.tar.zst  Time Profiler recordings: `zstd -dc x.tar.zst | tar -xf -` then `open x.trace` (Instruments),
                     or `xcrun xctrace export --input x.trace --xpath '/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]'`
  pid-*-sample-*.txt.gz    `sample <pid> 1` snapshots, oldest first by the number in the name: gunzip -c, then read "Call graph:"
A hot frame in every snapshot, or a thread parked in the same frame across all of them, is the stall.
TXT
  fi
  echo "ring: $kept file(s), $total bytes compressed, $cut dropped"
}

start() { # <dir> <budget-minutes> <kill> [ring-mode]
  local dir="$1" budget="$2" kill="$3" ringmode="${4:-none}"
  rm -rf "$dir"
  mkdir -p "$dir"
  date +%s >"$dir/start.epoch"
  touch "$dir/marker"
  if [ "$budget" -gt 0 ] 2>/dev/null; then
    # Detached with every stdio closed so the step returns; the runner reaps it at job end.
    nohup "$(cd "$(dirname "$0")" && pwd)/$(basename "$0")" watch "$dir" "$budget" "$kill" </dev/null >/dev/null 2>&1 &
    echo $! >"$dir/watchdog.pid"
    disown 2>/dev/null || true
  fi
  case "$ringmode" in
    sample|xctrace)
      nohup "$(cd "$(dirname "$0")" && pwd)/$(basename "$0")" ring "$dir" "$ringmode" </dev/null >/dev/null 2>&1 &
      echo $! >"$dir/ring.pid"
      disown 2>/dev/null || true ;;
    none) ;;
    *) echo "unknown ring mode '$ringmode' (none|sample|xctrace): no ring" ;;
  esac
  echo "capture marker set at $(date -u) (hang watchdog: ${budget} min, kill=${kill}, timeline ring: ${ringmode})"
}

# ── collect ──────────────────────────────────────────────────────────────────────────────────────
find_reports() { # <dir>/marker -> report paths newer than the marker
  local marker="$1" d IFS=:
  for d in $DIAG_DIRS; do
    [ -d "$d" ] || continue
    find "$d" -type f \( -name '*.ips' -o -name '*.crash' -o -name '*.diag' -o -name '*.spin' -o -name '*.hang' \) \
      -newer "$marker" 2>/dev/null
  done
}

PREDICATE='(process CONTAINS "test.kexe") OR (process == "java") OR (process IN {"trustd","nsurlsessiond","mDNSResponder","networkd","nehelper","symptomsd","ReportCrash"}) OR (subsystem BEGINSWITH "com.apple.network")'

collect() { # <dir> <out> [udid]
  local dir="$1" out="$2" udid="${3:-}" since reports f base waited=0 found n
  mkdir -p "$out/crash-reports" "$out/hang" "$out/logs"
  if [ ! -e "$dir/marker" ]; then
    echo "::warning::no capture marker in $dir (the start step did not run); collecting without a time window"
    mkdir -p "$dir" && date +%s >"$dir/start.epoch" && touch -t 197001020000 "$dir/marker"
  fi
  local start_epoch
  start_epoch=$(cat "$dir/start.epoch" 2>/dev/null || echo 0)
  since=$(date -r "$start_epoch" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -d "@$start_epoch" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
  local t0
  t0=$(date +%s)

  # The hang watchdog's stacks (taken BEFORE the kill) come first; stop it so it cannot fire late.
  touch "$dir/stop"
  [ -f "$dir/watchdog.pid" ] && kill "$(cat "$dir/watchdog.pid")" 2>/dev/null
  if [ -d "$dir/hang" ]; then cp -R "$dir/hang/." "$out/hang/" 2>/dev/null; fi
  [ -f "$dir/watchdog.log" ] && cp "$dir/watchdog.log" "$out/hang/watchdog.log"
  if [ -e "$dir/watchdog-fired" ]; then
    echo "::error title=Hang watchdog::a test process was still running at the budget; its stacks are in $out/hang (sample-*.txt, jvm-threads-*.txt)"
  fi
  # Anything still alive now (the step failed but left a process behind) is worth a look too.
  if pgrep -x "$NATIVE_NAME" >/dev/null 2>&1 || pgrep -f "$JVM_PATTERN" >/dev/null 2>&1; then
    echo "test processes still alive at failure; sampling them"
    dump_stacks "$out/hang/at-failure" >/dev/null
  fi

  # The timeline ring, stopped and compressed (it can take up to RING_XCTRACE_SAVE_WAIT s).
  [ -f "$dir/ring.pid" ] && kill "$(cat "$dir/ring.pid")" 2>/dev/null
  collect_ring "$dir" "$out"

  # Unified log: started in the background now, collected while the crash reporter is awaited.
  local log_pids=""
  if command -v log >/dev/null 2>&1; then
    # `log show` reads the host's store. Fires only on a Mac; a Linux test host has no `log` binary.
    ( bounded "$LOG_SHOW_SECONDS" log show --start "$since" --style compact --predicate "$PREDICATE" >"$out/logs/unified-log-host.txt" 2>"$out/logs/unified-log-host.err"
      cap_and_gzip "$out/logs/unified-log-host.txt" "$LOG_CAP_BYTES" ) &
    log_pids="$!"
    if [ -n "$udid" ] && command -v xcrun >/dev/null 2>&1; then
      # `simctl spawn <udid> log show` reads the SIMULATOR's own store (its processes log into the
      # device's launchd_sim, not the host's); the host form only sees the host. Verified locally
      # in tests/apple-failure-capture.test.sh's simulator section.
      ( bounded "$LOG_SHOW_SECONDS" xcrun simctl spawn "$udid" log show --start "$since" --style compact --predicate "$PREDICATE" >"$out/logs/unified-log-simulator.txt" 2>"$out/logs/unified-log-simulator.err"
        cap_and_gzip "$out/logs/unified-log-simulator.txt" "$LOG_CAP_BYTES" ) &
      log_pids="$log_pids $!"
    fi
  fi

  # Crash reports: poll for the reporter, then give a just-started report a moment to finish.
  reports=$(find_reports "$dir/marker")
  while [ -z "$reports" ] && [ "$waited" -lt "$CRASH_WAIT_SECONDS" ]; do
    sleep 3
    waited=$((waited + 3))
    reports=$(find_reports "$dir/marker")
  done
  if [ -n "$reports" ]; then
    [ "$waited" -gt 0 ] && sleep 3
    reports=$(find_reports "$dir/marker")
  fi
  if [ -z "$reports" ]; then
    echo "no crash report was written under $DIAG_DIRS after the marker (waited ${waited}s)"
  else
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      base=$(basename "$f")
      head -c "$FILE_CAP_BYTES" "$f" >"$out/crash-reports/$base"
      echo "::group::crash report $base"
      case "$base" in
        *.ips) summarize_ips "$f" ;;
        *.crash) summarize_crash_text "$f" ;;
        *) head -40 "$f" | sed 's/^/  /' ;;
      esac
      echo "::endgroup::"
    done <<<"$reports"
    echo "::error title=Crash report::$(echo "$reports" | grep -c .) crash/hang report(s) written during this job: $(echo "$reports" | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
  fi

  # Simulator crashes land in the HOST's DiagnosticReports (covered above); the device's own logs.
  if [ -n "$udid" ] && [ -d "$HOME/Library/Logs/CoreSimulator/$udid" ]; then
    mkdir -p "$out/coresimulator-$udid"
    find "$HOME/Library/Logs/CoreSimulator/$udid" -type f -size -"${FILE_CAP_BYTES}"c -newer "$dir/marker" 2>/dev/null |
      head -40 | while IFS= read -r f; do cp "$f" "$out/coresimulator-$udid/" 2>/dev/null; done
    [ -f "$HOME/Library/Logs/CoreSimulator/$udid/system.log" ] &&
      tail -c "$FILE_CAP_BYTES" "$HOME/Library/Logs/CoreSimulator/$udid/system.log" >"$out/coresimulator-$udid/system.log.tail"
  fi

  # JVM crash logs: hs_err / replay files from the workspace and the usual spill directories.
  mkdir -p "$out/jvm"
  for root in "${GITHUB_WORKSPACE:-.}" /tmp "${TMPDIR:-/tmp}"; do
    [ -d "$root" ] || continue
    find "$root" -maxdepth 8 \( -name .git -o -name node_modules -o -name .gradle \) -prune -o \
      -type f \( -name 'hs_err_pid*.log' -o -name 'replay_pid*.log' \) -newer "$dir/marker" -print 2>/dev/null
  done | sort -u | head -20 | while IFS= read -r f; do
    head -c "$FILE_CAP_BYTES" "$f" >"$out/jvm/$(basename "$f")"
    echo "::error title=JVM crash log::$f"
    grep -aE '^# (SIGSEGV|SIGABRT|SIGBUS|Problematic frame|C  |V  |J  )|^# +[A-Za-z]+ \(' "$f" | head -8 | sed 's/^/  /'
  done

  # Network state (cheap): enough to tell "the runner had no route/DNS" from "our stack hung".
  {
    echo "### date"; date -u
    echo "### ifconfig -a (interfaces, addresses, status)"
    ifconfig -a 2>&1 | grep -E '^[a-z0-9]+:|	inet6? |status:' | head -120
    echo "### netstat -rn"; netstat -rn 2>&1 | head -60
    echo "### scutil --dns (head)"; scutil --dns 2>&1 | head -40
    if command -v xcrun >/dev/null 2>&1; then
      echo "### simctl list devices (booted / state)"; xcrun simctl list devices 2>&1 | head -80
    fi
  } >"$out/network-state.txt" 2>&1

  # Wait for the logs, but never past their own bound.
  for p in $log_pids; do wait "$p" 2>/dev/null; done
  rm -f "$out/logs/"*.err 2>/dev/null
  find "$out" -type d -empty -delete 2>/dev/null

  # Hang summary in the job log, so the stuck frames are readable without downloading.
  for f in "$out"/hang/sample-*.txt "$out"/hang/at-failure/sample-*.txt; do
    [ -f "$f" ] || continue
    echo "::group::$(basename "$f") (call graph head)"
    awk '/^Call graph:/ { p = 1 } /^Total number in stack/ { p = 0 } p && n < 60 { print; n++ }' "$f"
    echo "::endgroup::"
  done
  for f in "$out"/hang/jvm-threads-*.txt "$out"/hang/at-failure/jvm-threads-*.txt; do
    [ -f "$f" ] || continue
    echo "::group::$(basename "$f") (Test worker threads)"
    awk '/^"/ { p = ($0 ~ /^"Test worker/) } p && n < 60 { print; n++ }' "$f"
    echo "::endgroup::"
  done
  [ -f "$out/hang/hang-errors.txt" ] && sed 's/^/hang note: /' "$out/hang/hang-errors.txt"

  # Total cap: drop the biggest log first, never the crash reports or stacks.
  n=$(du -sk "$out" | cut -f1)
  while [ "$n" -gt "$TOTAL_CAP_KB" ]; do
    big=$(find "$out/logs" -type f -print0 2>/dev/null | xargs -0 ls -S 2>/dev/null | head -1)
    [ -n "$big" ] || break
    echo "artifact over ${TOTAL_CAP_KB} KB: dropping $big"
    rm -f "$big"
    n=$(du -sk "$out" | cut -f1)
  done
  (cd "$out" && find . -type f -exec ls -l {} + | awk '{print $5, $9}') >"$out/MANIFEST.txt" 2>/dev/null
  echo "capture finished in $(($(date +%s) - t0)) s: $(find "$out" -type f | wc -l | tr -d ' ') files, $(du -sk "$out" | cut -f1) KB"
  return 0
}

case "${1:-}" in
  start) start "${2:?dir}" "${3:-0}" "${4:-0}" "${5:-none}" ;;
  ring) ring "${2:?dir}" "${3:?mode}" ;;
  watch) watch "${2:?dir}" "${3:?budget}" "${4:-0}" ;;
  collect) collect "${2:?dir}" "${3:?out}" "${4:-}" ;;
  summarize-ips) summarize_ips "${2:?file}" ;;
  *) echo "usage: $0 start|collect|watch|summarize-ips ..." >&2; exit 2 ;;
esac
