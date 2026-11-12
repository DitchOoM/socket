#!/bin/bash
# Records one run of the native QUIC perf workload under xctrace Time Profiler and prints the bucket shares.
#
#   record.sh <test.kexe> <rr|bulk> <seconds> <out-prefix>
#
# Also prints the load average before/after and process CPU is NOT measured here (xctrace perturbs it): use
# `/usr/bin/time -l <test.kexe> --ktest_filter=...` with QUIC_PERF set for CPU and datagram counts.
set -euo pipefail
[ "$#" -eq 4 ] || { echo "usage: $0 <test.kexe> <rr|bulk> <seconds> <out-prefix>" >&2; exit 2; }
bin="$1" mode="$2" secs="$3" out="$4"
here="$(cd "$(dirname "$0")" && pwd)"
echo "load before: $(uptime | sed 's/.*averages://')"
rm -rf "$out.trace"
QUIC_PERF="$mode" QUIC_PERF_SECONDS="$secs" xcrun xctrace record --template 'Time Profiler' \
  --time-limit "$((secs + 20))s" --output "$out.trace" --launch -- "$bin" --ktest_filter='*QuicNativePerfTrace*' | tail -3
xcrun xctrace export --input "$out.trace" --xpath '/trace-toc/run[1]/data/table[@schema="time-profile"]' > "$out.tp.xml"
python3 -I "$here/tp_export_to_pickle.py" "$out.tp.xml"
python3 -I "$here/buckets.py" "$out.tp.xml.pkl"
python3 -I "$here/inclusive.py" "$out.tp.xml.pkl"
echo "load after: $(uptime | sed 's/.*averages://')"
