#!/usr/bin/env bash
# Tests for .github/scripts/quiche-native-inputs.sh: the digest moves with every input of a quiche
# native and with nothing else.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
script="$repo/.github/scripts/quiche-native-inputs.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail=0
report() { # report <description> <0|1>
  if [ "$2" = 0 ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi
}
same() { if [ "$(digest)" = "$base" ]; then report "$1" 0; else report "$1" 1; fi; }
differs() { if [ "$(digest)" != "$base" ]; then report "$1" 0; else report "$1" 1; fi; }
fails() { if digest >/dev/null 2>&1; then report "$1" 1; else report "$1" 0; fi; }

fresh() { # a copy of the inputs under $work/tree
  rm -rf "$work/tree"
  mkdir -p "$work/tree/gradle" "$work/tree/socket-quic-quiche/src/jni" "$work/tree/socket-quic-quiche/libs/quiche/include"
  cp "$repo/gradle/libs.versions.toml" "$work/tree/gradle/"
  cp "$repo/settings.gradle.kts" "$work/tree/"
  cp "$repo/socket-quic-quiche/build.gradle.kts" "$work/tree/socket-quic-quiche/"
  cp "$repo/socket-quic-quiche/src/jni/quiche_jni.c" "$work/tree/socket-quic-quiche/src/jni/"
  cp "$repo/socket-quic-quiche/libs/quiche/include/quiche.h" "$work/tree/socket-quic-quiche/libs/quiche/include/"
}
digest() { QUICHE_NATIVE_INPUTS_ROOT="$work/tree" bash "$script"; }

fresh
base="$(digest)"
if [ "${#base}" = 16 ]; then report "digest is 16 hex characters" 0; else report "digest is 16 hex characters" 1; fi
same "digest is deterministic"

# Inputs that must NOT move it: the rest of the catalog, and build.gradle.kts outside the regions.
fresh
echo 'someUnrelatedLib = "1.2.3"' >> "$work/tree/gradle/libs.versions.toml"
same "an unrelated catalog line does not move it"
fresh
echo '// test-only configuration' >> "$work/tree/socket-quic-quiche/build.gradle.kts"
same "build.gradle.kts outside the regions does not move it"

# Inputs that MUST move it.
fresh
sed -i.bak -E 's/^quiche = "[^"]*"/quiche = "9.9.9"/' "$work/tree/gradle/libs.versions.toml"
differs "the quiche version moves it"
fresh
sed -i.bak -E 's/^quicheBoringCrate = "[^"]*"/quicheBoringCrate = "9.9.9"/' "$work/tree/gradle/libs.versions.toml"
differs "the boring-crate pin moves it"
fresh
echo '/* shim edit */' >> "$work/tree/socket-quic-quiche/src/jni/quiche_jni.c"
differs "quiche_jni.c moves it"
fresh
echo '/* header edit */' >> "$work/tree/socket-quic-quiche/libs/quiche/include/quiche.h"
differs "quiche.h moves it"
fresh
sed -i.bak -E 's/^(val quicheRustcOpt = ").*"/\1-C opt-level=2"/' "$work/tree/socket-quic-quiche/build.gradle.kts"
differs "a line inside the first region moves it"
fresh
sed -i.bak -E 's/^(val androidNativesPrebuilt =)/val androidEdited = 1\n\1/' "$work/tree/socket-quic-quiche/build.gradle.kts"
differs "a line inside the Android region moves it"

# Broken markers must fail, never hash less.
fresh
awk '/^\/\/ endregion quiche-native-inputs$/ && !done { done = 1; next } { print }' \
  "$work/tree/socket-quic-quiche/build.gradle.kts" > "$work/edited" && mv "$work/edited" "$work/tree/socket-quic-quiche/build.gradle.kts"
fails "an unbalanced region fails"
fresh
rm "$work/tree/socket-quic-quiche/src/jni/quiche_jni.c"
fails "a missing input fails"

exit "$fail"
