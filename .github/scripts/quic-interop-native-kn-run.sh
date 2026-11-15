#!/usr/bin/env bash
# Run the Kotlin/Native lane's pairs (quic-interop-native.yaml): our K/N client, on macOS arm64 and inside a
# booted iOS simulator, against quic-go's server, plain and migrating actively. Needs TESTS, the linked test
# binaries, the peers under $RUNNER_TEMP/peers and IOS_SIMULATOR_BOOTED_UDID.
set -uo pipefail
root=$(pwd)
results="$root/interop-results"
mkdir -p "$results"
peers="$RUNNER_TEMP/peers"
pair="$root/test-harness/quic-interop/native-pair.py"
client="$root/test-harness/quic-interop/kn-client.sh"
macos="$root/socket-quic-quiche/build/bin/macosArm64/debugTest/test.kexe"
sim="$root/socket-quic-quiche/build/bin/iosSimulatorArm64/debugTest/test.kexe"

for lane in macos-arm64-kn ios-simulator-arm64-kn; do
  case "$lane" in
    macos-arm64-kn) cmd="$client $macos" ;;
    ios-simulator-arm64-kn) cmd="$client $sim $IOS_SIMULATOR_BOOTED_UDID" ;;
  esac
  python3 "$pair" --direction "$lane-our-client" --peer quic-go --tests "$TESTS" \
    --server "$peers/qgo-server" --client "$cmd" --results "$results"
  python3 "$pair" --direction "$lane-our-client-migrating" --peer quic-go --tests transfer \
    --server "$peers/qgo-server" --client "$cmd" --results "$results"
done

{
  echo "### Kotlin/Native"
  echo '```'
  cat "$results"/*.reasons.txt 2>/dev/null | tail -80
  grep -h MIGRATION "$results"/*.summary 2>/dev/null
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
ls -la "$results"
