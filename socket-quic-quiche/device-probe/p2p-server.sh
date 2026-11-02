#!/usr/bin/env bash
# The phone as the QUIC echo server for a direct phone-to-phone walk: `./p2p-server.sh [minutes] [port]`.
# Starts P2pEchoServerProbe and prints its P2P-READY line (bound port, every reachable address); hand
# those addresses to the client, e.g. on the iPhone `WALK_AUTOSTART=1 ../../ios-probe/device/launch.sh
# "<v4>,<v6>"` with SERVER_PORT=<port>. ⚠️ Same app process as the walk probe: this stops a walk
# running on this phone; `./start.sh resume` brings it back afterwards.
set -euo pipefail
. "$(dirname "$0")/common.sh"
MIN="${1:-30}"; PORT="${2:-44433}"
adbs logcat -c
adbs shell "nohup am instrument -w -e class com.ditchoom.socket.quic.P2pEchoServerProbe -e p2pPort $PORT -e p2pMinutes $MIN $RUNNER > /dev/null 2>&1 &"
for _ in $(seq 1 30); do
  line=$(adbs logcat -d -s P2pEchoServer:I | grep -m1 'P2P-READY' || true)
  [ -n "$line" ] && { echo "${line#*: }"; exit 0; }
  sleep 1
done
echo "no P2P-READY within 30 s:"; adbs logcat -d | grep -iE 'P2pEchoServer|AndroidRuntime|quic' | tail -20; exit 1
