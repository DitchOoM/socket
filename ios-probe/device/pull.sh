#!/usr/bin/env bash
# Copy the log out of the app's Documents container to ./logs/<utc-stamp>-<tag>.log
set -euo pipefail
. "$(dirname "$0")/common.sh"
TAG="${1:-walk}"; mkdir -p "$(dirname "$0")/logs"
out="$(dirname "$0")/logs/$(date -u +%Y%m%dT%H%M%SZ)-$TAG.log"
xcrun devicectl device copy from --device "$DEVICE" --domain-type appDataContainer --domain-identifier "$BUNDLE" --source Documents/quic-handoff-probe.log --destination "$out" >/dev/null
echo "pulled $(wc -l < "$out") lines -> $out"
