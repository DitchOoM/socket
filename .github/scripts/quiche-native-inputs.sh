#!/usr/bin/env bash
# Prints one digest of everything that decides what a quiche native binary contains. Every CI cache of a
# quiche native (Linux, Android, Apple) keys on it, so the key moves when an input moves and at no other
# time. The inputs:
#   * the quiche, quicheSha256 and quicheBoringCrate pins in gradle/libs.versions.toml (not the rest of
#     the catalog, which changes with every dependency bump)
#   * the `region quiche-native-inputs` regions of socket-quic-quiche/build.gradle.kts: the quiche source
#     patches and their wiring, the cargo / NDK / clang invocations, the JNI shim build, the BoringSSL
#     bundle pin. The Gradle build embeds a digest of the same regions in each `.built-` marker, so a key
#     hit always carries a marker that matches.
#   * socket-quic-quiche/src/jni/quiche_jni.c and libs/quiche/include/quiche.h (the shim's source and the
#     header it compiles against)
#   * the boringssl provision plugin version in settings.gradle.kts
#   * with --rust, the rustc version: the repository does not pin a Rust toolchain (the workflows take
#     `stable`), so a compiler update produces new bytes from the same sources.
# A stale hit is the failure this guards against, so the script fails rather than hash less than it should:
# a missing input, or build.gradle.kts without exactly two balanced regions, exits non-zero.
#
# Usage: quiche-native-inputs.sh [--rust]
# Prints the digest on stdout, and `digest=<digest>` to $GITHUB_OUTPUT when that is set.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${QUICHE_NATIVE_INPUTS_ROOT:-$root}"

with_rust=false
for arg in "$@"; do
  case "$arg" in
    --rust) with_rust=true ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

build=socket-quic-quiche/build.gradle.kts
for f in gradle/libs.versions.toml "$build" socket-quic-quiche/src/jni/quiche_jni.c \
  socket-quic-quiche/libs/quiche/include/quiche.h settings.gradle.kts; do
  [ -f "$f" ] || { echo "quiche-native-inputs: missing input $f" >&2; exit 1; }
done

regions="$(awk '
  { t = $0; gsub(/^[ \t]+|[ \t]+$/, "", t) }
  t == "// region quiche-native-inputs"    { if (r) bad = 1; r = 1; n++; next }
  t == "// endregion quiche-native-inputs" { if (!r) bad = 1; r = 0; next }
  r { print }
  END { if (bad || r || n != 2) { print "quiche-native-inputs: " FILENAME " needs exactly 2 balanced quiche-native-inputs regions, found " n+0 > "/dev/stderr"; exit 1 } }
' "$build")"

pins="$(grep -E '^(quiche|quicheSha256|quicheBoringCrate)[[:space:]]*=' gradle/libs.versions.toml)"
[ "$(printf '%s\n' "$pins" | wc -l | tr -d ' ')" = 3 ] || { echo "quiche-native-inputs: expected 3 quiche pins in gradle/libs.versions.toml" >&2; exit 1; }
plugin="$(grep -F 'boringsslPluginVersion' settings.gradle.kts)"
[ -n "$plugin" ] || { echo "quiche-native-inputs: no boringsslPluginVersion in settings.gradle.kts" >&2; exit 1; }
rust=""
if [ "$with_rust" = true ]; then
  rust="$(rustc --version)"
fi

sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; }
digest="$(
  {
    printf 'pins\n%s\n' "$pins"
    printf 'plugin\n%s\n' "$plugin"
    printf 'regions\n%s\n' "$regions"
    printf 'rust\n%s\n' "$rust"
    for f in socket-quic-quiche/src/jni/quiche_jni.c socket-quic-quiche/libs/quiche/include/quiche.h; do
      printf 'file %s\n' "$f"
      cat "$f"
    done
  } | sha | cut -c1-16
)"
echo "$digest"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "digest=$digest" >> "$GITHUB_OUTPUT"; fi
