#!/usr/bin/env bash
#
# Tests for jfr-ring.sh against a REAL JVM: a stand-in "Gradle Test Executor" that records into a
# JFR ring with the repo's stall-ring.jfc and parks a thread forever. Covers the dump, the cascade
# and per-pid caps, the owner filter, the idle watchdog, and collect's compression and 20 MB cap.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../jfr-ring.sh"
jfc="$here/../../jfr/stall-ring.jfc"
tmp="$(mktemp -d)"
worker_pid=""
trap '[ -n "$worker_pid" ] && kill "$worker_pid" 2>/dev/null; rm -rf "$tmp"' EXIT
pass=0
fail=0
check() { if [ "$2" -eq 0 ]; then echo "ok: $1"; pass=$((pass + 1)); else echo "FAIL: $1"; fail=$((fail + 1)); fi; }

if ! command -v javac >/dev/null 2>&1 || ! command -v jcmd >/dev/null 2>&1 || ! command -v jfr >/dev/null 2>&1; then
  echo "skip: needs a JDK (javac, jcmd, jfr) on PATH"; exit 0
fi

mkdir -p "$tmp/src" "$tmp/out" "$tmp/ring" "$tmp/root/build/test-results/jvmTest/binary"
cat >"$tmp/src/FakeWorker.java" <<'JAVA'
public class FakeWorker {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> java.util.concurrent.locks.LockSupport.park(new Object()), "parked-forever");
        t.start();
        t.join();
    }
}
JAVA
javac -d "$tmp/out" "$tmp/src/FakeWorker.java" || { echo "FAIL: javac"; exit 1; }
# the real jfc, with the 10 s thread dump sped up so the test does not wait 10 s
sed 's|<setting name="period">10 s</setting>|<setting name="period">1 s</setting>|' "$jfc" >"$tmp/fast.jfc"

java "-XX:StartFlightRecording=name=ci-ring,settings=$tmp/fast.jfc,disk=true,maxage=60s,maxsize=20m" \
  -cp "$tmp/out" FakeWorker 'Gradle Test Executor 7' >/dev/null 2>&1 &
worker_pid=$!
for _ in $(seq 1 40); do jcmd -l 2>/dev/null | grep -q "^$worker_pid .*Gradle Test Executor 7" && break; sleep 0.5; done
sleep 3  # let at least one periodic thread dump land in the ring

export JFR_RING_WORKER_PATTERN="Gradle Test Executor 7" # never touch another build's workers on a dev machine

# --- dump ---------------------------------------------------------------------------------------
bash "$script" dump "$tmp/ring" "failed: Foo.bar" >"$tmp/log" 2>&1
f="$(ls "$tmp/ring"/worker-"$worker_pid"-*.jfr 2>/dev/null | head -1)"
[ -n "$f" ] && [ -s "$f" ]; check "dump writes a recording of the live worker" $?
jfr print --events jdk.ThreadDump "$f" 2>/dev/null | grep -q 'parked-forever'; check "the recording names the parked thread (jdk.ThreadDump)" $?
grep -q 'failed: Foo.bar' "$f.txt"; check "the reason is kept next to the recording" $?
ls "$tmp/ring"/*.threads.txt >/dev/null 2>&1 && grep -q 'parked-forever' "$tmp/ring"/*.threads.txt; check "a plain thread dump is kept too" $?

bash "$script" dump "$tmp/ring" "second failure" >"$tmp/log" 2>&1
[ "$(ls "$tmp/ring"/worker-*.jfr | wc -l | tr -d ' ')" = 1 ] && grep -q 'same cascade' "$tmp/log"; check "a failure right after another shares its dump" $?

JFR_RING_MIN_GAP_SECONDS=0 JFR_RING_MAX_DUMPS_PER_PID=2 bash "$script" dump "$tmp/ring" "third" >/dev/null 2>&1
JFR_RING_MIN_GAP_SECONDS=0 JFR_RING_MAX_DUMPS_PER_PID=2 bash "$script" dump "$tmp/ring" "fourth" >"$tmp/log" 2>&1
[ "$(ls "$tmp/ring"/worker-*.jfr | wc -l | tr -d ' ')" = 2 ] && grep -q 'already, skipping' "$tmp/log"; check "dumps per worker are capped" $?

JFR_RING_OWNER=1 bash "$script" dump "$tmp/ring-owner" "x" >"$tmp/log" 2>&1
[ -z "$(ls "$tmp/ring-owner"/*.jfr 2>/dev/null)" ] && grep -q 'no live test worker' "$tmp/log"; check "a worker the owner did not fork is left alone" $?

# --- collect ------------------------------------------------------------------------------------
bash "$script" collect "$tmp/ring" "$tmp/up" >"$tmp/log" 2>&1
ls "$tmp/up"/*.jfr.gz >/dev/null 2>&1 && gunzip -c "$(ls "$tmp/up"/*.jfr.gz | head -1)" | cmp -s - "$f" || [ "$(ls "$tmp/up"/*.jfr.gz | wc -l | tr -d ' ')" -ge 1 ]; check "collect gzips the recordings" $?
[ -s "$tmp/up/README.txt" ] && grep -q 'jfr print' "$tmp/up/README.txt"; check "collect says how to read them" $?

JFR_RING_CAP_BYTES=1 bash "$script" collect "$tmp/ring" "$tmp/up-capped" >"$tmp/log" 2>&1
grep -q 'TRUNCATED' "$tmp/log" && [ -z "$(ls "$tmp/up-capped"/*.gz 2>/dev/null)" ]; check "the cap drops recordings and the log says so" $?

bash "$script" collect "$tmp/never-written" "$tmp/up-none" >"$tmp/log" 2>&1
[ ! -d "$tmp/up-none" ] || [ -z "$(ls -A "$tmp/up-none")" ]; check "nothing recorded, nothing to upload" $?

# --- hang watchdog ------------------------------------------------------------------------------
rm -rf "$tmp/ring-hang"
JFR_RING_ROOT="$tmp/root" JFR_RING_POLL_SECONDS=1 bash "$script" watch "$tmp/ring-hang" 4 >"$tmp/watch.log" 2>&1 &
wd=$!
sleep 2; touch "$tmp/root/build/test-results/jvmTest/binary/results.bin"; sleep 1
[ -z "$(ls "$tmp/ring-hang"/*.jfr 2>/dev/null)" ]; check "test progress keeps the watchdog quiet" $?
# The reason file is written after the recording is complete, so it is the one whose arrival says the dump is done.
for _ in $(seq 1 30); do ls "$tmp/ring-hang"/*.jfr.txt >/dev/null 2>&1 && break; sleep 1; done
ls "$tmp/ring-hang"/worker-"$worker_pid"-*.jfr >/dev/null 2>&1; check "a live worker with no test progress is dumped" $?
grep -q 'hang' "$tmp/ring-hang"/*.jfr.txt 2>/dev/null; check "the hang reason is recorded" $?
touch "$tmp/ring-hang/.stop"; sleep 2; kill "$wd" 2>/dev/null

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
