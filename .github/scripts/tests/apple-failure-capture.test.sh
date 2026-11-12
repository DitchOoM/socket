#!/usr/bin/env bash
#
# Tests for apple-failure-capture.sh against REAL failures: a C program that segfaults (the crash
# reporter writes a real .ips), and one that hangs (a real `sample` of a live process). The
# platform-neutral half (the .ips parser, the marker window, hs_err collection, the watchdog's kill)
# also runs on the Linux runner that executes every .github/scripts test; the Darwin half skips
# itself there and says so. Optional simulator half: CAPTURE_TEST_SIMULATOR_UDID=<booted udid>.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../apple-failure-capture.sh"
pass=0
fail=0
ok() { echo "ok: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1"; [ -n "${2:-}" ] && printf '    %s\n' "$2"; fail=$((fail + 1)); }
expect() { # <name> <needle> <haystack>
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else bad "$1 — missing '$2'" "$(head -c 1500 <<<"$3")"; fi
}

work="$(mktemp -d)"
cleanup() {
  [ -n "${HANG_PID:-}" ] && kill "$HANG_PID" 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT
# Names unique to this run: the watchdog kills by exact name and the tests must never touch a real
# test.kexe or Gradle worker that happens to be running on the same machine.
uniq="cp$$"
export CAPTURE_NATIVE_NAME="$uniq"
export CAPTURE_JVM_PATTERN="no-such-jvm-worker-$$"
export CAPTURE_KILL_PATTERNS="$CAPTURE_JVM_PATTERN"
export CAPTURE_CRASH_WAIT_SECONDS=3
export CAPTURE_LOG_SHOW_SECONDS=20
export CAPTURE_MINUTE_SECONDS=1 # a "minute" of the watchdog budget is one second here
diag="$work/DiagnosticReports"
mkdir -p "$diag"
export CAPTURE_DIAG_DIRS="$diag"
real_home="$HOME"
export HOME="$work/home" # keeps ~/Library/Logs/CoreSimulator lookups inside the sandbox
mkdir -p "$HOME"

# ── 1. the .ips parser: exception, faulting thread, image names ──────────────────────────────────
cat >"$work/fixture.ips" <<'EOF'
{"app_name":"test.kexe","timestamp":"2026-11-11 10:00:00.00 +0000","bug_type":"309"}
{"procName":"test.kexe","pid":4242,"captureTime":"2026-11-11 10:00:00.0000 +0000","exception":{"type":"EXC_BAD_ACCESS","signal":"SIGSEGV","subtype":"KERN_INVALID_ADDRESS at 0x0000000000000010"},"termination":{"namespace":"SIGNAL","indicator":"Segmentation fault: 11"},"faultingThread":1,"usedImages":[{"name":"test.kexe"},{"name":"libsystem_platform.dylib"}],"threads":[{"frames":[]},{"queue":"com.apple.network.connections","frames":[{"imageIndex":1,"imageOffset":1000},{"imageIndex":0,"symbol":"kfun:com.ditchoom.socket.AppleSystemChainTrustTests#evaluate","symbolLocation":88}]}]}
EOF
out="$("$script" summarize-ips "$work/fixture.ips" 2>&1)"
expect "ips: signal and exception type" "EXC_BAD_ACCESS SIGSEGV" "$out"
expect "ips: the faulting thread is the one printed" "crashed thread 1" "$out"
expect "ips: frames carry image names and symbols" "test.kexe                    kfun:com.ditchoom.socket.AppleSystemChainTrustTests#evaluate+88" "$out"
expect "ips: an unsymbolicated frame prints its offset" "0x3e8" "$out"
printf 'not json at all\n' >"$work/garbage.ips"
out="$("$script" summarize-ips "$work/garbage.ips" 2>&1)"
expect "ips: garbage is reported, not fatal" "could not parse" "$out"

# ── 2. the marker window: a report older than the marker is NOT collected ────────────────────────
touch -t 202001010000 "$diag/old.ips"
cp "$work/fixture.ips" "$diag/old.ips"
touch -t 202001010000 "$diag/old.ips"
"$script" start "$work/cap1" 0 0 >/dev/null
sleep 1
cp "$work/fixture.ips" "$diag/new.ips"
mkdir -p "$work/ws"
printf '# SIGSEGV (0xb) at pc=0x0000, pid=7, tid=8\n# Problematic frame:\n# C  [libquiche.so+0x10]\n' >"$work/ws/hs_err_pid7.log"
out="$(GITHUB_WORKSPACE="$work/ws" "$script" collect "$work/cap1" "$work/out1" 2>&1)"
[ -f "$work/out1/crash-reports/new.ips" ] && ok "collect: a report written after the marker is kept" || bad "collect: new.ips missing" "$out"
[ ! -e "$work/out1/crash-reports/old.ips" ] && ok "collect: a report older than the marker is left out" || bad "collect: old.ips leaked into the artifact"
expect "collect: the crashed thread is in the job log" "crashed thread 1" "$out"
[ -f "$work/out1/jvm/hs_err_pid7.log" ] && ok "collect: hs_err from the workspace is kept" || bad "collect: hs_err missing" "$out"
expect "collect: hs_err summary names the problematic frame" "Problematic frame" "$out"
[ -s "$work/out1/network-state.txt" ] && ok "collect: network state written" || bad "collect: no network-state.txt"
[ -s "$work/out1/MANIFEST.txt" ] && ok "collect: manifest written" || bad "collect: no manifest"

# A clean window (nothing crashed) says so and still succeeds.
"$script" start "$work/cap2" 0 0 >/dev/null
out="$(GITHUB_WORKSPACE="$work/empty" "$script" collect "$work/cap2" "$work/out2" 2>&1)"
rc=$?
expect "collect: no crash report is reported as such" "no crash report was written" "$out"
[ "$rc" -eq 0 ] && ok "collect: exit 0 when there is nothing to collect" || bad "collect: exit $rc"

# ── 3. the size cap drops logs, never crash reports ──────────────────────────────────────────────
"$script" start "$work/cap3" 0 0 >/dev/null
sleep 1
cp "$work/fixture.ips" "$diag/new3.ips"
mkdir -p "$work/out3/logs"
head -c 3000000 /dev/urandom >"$work/out3/logs/big.bin"
out="$(CAPTURE_TOTAL_CAP_KB=500 GITHUB_WORKSPACE="$work/empty" "$script" collect "$work/cap3" "$work/out3" 2>&1)"
[ ! -e "$work/out3/logs/big.bin" ] && [ -f "$work/out3/crash-reports/new3.ips" ] && ok "cap: over the total cap the biggest log goes, the crash report stays" || bad "cap: wrong file dropped" "$out"

# ── 4. the hang watchdog: stacks first, then (kill=1) the process ends ───────────────────────────
if command -v cc >/dev/null 2>&1; then
  mkdir -p "$work/bin"
  printf '#include <unistd.h>\nvoid spin_here(void){sleep(300);}\nint main(void){spin_here();return 0;}\n' >"$work/hang.c"
  cc -g -o "$work/bin/$uniq" "$work/hang.c" 2>/dev/null
fi
if [ -x "$work/bin/$uniq" ]; then
  "$work/bin/$uniq" &
  HANG_PID=$!
  sleep 1
  "$script" start "$work/cap4" 3 1 >/dev/null
  # budget = 3 s; allow the 10 s poll plus the stack dump
  for _ in $(seq 1 40); do [ -e "$work/cap4/watchdog-fired" ] && break; sleep 1; done
  [ -e "$work/cap4/watchdog-fired" ] && ok "watchdog: fired at its budget while the process was alive" || bad "watchdog: never fired" "$(cat "$work/cap4/watchdog.log" 2>/dev/null)"
  sleep 1
  if kill -0 "$HANG_PID" 2>/dev/null; then bad "watchdog: kill=1 left the process running"; else ok "watchdog: kill=1 ended the hung process after the dump"; fi
  HANG_PID=""
  out="$("$script" collect "$work/cap4" "$work/out4" 2>&1)"
  if [ "$(uname -s)" = Darwin ]; then
    sample_file="$(ls "$work"/out4/hang/sample-"$uniq"-*.txt 2>/dev/null | head -1)"
    [ -n "$sample_file" ] && ok "hang: a sample of the live process was captured" || bad "hang: no sample file" "$out"
    expect "hang: the stuck frame is named in the sample" "spin_here" "$(cat "$sample_file" 2>/dev/null)"
    expect "hang: the call graph is printed in the job log" "spin_here" "$out"
  else
    echo "skip: sample/spindump halves need macOS (uname: $(uname -s))"
  fi
  # kill=0: the watchdog must dump and leave the process alone (the JVM lanes' step timeout kills).
  "$work/bin/$uniq" &
  HANG_PID=$!
  sleep 1
  "$script" start "$work/cap5" 2 0 >/dev/null
  for _ in $(seq 1 40); do [ -e "$work/cap5/watchdog-fired" ] && break; sleep 1; done
  if [ -e "$work/cap5/watchdog-fired" ] && kill -0 "$HANG_PID" 2>/dev/null; then ok "watchdog: kill=0 dumps and leaves the process alone"; else bad "watchdog: kill=0 misbehaved"; fi
  kill "$HANG_PID" 2>/dev/null
  wait "$HANG_PID" 2>/dev/null
  HANG_PID=""
  # No live process at the budget: nothing to dump, nothing to kill.
  "$script" start "$work/cap6" 1 1 >/dev/null
  sleep 14
  [ ! -e "$work/cap6/watchdog-fired" ] && grep -q "no test process alive" "$work/cap6/watchdog.log" && ok "watchdog: idle at the budget does nothing" || bad "watchdog: fired with nothing to dump" "$(cat "$work/cap6/watchdog.log" 2>/dev/null)"
else
  echo "skip: no C compiler for the hang half"
fi

# ── 5. a REAL crash: the segfault's own .ips from the system crash reporter (macOS) ──────────────
if [ "$(uname -s)" = Darwin ] && command -v cc >/dev/null 2>&1; then
  printf 'int main(void){volatile int *p = 0; *p = 1; return 0;}\n' >"$work/crash.c"
  cc -g -o "$work/crash" "$work/crash.c"
  export CAPTURE_DIAG_DIRS="$real_home/Library/Logs/DiagnosticReports:/Library/Logs/DiagnosticReports"
  "$script" start "$work/cap7" 0 0 >/dev/null
  sleep 1
  "$work/crash" 2>/dev/null
  out="$("$script" collect "$work/cap7" "$work/out7" 2>&1)"
  expect "real crash: the segfault is found and its signal printed" "EXC_BAD_ACCESS SIGSEGV" "$out"
  expect "real crash: the faulting frame is main in the crashed binary" "crash                        main" "$out"
  ls "$work"/out7/crash-reports/crash-*.ips >/dev/null 2>&1 && ok "real crash: the .ips is in the artifact" || bad "real crash: no .ips copied"
else
  echo "skip: the real-crash half needs macOS and a C compiler"
fi

# ── 6. a booted simulator, when the caller provides one ──────────────────────────────────────────
if [ -n "${CAPTURE_TEST_SIMULATOR_UDID:-}" ] && [ "$(uname -s)" = Darwin ]; then
  udid="$CAPTURE_TEST_SIMULATOR_UDID"
  export CAPTURE_DIAG_DIRS="$real_home/Library/Logs/DiagnosticReports"
  export HOME="$real_home"
  "$script" start "$work/cap8" 0 0 >/dev/null
  sleep 1
  sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  xcrun --sdk iphonesimulator cc -arch arm64 -isysroot "$sdk" -mios-simulator-version-min=16.0 -g -o "$work/crash-sim" "$work/crash.c"
  xcrun simctl spawn "$udid" "$work/crash-sim" >/dev/null 2>&1
  out="$("$script" collect "$work/cap8" "$work/out8" "$udid" 2>&1)"
  expect "simulator: a crash inside the simulator lands in the HOST's DiagnosticReports" "crashed thread 0" "$out"
  [ -s "$work/out8/logs/unified-log-simulator.txt.gz" ] && ok "simulator: 'simctl spawn log show' returned the simulator's own log" || bad "simulator: no simulator log"
else
  echo "skip: no CAPTURE_TEST_SIMULATOR_UDID (simulator half)"
fi

# ── 7. the timeline ring: `sample` snapshots and Time Profiler recordings of a live test binary ──
# (macOS only: `sample` and `xctrace` are Apple tools). The binary burns CPU in a named frame so a
# snapshot has something to name.
if [ "$(uname -s)" = Darwin ] && command -v cc >/dev/null 2>&1; then
  printf '#include <unistd.h>\nvolatile unsigned long x;\nvoid burn_in_the_ring_test(void){for(;;)x++;}\nint main(void){burn_in_the_ring_test();return 0;}\n' >"$work/burn.c"
  cc -g -O0 -o "$work/bin/$uniq" "$work/burn.c" 2>/dev/null
  export CAPTURE_RING_SAMPLE_INTERVAL=1 CAPTURE_RING_SAMPLE_KEEP=3
  "$work/bin/$uniq" &
  HANG_PID=$!
  "$script" start "$work/cap9" 0 0 sample >/dev/null
  sleep 14
  out="$("$script" collect "$work/cap9" "$work/out9" 2>&1)"
  n=$(ls "$work"/out9/ring/pid-*-sample-*.txt.gz 2>/dev/null | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] && [ "$n" -le 3 ] && ok "ring/sample: only the newest $n of many snapshots are kept (ring of 3)" || bad "ring/sample: $n snapshots" "$out"
  expect "ring/sample: a snapshot names the burning frame" "burn_in_the_ring_test" "$(gunzip -c "$(ls "$work"/out9/ring/pid-*-sample-*.txt.gz | head -1)" 2>/dev/null)"
  expect "ring/sample: the log says how much was kept" "ring: $n file(s)" "$out"
  [ -s "$work/out9/ring/README.txt" ] && ok "ring/sample: README written" || bad "ring/sample: no README"
  kill "$HANG_PID" 2>/dev/null; wait "$HANG_PID" 2>/dev/null; HANG_PID=""
  # cap: nothing fits under 1 byte, and the log says so
  "$work/bin/$uniq" &
  HANG_PID=$!
  "$script" start "$work/cap10" 0 0 sample >/dev/null
  sleep 8
  out="$(CAPTURE_RING_CAP_BYTES=1 "$script" collect "$work/cap10" "$work/out10" 2>&1)"
  expect "ring/cap: dropped files are named in the log" "TRUNCATED" "$out"
  kill "$HANG_PID" 2>/dev/null; wait "$HANG_PID" 2>/dev/null; HANG_PID=""
  # no ring requested: nothing recorded, nothing uploaded
  "$script" start "$work/cap11" 0 0 >/dev/null
  out="$("$script" collect "$work/cap11" "$work/out11" 2>&1)"
  [ ! -d "$work/out11/ring" ] && ok "ring/none: a lane without a ring uploads no ring" || bad "ring/none: a ring directory appeared"
  # Time Profiler, when this machine lets xctrace attach (it may need a developer-tools authorisation)
  "$work/bin/$uniq" &
  HANG_PID=$!
  CAPTURE_RING_XCTRACE_WINDOW=6 "$script" start "$work/cap12" 0 0 xctrace >/dev/null
  sleep 16
  out="$(CAPTURE_RING_XCTRACE_WINDOW=6 "$script" collect "$work/cap12" "$work/out12" 2>&1)"
  if ls "$work"/out12/ring/ 2>/dev/null | grep -q 'xctrace-.*\.tar\.\|xctrace-.*\.tgz'; then
    ok "ring/xctrace: a Time Profiler recording of the live process is compressed into the artifact"
    t="$(ls "$work"/out12/ring/pid-*-xctrace-*.tar.* | head -1)"
    mkdir -p "$work/unpack"
    case "$t" in *.zst) zstd -dc "$t" | tar -xf - -C "$work/unpack" ;; *) tar -xzf "$t" -C "$work/unpack" ;; esac
    tr_dir="$(ls -d "$work"/unpack/*.trace | head -1)"
    expect "ring/xctrace: the recording shows the burning frame" "burn_in_the_ring_test" "$(xcrun xctrace export --input "$tr_dir" --xpath '/trace-toc/run[@number="1"]/data/table[@schema="time-profile"]' 2>/dev/null | grep -o 'burn_in_the_ring_test' | head -1)"
  else
    echo "skip: xctrace could not record on this machine: $(cat "$work"/cap12/ring/pid-*/*.log 2>/dev/null | tail -2 | tr '\n' ' ')"
  fi
  kill "$HANG_PID" 2>/dev/null; wait "$HANG_PID" 2>/dev/null; HANG_PID=""
else
  echo "skip: the ring half needs macOS and a C compiler"
fi

echo "---"
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
