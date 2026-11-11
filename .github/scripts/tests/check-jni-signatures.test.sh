#!/usr/bin/env bash
#
# The JNI signature check passes on the real tree and fails, naming the function, on each way the two
# sides can drift: a renamed C function, a changed parameter type, a changed return type, a dropped
# or added parameter, a dead C entry point, and a wrong class prefix. Each mismatch is injected into a
# copy of the real files, so the check is proved against the code it guards, not a toy.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../check-jni-signatures.py"
root="$(cd "$here/../../.." && pwd)"
kt="$root/socket-quic-quiche/src/commonJvmMain/kotlin/com/ditchoom/socket/quic/JniQuicheApi.kt"
c="$root/socket-quic-quiche/src/jni/quiche_jni.c"
pass=0
fail=0

check() { # <name> <expected-exit> <actual-exit> <output> <needle>
  local name="$1" want="$2" got="$3" out="$4" needle="$5"
  if [ "$want" != "$got" ] || ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL: $name — expected exit $want and '$needle', got exit $got"; echo "$out" | sed 's/^/    /'
    fail=$((fail + 1)); return
  fi
  echo "ok: $name"; pass=$((pass + 1))
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

out="$(python3 -I "$script" 2>&1)"; rc=$?
check "the real Kotlin and C agree" 0 "$rc" "$out" "ok: "

# Run the check on a copy of both files with one edit applied: mutate <file: kt|c> <python-expr on s>.
mutate() {
  cp "$kt" "$tmp/JniQuicheApi.kt"; cp "$c" "$tmp/quiche_jni.c"
  python3 -I - "$tmp" "$1" "$2" <<'PY'
import sys
d, which, expr = sys.argv[1:4]
f = d + ("/JniQuicheApi.kt" if which == "kt" else "/quiche_jni.c")
s = open(f).read()
t = eval(expr)
assert t != s, "mutation changed nothing: " + expr
open(f, "w").write(t)
PY
  [ $? -eq 0 ] || { echo "FAIL: could not apply mutation $2"; fail=$((fail + 1)); return 1; }
  OUT="$(python3 -I "$script" --kotlin "$tmp/JniQuicheApi.kt" --c "$tmp/quiche_jni.c" 2>&1)"; RC=$?
}

mutate c 's.replace("JNI_FN(nConfigFree)", "JNI_FN(nConfigFreee)")' && {
  check "a renamed C function is reported on both sides" 1 "$RC" "$OUT" "nConfigFree: an \`external fun\` in Kotlin with no JNI_FN"
  check "the renamed C function is reported as dead" 1 "$RC" "$OUT" "nConfigFreee: a JNI_FN in C with no \`external fun\`"
}

mutate c 's.replace("JNI_FN(nConfigNew)(JNIEnv *env, jclass cls, jint version)", "JNI_FN(nConfigNew)(JNIEnv *env, jclass cls, jlong version)")' &&
  check "a changed C parameter type names the function and parameter" 1 "$RC" "$OUT" "nConfigNew: parameter 1 is jlong in C but Kotlin Int needs jint"

mutate kt 's.replace("external fun nConfigNew(version: Int): Long", "external fun nConfigNew(version: Int): Int")' &&
  check "a changed Kotlin return type is reported" 1 "$RC" "$OUT" "nConfigNew: return type is jlong in C but Kotlin Int needs jint"

mutate kt 's.replace("external fun nConfigFree(config: Long)", "external fun nConfigFree(config: Long, extra: Long)")' &&
  check "an added Kotlin parameter is reported" 1 "$RC" "$OUT" "nConfigFree: C takes 1 parameter(s) after env/class, Kotlin passes 2"

mutate kt 's.replace("external fun nConfigFree(config: Long)", "external fun nConfigFree(config: Boolean)")' &&
  check "a Long that became a Boolean is reported" 1 "$RC" "$OUT" "nConfigFree: parameter 1 is jlong in C but Kotlin Boolean needs jboolean"

mutate c 's.replace("#define JNI_FN(name) Java_com_ditchoom_socket_quic_JniQuicheApi_ ##", "#define JNI_FN(name) Java_com_ditchoom_socket_quic_JniQuicheApii_ ##")' &&
  check "a wrong class prefix is reported" 1 "$RC" "$OUT" "JNI_FN prefix is"

mutate kt 's + "\n    @JvmStatic private external fun nBrandNew(x: Long)\n"' &&
  check "a new external with no C body is reported" 1 "$RC" "$OUT" "nBrandNew: an \`external fun\` in Kotlin with no JNI_FN"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
