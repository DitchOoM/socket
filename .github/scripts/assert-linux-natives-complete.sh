#!/usr/bin/env bash
#
# Assert that the quiche natives build-linux.yaml's `natives` job is about to upload are COMPLETE:
# every file every consumer of the quiche-linux-natives-full / android-native-libs artifacts relies
# on, by name, per target. Exit 0 with one `ok:` line per file, or exit 1 with one `::error::` per
# missing file and a listing of what IS there. The natives job runs this immediately before its
# upload steps, so an incomplete artifact never ships under a green job.
#
# WHY (#461): on PR #460 `buildQuicheSharedLinuxX64` FAILED (a quiche patch anchor was not found),
# `|| true` swallowed it, the JNI shim step fell back to a shim-only link, and the job uploaded an
# artifact with no libquiche.so and no markers — as SUCCESS. Eleven consumer shards then failed two
# minutes later on "No quiche build markers restored … 'include-hidden-files: true' was dropped",
# which was wrong about the cause. A producer that validates its own output turns that cross-job
# mystery into a one-line failure in the job that caused it.
#
# The required set (keep in step with .github/actions/linux-quiche-env's consumer-side checks):
#   linux-x64, linux-arm64   socket-quic-quiche/libs/quiche/<t>/lib/{libquiche.a,libquiche.so,libquiche_jni.so}
#                            + the cargo skip marker  .built-<quiche>-qlog-jvm-<patchDigest>   (dotfile!)
#                            + the JNI-shim skip marker .jni-built-<quiche>                     (dotfile!)
#   android arm64-v8a        socket-quic-quiche/src/androidMain/jniLibs/arm64-v8a/{libquiche.so,libquiche_jni.so}
#   windows-x64              socket-quic-quiche/libs/quiche/windows-x64/lib/quiche_jni.dll — REQUIRED as of #515.
#                            It was optional here because the MinGW cross-compile had never once succeeded (an
#                            unguarded <sys/socket.h>, then a missing `qlog` cargo feature behind it), and
#                            review.yaml's build-windows skipped :socket-quic-quiche:jvmTest whenever it was absent
#                            — which was every run. The cross-build works now, so an absent DLL is a defect and
#                            fails this job like every other file in the set.
#
# The patch digest in the `.built-*` marker is computed inside build.gradle.kts, so the marker is
# matched by `.built-<quiche>-qlog-jvm-*` — the quiche version IS pinned here (read from the same
# gradle/libs.versions.toml the build read), so a marker left behind by an older quiche in a warm
# cache cannot satisfy this.
#
# Usage: assert-linux-natives-complete.sh [repo-root]     (repo-root defaults to the current directory)
#
# errexit: deliberately NOT `set -e`. Every check is an explicit `if`, the counters below are the
# result, and the final `exit` is a statement — nothing here relies on an implicit abort. This also
# keeps the script correct under a caller that already runs it with -e, and immune to the composite
# `shell: bash` trap (a no-match grep inside `VAR=$(...)` aborting the step with no output).
set -uo pipefail

root="${1:-.}"
if [ ! -d "$root" ]; then
  echo "::error::assert-linux-natives-complete: '$root' is not a directory"
  exit 2
fi
root="$(cd "$root" && pwd)"

toml="$root/gradle/libs.versions.toml"
quiche_line=""
if ! quiche_line=$(grep -E '^quiche[[:space:]]*=' "$toml" 2>/dev/null); then
  echo "::error::assert-linux-natives-complete: no 'quiche = \"<version>\"' pin in $toml — cannot name the markers to expect"
  exit 2
fi
quiche_line=${quiche_line%%$'\n'*}   # first match only
quiche_ver=${quiche_line#*\"}
quiche_ver=${quiche_ver%%\"*}
if [ -z "$quiche_ver" ]; then
  echo "::error::assert-linux-natives-complete: could not parse the quiche version out of: $quiche_line"
  exit 2
fi
echo "expecting natives for quiche $quiche_ver under $root"

ok_count=0
missing_count=0
missing_list=()

# A required file: must exist and be non-empty (a zero-byte libquiche.so is exactly as useless as a
# missing one, and a truncated upload would look like that).
require_file() {
  local path=$1
  if [ -s "$root/$path" ]; then
    echo "ok: $path ($(wc -c < "$root/$path" | tr -d ' ') bytes)"
    ok_count=$((ok_count + 1))
  else
    echo "::error::missing (or empty): $path"
    missing_count=$((missing_count + 1))
    missing_list+=("$path")
  fi
}

# A required marker matched by glob (the digest half of its name is Gradle's). compgen -G exits 1
# with no match and prints nothing, which is a normal answer here, not an abort.
require_marker_glob() {
  local dir=$1 pattern=$2
  local matches
  if matches=$(compgen -G "$root/$dir/$pattern"); then
    while IFS= read -r m; do
      echo "ok: $dir/${m##*/}"
    done <<< "$matches"
    ok_count=$((ok_count + 1))
  else
    echo "::error::missing marker: $dir/$pattern"
    missing_count=$((missing_count + 1))
    missing_list+=("$dir/$pattern")
  fi
}

for t in linux-x64 linux-arm64; do
  d="socket-quic-quiche/libs/quiche/$t/lib"
  require_file "$d/libquiche.a"
  require_file "$d/libquiche.so"
  require_file "$d/libquiche_jni.so"
  require_marker_glob "$d" ".built-$quiche_ver-qlog-jvm-*"
  require_file "$d/.jni-built-$quiche_ver"
done

d="socket-quic-quiche/src/androidMain/jniLibs/arm64-v8a"
require_file "$d/libquiche.so"
require_file "$d/libquiche_jni.so"

# The MinGW cross-build is the producer (build-linux.yaml's natives job); review.yaml's build-windows
# is the only consumer and cannot run :socket-quic-quiche:jvmTest without it — the JNI backend is the
# default one that task resolves, so a missing DLL is not a degraded Windows run, it is no Windows run.
require_file "socket-quic-quiche/libs/quiche/windows-x64/lib/quiche_jni.dll"

if [ "$missing_count" -eq 0 ]; then
  echo "natives artifact complete: $ok_count required file(s)/marker(s) present for quiche $quiche_ver"
  exit 0
fi

echo "::error::natives artifact INCOMPLETE — $missing_count required file(s) missing; the natives job must not upload this (#461):"
for m in "${missing_list[@]}"; do
  echo "::error::  $m"
done
echo "--- what IS there (dotfiles included): ---"
# A listing that finds nothing is still a listing; `|| true` keeps a missing tree from masking the
# real exit status below with find's own.
( cd "$root" && find socket-quic-quiche/libs/quiche socket-quic-quiche/src/androidMain/jniLibs -type f 2>/dev/null | LC_ALL=C sort ) || true
echo "--- a missing libquiche.so/.a/marker means the shared build or the JNI-shim Gradle step above FAILED — read its own ::error:: annotation, not a consumer's. ---"
exit 1
