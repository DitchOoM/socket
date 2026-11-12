#!/bin/sh
# One timed block on a shared machine: waits (up to 5 minutes) for the 1-minute load to fall under <max-load>,
# takes ~/socket-bench.lock (released by the trap), runs every stack plus this library's JVM build.
#   timed-block.sh <max-load> <java 25> <out-prefix>      (run from the repository root)
set -u
max="$1" java="$2" out="$3"
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
case "$(uname)" in Darwin) kexe="$repo/socket-quic-quiche/build/bin/macosArm64/releaseTest/test.kexe" ;;
  *) kexe="$repo/socket-quic-quiche/build/bin/linuxX64/releaseTest/test.kexe" ;; esac
load() { uptime | sed 's/.*averages*: //' | awk '{gsub(",","",$1); print $1}'; }
waited=0
while [ "$(echo "$(load) > $max" | bc)" = 1 ]; do
  [ "$waited" -ge 300 ] && { echo "LOAD-NOT-SETTLED $(uptime)"; exit 3; }
  sleep 10; waited=$((waited + 10))
done
mkdir "$HOME/socket-bench.lock" || { echo "LOCK-HELD"; exit 4; }
trap 'rmdir "$HOME/socket-bench.lock"' EXIT INT TERM
echo "START $(date -u +%H:%M:%S) $(uptime)"
python3 -I "$here/run.py" --kexe "$kexe" --certs "$HOME/socket-bench-native/certs" --rounds 3 --seconds 8 --warmup 2 \
  --jmh-jar "$repo/benchmarks/jvm/build/benchmarks/main/jars/benchmark-jvm-main-jmh-JMH.jar" --java "$java" \
  --certs-repo "$repo/socket-quic-quiche/testcerts" --json "$out.json" > "$out.md" 2> "$out.log"
echo "END $(date -u +%H:%M:%S) $(uptime)"
