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
  cp -R "$repo/socket-quic-quiche/src/jni/." "$work/tree/socket-quic-quiche/src/jni/"
  mkdir -p "$work/tree/socket-quic-quiche/src/nativeInterop" "$work/tree/socket-quic-quiche/src/qnetMain"
  cp -R "$repo/socket-quic-quiche/src/qnetMain/." "$work/tree/socket-quic-quiche/src/qnetMain/"
  cp -R "$repo/socket-quic-quiche/src/nativeInterop/." "$work/tree/socket-quic-quiche/src/nativeInterop/"
  cp -R "$repo/socket-quic-quiche/libs/quiche/include/." "$work/tree/socket-quic-quiche/libs/quiche/include/"
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

fresh
echo 'int qnet_probe(void) { return 0; }' > "$work/tree/socket-quic-quiche/src/jni/qnet_new.c"
differs "a NEW .c file under src/jni moves it"
fresh
mkdir -p "$work/tree/socket-quic-quiche/src/nativeInterop/cinterop"
echo 'headers = qnet.h' > "$work/tree/socket-quic-quiche/src/nativeInterop/cinterop/Qnet.def"
differs "a new file under src/nativeInterop moves it"
fresh
mkdir -p "$work/tree/socket-quic-quiche/src/qnetMain/kotlin"
echo '// binding' > "$work/tree/socket-quic-quiche/src/qnetMain/kotlin/NewBinding.kt"
differs "a new file under src/qnetMain moves it"
fresh
echo '/* qnet edit */' >> "$work/tree/socket-quic-quiche/src/jni/qnet.c"
differs "an edit to qnet.c moves it"
fresh
echo '# edit' >> "$work/tree/socket-quic-quiche/src/nativeInterop/cinterop/QNet.def"
differs "an edit to QNet.def moves it"
fresh
mv "$work/tree/socket-quic-quiche/src/jni/quiche_jni.c" "$work/tree/socket-quic-quiche/src/jni/renamed.c"
differs "renaming a source file moves it (the path is hashed)"

# Broken markers must fail, never hash less.
fresh
awk '/^\/\/ endregion quiche-native-inputs$/ && !done { done = 1; next } { print }' \
  "$work/tree/socket-quic-quiche/build.gradle.kts" > "$work/edited" && mv "$work/edited" "$work/tree/socket-quic-quiche/build.gradle.kts"
fails "an unbalanced region fails"
fresh
rm -rf "$work/tree/socket-quic-quiche/src/jni"
fails "a missing native source directory fails"

# Guard: native build code must sit inside the regions, or editing it would not move the key. Outside the
# regions, no executable line may use the native build's own identifiers, call a compiler or cargo, or
# register an Exec task. The one exception is the cargo-fuzz task, which builds a sanitizer binary that is
# never cached or shipped.
guard_offenders() { # guard_offenders <build.gradle.kts>
  awk '
    { t = $0; gsub(/^[ \t]+|[ \t]+$/, "", t) }
    t == "// region quiche-native-inputs"    { r = 1; next }
    t == "// endregion quiche-native-inputs" { r = 0; next }
    !r && t !~ /^(\/\/|\*|\/\*)/ { print NR ": " t }
  ' "$1" | grep -E 'rustToolchain|ndkClang|llvmStrip|ndkToolchain|androidNdkDir|requireTarget|downloadQuicheSource|patchQuiche|createBuild[A-Za-z]*Task|<Exec>|"(cargo|clang|clang\+\+|gcc|cc|cmake|make|rustc|rustup|llvm-strip)"' |
    grep -vE '^[0-9]+: (\) = tasks\.register<Exec>\(taskName\) \{|environment\("CC", "clang"\)|environment\("CXX", "clang\+\+"\)|"cargo",)' || true
}
offenders="$(guard_offenders "$repo/socket-quic-quiche/build.gradle.kts")"
if [ -z "$offenders" ]; then
  report "no native build code outside the quiche-native-inputs regions" 0
else
  printf '%s\n' "$offenders" >&2
  report "no native build code outside the quiche-native-inputs regions" 1
fi
fresh
printf 'tasks.register<Exec>("buildQnet") {\n    commandLine("clang", "-shared")\n}\n' >> "$work/tree/socket-quic-quiche/build.gradle.kts"
if [ -n "$(guard_offenders "$work/tree/socket-quic-quiche/build.gradle.kts")" ]; then
  report "the guard catches a native Exec task added outside the regions" 0
else
  report "the guard catches a native Exec task added outside the regions" 1
fi

exit "$fail"
