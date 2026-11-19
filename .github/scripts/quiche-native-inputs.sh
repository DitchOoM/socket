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
#   * EVERY file under the native source directories, found by glob, sorted by path, path included in the
#     hash: socket-quic-quiche/src/jni (JNI / FFM shim C sources and headers),
#     socket-quic-quiche/src/nativeInterop (cinterop .def files incl. QNet.def, and headers),
#     socket-quic-quiche/src/qnetMain (the qnet Kotlin/Native binding) and
#     socket-quic-quiche/libs/quiche/include (the quiche C header the shims compile against). A new
#     source file moves the key without anyone listing it here. Add a directory to NATIVE_DIRS when a
#     native build starts reading from a new one.
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
NATIVE_DIRS=(socket-quic-quiche/src/jni socket-quic-quiche/src/nativeInterop socket-quic-quiche/src/qnetMain socket-quic-quiche/libs/quiche/include)
for f in gradle/libs.versions.toml "$build" settings.gradle.kts; do
  [ -f "$f" ] || { echo "quiche-native-inputs: missing input $f" >&2; exit 1; }
done
for d in "${NATIVE_DIRS[@]}"; do
  [ -d "$d" ] || { echo "quiche-native-inputs: missing input directory $d" >&2; exit 1; }
done
native_files="$(find "${NATIVE_DIRS[@]}" -type f | LC_ALL=C sort)"
[ -n "$native_files" ] || { echo "quiche-native-inputs: no native source files found" >&2; exit 1; }

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
    while IFS= read -r f; do
      printf 'file %s %s\n' "$f" "$(sha <"$f" | cut -d' ' -f1)"
    done <<<"$native_files"
  } | sha | cut -c1-16
)"
echo "$digest"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "digest=$digest" >> "$GITHUB_OUTPUT"; fi
