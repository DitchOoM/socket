#!/usr/bin/env bash
# Run one native lane's pairs (quic-interop-native.yaml): our client against each peer's server, our client
# migrating actively against it, and each peer's client against our server. Needs LANE and TESTS, the endpoint
# jar at test-harness/quic-interop/quic-interop.jar and the peers under $RUNNER_TEMP/peers.
set -uo pipefail
root=$(pwd)
py=python3
exe=""
peers="$RUNNER_TEMP/peers"
case "$(uname -s)" in
  # The pairs are started by Python, not by this shell, so they need Windows paths, not MSYS ones.
  MINGW* | MSYS* | CYGWIN*)
    exe=.exe
    py=python
    root=$(cygpath -m "$root")
    peers=$(cygpath -m "$peers")
    ;;
esac
results="$root/interop-results"
mkdir -p "$results"
# Windows ships only the JNI shim (quiche_jni.dll, quiche linked in), not a libquiche the FFM loader can open,
# so the multi-release FFM classes are switched off there and the jar falls back to JNI, as the Windows JVM
# test lane does.
jvm_flags="--enable-native-access=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
[ -n "$exe" ] && jvm_flags="$jvm_flags -Djdk.util.jar.enableMultiRelease=false"
ours="java $jvm_flags -jar $root/test-harness/quic-interop/quic-interop.jar"
pair="$root/test-harness/quic-interop/native-pair.py"

"$py" "$pair" --direction "$LANE-our-client" --peer quic-go --tests "$TESTS" \
  --server "$peers/qgo-server$exe" --client "$ours" --results "$results"
"$py" "$pair" --direction "$LANE-our-client-migrating" --peer quic-go --tests transfer \
  --server "$peers/qgo-server$exe" --client "$ours" --results "$results"
"$py" "$pair" --direction "$LANE-our-server" --peer quic-go --tests "$TESTS" \
  --server "$ours" --client "$peers/qgo-client$exe" --client-urls-as-args --results "$results"

{
  echo "### $LANE"
  echo '```'
  cat "$results"/*.reasons.txt 2>/dev/null | tail -80
  grep -h MIGRATION "$results"/*.summary 2>/dev/null
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
ls -la "$results"
