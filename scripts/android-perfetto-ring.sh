#!/usr/bin/env bash
#
# A Perfetto ring-buffer trace of the emulator while the instrumented tests run — kept in the
# device's memory, written out ONLY if the run fails or hangs. Sourced by android-emulator-tests.sh
# (functions only; nothing runs on source).
#
#   perfetto_ring_start   <api> <out-dir>   start the recorder (no-op below API 29 or unless enabled)
#   perfetto_ring_collect <api> <out-dir>   stop it, write the trace, pull + compress it (failure/hang)
#   perfetto_ring_discard <api>             stop it WITHOUT writing anything (green run)
#
# WHY: logcat says what a test logged; it cannot say which thread was descheduled, what the process
# was waiting on, or whether the emulator itself starved. The API-29 lane's ~1-in-120 flake and the
# install stalls were diagnosed from logcat plus ps; a scheduling timeline (sched_switch/wakeup per
# thread, with the app's own atrace slices) is the missing view. Read the result at ui.perfetto.dev
# or with trace_processor_shell (see the README written next to it).
#
# API 24 has NO Perfetto (it ships from Android 9/10; perfetto + traced are absent from the API 24
# image), so that lane keeps logcat only and this is a no-op there. Enabled per lane by
# PERFETTO_RING=1 so the recorder is never the only configuration the Android matrix has run.
#
# The ring lives in the device's tracing service: buffers are RING_BUFFER (oldest overwritten) and
# the trace file is produced only when the session ends gracefully. SIGTERM ends it gracefully
# (flush, write the file); SIGKILL ends it with no file at all, which is what a green run gets.
#
# Never fails the job: every command is bounded, every failure is reported and skipped.

PERFETTO_DEVICE_FILE="/data/misc/perfetto-traces/ci-ring.pftrace"
PERFETTO_CAP_BYTES="${PERFETTO_RING_CAP_BYTES:-20971520}"   # 20 MB compressed
PERFETTO_BUFFER_KB="${PERFETTO_RING_BUFFER_KB:-24576}"      # ~the last 30-60 s of a busy 4-core guest
PERFETTO_PID_FILE=""
PERFETTO_STATE="off"   # off | running | collected | discarded
# Test APKs of the modules with instrumented tests: the processes whose app slices (atrace) matter.
PERFETTO_APPS="${PERFETTO_RING_APPS:-com.ditchoom.socket.http3.test com.ditchoom.socket.quic.quiche.test com.ditchoom.socket.webtransport.test}"

_pf_adb() { "${ADB:-adb}" "$@"; }

# The TraceConfig, as text proto. Kept to scheduling, process stats and the atrace categories for
# connection state (am), network and system services (ss/network).
perfetto_ring_config() { # <api>
  local api="$1" app
  cat <<CFG
buffers { size_kb: ${PERFETTO_BUFFER_KB} fill_policy: RING_BUFFER }
buffers { size_kb: 2048 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      atrace_categories: "ss"
      atrace_categories: "am"
      atrace_categories: "network"
CFG
  for app in $PERFETTO_APPS; do echo "      atrace_apps: \"$app\""; done
  # Per-CPU kernel buffer: small. compact_sched (a fraction of the bytes per sched event) needs the
  # perfetto that ships with Android 13.
  echo "      buffer_size_kb: 2048"
  if [ "$api" -ge 33 ]; then echo "      compact_sched { enabled: true }"; fi
  cat <<CFG
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 5000 }
  }
}
duration_ms: 3600000
CFG
  # In-trace deflate of what is written (perfetto v40+, Android 15). Older builds reject the field
  # and refuse to start, so it is gated on the API; the pull is gzipped on the host either way.
  if [ "$api" -ge 35 ]; then echo "compression_type: COMPRESSION_TYPE_DEFLATE"; fi
}

