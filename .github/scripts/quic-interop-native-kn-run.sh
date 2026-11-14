#!/usr/bin/env bash
# Run the Kotlin/Native lanes' pairs (quic-interop-native.yaml): our K/N client against quic-go's server, plain
# and migrating actively. Each argument is one lane, <lane>=<test.kexe>[@<booted simulator udid>], for example
#   macos-arm64-kn=socket-quic-quiche/build/bin/macosArm64/debugTest/test.kexe
#   ios-simulator-arm64-kn=socket-quic-quiche/build/bin/iosSimulatorArm64/debugTest/test.kexe@$UDID
# Needs TESTS and the peers under $RUNNER_TEMP/peers.
set -uo pipefail
[ "$#" -gt 0 ] || { echo "::error::usage: $0 <lane>=<test.kexe>[@<udid>] ..."; exit 2; }
root=$(pwd)
results="$root/interop-results"
mkdir -p "$results"
peers="$RUNNER_TEMP/peers"
pair="$root/test-harness/quic-interop/native-pair.py"
client="$root/test-harness/quic-interop/kn-client.sh"

for spec in "$@"; do
  lane=${spec%%=*}
  target=${spec#*=}
  kexe=${target%%@*}
  udid=""
  [ "$kexe" != "$target" ] && udid=${target#*@}
  case "$kexe" in /*) ;; *) kexe="$root/$kexe" ;; esac
  [ -x "$kexe" ] || chmod +x "$kexe" || { echo "::error::$lane: no test binary at $kexe"; exit 1; }
  cmd="$client $kexe${udid:+ $udid}"
  python3 "$pair" --direction "$lane-our-client" --peer quic-go --tests "$TESTS" \
    --server "$peers/qgo-server" --client "$cmd" --results "$results"
  python3 "$pair" --direction "$lane-our-client-migrating" --peer quic-go --tests transfer \
    --server "$peers/qgo-server" --client "$cmd" --results "$results"
done

{
  echo "### Kotlin/Native: ${*%%=*}"
  echo '```'
  cat "$results"/*.reasons.txt 2>/dev/null | tail -80
  grep -h MIGRATION "$results"/*.summary 2>/dev/null
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
ls -la "$results"
