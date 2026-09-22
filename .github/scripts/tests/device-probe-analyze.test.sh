#!/usr/bin/env bash
#
# The walk analyzer's own tests: lane demultiplexing on a synthetic two-lane log, and a real one-lane
# log analysed byte-for-byte as it was before lanes existed.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
python3 -m unittest discover -s "$root/socket-quic-quiche/device-probe" -p 'test_*.py' -v
