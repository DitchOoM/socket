#!/usr/bin/env bash
# The Kotlin/Native interop client for native-pair.py: kn-client.sh <test.kexe> [<simulator udid>]
#
# Runs the platform's NativeQuicInteropClient (Apple or Linux) from a K/N test binary with the endpoint contract native-pair.py set in the
# environment (TESTCASE, REQUESTS, DOWNLOADS, LOGS, CLIENT_PARAMS). With a simulator udid it runs inside
# that booted iOS simulator, where only variables named SIMCTL_CHILD_<NAME> reach the process, so each
# is passed on under that name. The simulator shares the host's loopback and file system.
set -uo pipefail
kexe=$1
udid=${2:-}
filter="--ktest_filter=com.ditchoom.socket.quic.*QuicInteropClient.*"
export QUIC_INTEROP_CLIENT=1
if [ -z "$udid" ]; then
  exec "$kexe" "$filter"
fi
for name in QUIC_INTEROP_CLIENT TESTCASE REQUESTS DOWNLOADS LOGS CLIENT_PARAMS; do
  export "SIMCTL_CHILD_$name=${!name:-}"
done
exec xcrun simctl spawn "$udid" "$kexe" "$filter"