perfetto_ring_start() { # <api> <out-dir>
  local api="$1" out="$2" pid
  PERFETTO_STATE="off"
  [ "${PERFETTO_RING:-0}" = "1" ] || { echo "perfetto ring: not enabled for this lane"; return 0; }
  if [ "$api" -lt 29 ]; then echo "perfetto ring: API $api has no Perfetto, logcat only"; return 0; fi
  mkdir -p "$out"
  PERFETTO_PID_FILE="$out/.perfetto-pid"
  perfetto_ring_config "$api" >"$out/perfetto-ring.cfg"
  _pf_adb shell "rm -f $PERFETTO_DEVICE_FILE" >/dev/null 2>&1 || true
  # --background prints the pid of the detached recorder and returns.
  pid=$(timeout 30 "${ADB:-adb}" shell "perfetto --background --txt -c - -o $PERFETTO_DEVICE_FILE" <"$out/perfetto-ring.cfg" 2>"$out/perfetto-start.log" | tr -d '\r' | grep -E '^[0-9]+$' | tail -1) || true
  if [ -z "$pid" ]; then
    echo "::warning::perfetto ring did not start on API $api (see $out/perfetto-start.log); logcat only"
    cat "$out/perfetto-start.log" 2>/dev/null | head -5
    return 0
  fi
  echo "$pid" >"$PERFETTO_PID_FILE"
  PERFETTO_STATE="running"
  echo "perfetto ring: recording (device pid $pid, ${PERFETTO_BUFFER_KB} KB ring), written only on failure"
}

perfetto_ring_collect() { # <api> <out-dir>
  local api="$1" out="$2" pid raw size gz i
  [ "$PERFETTO_STATE" = "running" ] || return 0
  pid=$(cat "$PERFETTO_PID_FILE" 2>/dev/null)
  # SIGTERM = a graceful stop: the service flushes and writes the file.
  timeout 20 "${ADB:-adb}" shell "kill -TERM $pid" >/dev/null 2>&1 || true
  for i in $(seq 1 20); do
    size=$(timeout 10 "${ADB:-adb}" shell "stat -c %s $PERFETTO_DEVICE_FILE 2>/dev/null || echo 0" 2>/dev/null | tr -d '\r\n ') || true
    # still being written while the size changes; the process is gone when the file is complete
    if [ "${size:-0}" -gt 0 ] && ! timeout 10 "${ADB:-adb}" shell "kill -0 $pid" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  PERFETTO_STATE="collected"
  raw="$out/perfetto-ring-api${api}.pftrace"
  if ! timeout 120 "${ADB:-adb}" pull "$PERFETTO_DEVICE_FILE" "$raw" >"$out/perfetto-pull.log" 2>&1 || [ ! -s "$raw" ]; then
    echo "::warning::perfetto ring: no trace could be pulled on API $api ($(head -c 200 "$out/perfetto-pull.log" 2>/dev/null))"
    rm -f "$raw"; return 0
  fi
  timeout 20 "${ADB:-adb}" shell "rm -f $PERFETTO_DEVICE_FILE" >/dev/null 2>&1 || true
  size=$(wc -c <"$raw" | tr -d ' ')
  gzip -9 -f "$raw" || { echo "::warning::perfetto ring: gzip failed"; return 0; }
  gz="$raw.gz"
  local gzsize; gzsize=$(wc -c <"$gz" | tr -d ' ')
  if [ "$gzsize" -gt "$PERFETTO_CAP_BYTES" ]; then
    # A proto trace cut mid-packet does not parse, so the cap drops the file whole and says so.
    echo "perfetto ring: TRUNCATED, dropped $(basename "$gz") ($gzsize bytes compressed > cap $PERFETTO_CAP_BYTES)"
    rm -f "$gz"; return 0
  fi
  cat >"$out/perfetto-ring-README.txt" <<TXT
Perfetto ring of the Android emulator (API ${api}) up to the moment the run failed or hung:
sched_switch / sched_wakeup per thread, process stats, atrace categories ss + am + network, and the
app slices of the test APKs. gunzip it, then open it at https://ui.perfetto.dev (the Process and
thread tracks show who was running and who was blocked), or query it:
  trace_processor_shell perfetto-ring-api${api}.pftrace
  > select t.name, count(*) from sched s join thread t using(utid) group by 1 order by 2 desc limit 20;
TXT
  echo "perfetto ring: collected $(basename "$gz"): ${size} bytes raw, ${gzsize} bytes compressed"
}

perfetto_ring_discard() { # <api>
  local pid
  [ "$PERFETTO_STATE" = "running" ] || return 0
  pid=$(cat "$PERFETTO_PID_FILE" 2>/dev/null)
  # SIGKILL: the session ends with no file written; a green run leaves nothing.
  timeout 20 "${ADB:-adb}" shell "kill -KILL $pid; rm -f $PERFETTO_DEVICE_FILE" >/dev/null 2>&1 || true
  PERFETTO_STATE="discarded"
  echo "perfetto ring: discarded (green run, nothing written)"
}
