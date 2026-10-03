#!/bin/bash
# quic-interop-runner entrypoint. The runner sets ROLE, TESTCASE, REQUESTS, SSLKEYLOGFILE and QLOGDIR.
set -u

# Route through the simulator (provided by the endpoint base image).
/setup.sh

ldd --version 2>&1 | head -1

# The library writes one key log per connection into QUIC_KEYLOG_DIR; the runner reads one file.
KEYDIR=$(mktemp -d)
export QUIC_KEYLOG_DIR="$KEYDIR"
collect_keys() {
  if [ -n "${SSLKEYLOGFILE:-}" ]; then
    cat "$KEYDIR"/*.keys > "$SSLKEYLOGFILE" 2>/dev/null || true
  fi
}
if [ -n "${QLOGDIR:-}" ]; then
  export QUIC_QLOG_DIR="$QLOGDIR"
fi

JAVA=(java --enable-native-access=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -jar /quic-interop.jar)

if [ "$ROLE" = "client" ]; then
  # Wait for the simulator to start up.
  /wait-for-it.sh sim:57832 -s -t 30
  "${JAVA[@]}"
  rc=$?
  collect_keys
  exit $rc
elif [ "$ROLE" = "server" ]; then
  # The runner stops the server with SIGTERM; flush the key logs on the way out, and periodically in
  # case the stop is a SIGKILL.
  ( while sleep 1; do collect_keys; done ) &
  "${JAVA[@]}" &
  pid=$!
  trap 'kill -TERM $pid 2>/dev/null; wait $pid; collect_keys; exit 0' TERM INT
  wait $pid
  rc=$?
  collect_keys
  exit $rc
else
  echo "unknown ROLE '$ROLE'"
  exit 1
fi
