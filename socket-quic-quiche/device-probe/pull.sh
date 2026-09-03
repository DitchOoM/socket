#!/usr/bin/env bash
# Pull the log (and qlogs if any) to ./logs/<utc-stamp>-<tag>.log
set -euo pipefail
. "$(dirname "$0")/common.sh"
TAG="${1:-walk}"; mkdir -p "$(dirname "$0")/logs"
out="$(dirname "$0")/logs/$(date -u +%Y%m%dT%H%M%SZ)-$TAG.log"
adbs pull "$DEVICE_LOG" "$out" >/dev/null && echo "pulled $(wc -l < "$out") lines -> $out"
