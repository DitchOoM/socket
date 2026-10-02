#!/usr/bin/env bash
# Fails unless the socket-quic-quiche JVM jar carries every native a published platform needs, each
# built for that platform's CPU.
#
# Per platform the jar must hold libquiche (the FFM backend on JDK 21+) AND libquiche_jni (the JNI
# backend on JDK 8-20, which NativeLibLoader extracts beside libquiche). Checking for the platform's
# directory alone is not enough: 4.19.0-4.21.0 shipped META-INF/native/macos-x64/ holding only
# libquiche.dylib, so an Intel Mac on JDK 17 failed to load quiche at all, and a directory check passed.
# The architecture is checked too, because a shim built on an arm64 host without `-arch x86_64` is an
# arm64 binary under a macos-x64 name.
#
# The expected set mirrors nativeLibsByPlatform in socket-quic-quiche/build.gradle.kts minus
# windows-x64, which is staged for the Windows test lane only and never published.
#
# Usage: verify-quiche-jvm-natives.sh <socket-quic-quiche-jvm-VERSION.jar>
set -euo pipefail

jar="${1:?usage: $0 <socket-quic-quiche-jvm jar>}"
[ -f "$jar" ] || { echo "::error::$jar does not exist"; exit 1; }

# platform  file  `file(1)` must report this architecture
expected="
linux-x64    libquiche.so         x86-64
linux-x64    libquiche_jni.so     x86-64
linux-arm64  libquiche.so         aarch64
linux-arm64  libquiche_jni.so     aarch64
macos-x64    libquiche.dylib      x86_64
macos-x64    libquiche_jni.dylib  x86_64
macos-arm64  libquiche.dylib      arm64
macos-arm64  libquiche_jni.dylib  arm64
"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q -o "$jar" 'META-INF/native/*' -d "$work" 2>/dev/null || true

failed=0
while read -r platform lib arch; do
  [ -n "$platform" ] || continue
  f="$work/META-INF/native/$platform/$lib"
  if [ ! -s "$f" ]; then
    echo "::error::missing META-INF/native/$platform/$lib"
    failed=1
    continue
  fi
  desc="$(file -b "$f")"
  case "$desc" in
    *"$arch"*) echo "ok: $platform/$lib ($arch)" ;;
    *)
      echo "::error::META-INF/native/$platform/$lib is not built for $arch: $desc"
      failed=1
      ;;
  esac
done <<< "$expected"

if [ "$failed" != 0 ]; then
  echo "::error::$(basename "$jar") is missing natives a published platform needs (see above)"
  exit 1
fi
echo "all published quiche natives present, each for its platform's architecture"
