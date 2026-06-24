#!/usr/bin/env bash
# Run the Kotlin/Native **linuxArm64** test suites on real aarch64 Linux.
#
# WHY THIS EXISTS: a macOS host cannot compile ANY Kotlin/Native Linux target (K/N has no
# Linux backend on a Mac), and most CI / dev arm64-Linux hardware is scarce. But a K/N
# `linkDebugTest*` binary is a self-contained ELF (quiche/BoringSSL/liburing are
# whole-archived statically; only glibc + libstdc++/libgcc_s/libcrypt are dynamically
# needed). So we split the work: CROSS-LINK the arm64 test binary on an x86_64 Linux host
# that has the aarch64 cross-toolchain + arm64 libquiche.a, then RUN that binary on real
# aarch64 Linux inside a container (Apple `container` or Docker — both run arm64 natively on
# Apple Silicon; on x86_64 Linux they need qemu-user binfmt).
#
# NOTHING here is tied to a specific machine — every host/path/runtime is an env var.
#
# USAGE
#   # build on a remote Linux x86_64 host over ssh (default mode):
#   ARM64_BUILD_HOST=user@linuxbox scripts/arm64-container-test.sh
#   # build locally (only valid when THIS machine is x86_64 Linux with the toolchain):
#   ARM64_BUILD_LOCAL=1 scripts/arm64-container-test.sh
#   # subset of modules, or a test filter:
#   ARM64_BUILD_HOST=… scripts/arm64-container-test.sh socket-quic-quiche
#   KTEST_FILTER='com.ditchoom.socket.quic.*Lifecycle*' ARM64_BUILD_HOST=… scripts/arm64-container-test.sh
#
# ENV (all optional unless noted)
#   ARM64_BUILD_HOST   ssh target that can cross-link linuxArm64 (required unless ARM64_BUILD_LOCAL=1).
#                      Must have: the repo checkout, JDK 21, cargo, gcc-aarch64-linux-gnu, and
#                      socket-quic-quiche/libs/quiche/linux-arm64/lib/libquiche.a present.
#   ARM64_BUILD_LOCAL  build on this machine instead of over ssh (this machine must be x86_64 Linux).
#   REMOTE_REPO        repo path on the build host (default: the same path as this checkout's root).
#   REMOTE_JAVA_HOME   JAVA_HOME to export on the build host before gradle (default: build host's default).
#   CONTAINER_RUNTIME  'container' | 'docker' (default: auto — prefer Apple `container`, else docker).
#   ARM64_IMAGE        base image (default: docker.io/library/ubuntu:24.04).
#   KTEST_FILTER       K/N gtest-style filter passed as --ktest_filter (default: run all tests).
#   MODULES (args)     space-separated module names; default: the three native QUIC-stack modules.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULES=("${@:-}")
[ -z "${MODULES[*]}" ] && MODULES=(socket-quic-quiche socket-http3 socket-webtransport)

REMOTE_REPO="${REMOTE_REPO:-$ROOT}"
ARM64_IMAGE="${ARM64_IMAGE:-docker.io/library/ubuntu:24.04}"
KTEST_FILTER="${KTEST_FILTER:-}"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/arm64-kexe.XXXXXX")"
NAME="socket-arm64-$$"

# ---- pick a container runtime -------------------------------------------------
RUNTIME="${CONTAINER_RUNTIME:-}"
if [ -z "$RUNTIME" ]; then
  if command -v container >/dev/null 2>&1; then RUNTIME=container
  elif command -v docker >/dev/null 2>&1; then RUNTIME=docker
  else echo "ERROR: no container runtime found (install Apple 'container' or 'docker')" >&2; exit 1; fi
fi
echo "[arm64] runtime=$RUNTIME image=$ARM64_IMAGE modules='${MODULES[*]}'"

STARTED_APPLE_SYS=0
cleanup() {
  "$RUNTIME" stop "$NAME" >/dev/null 2>&1 || true
  "$RUNTIME" rm "$NAME" >/dev/null 2>&1 || true
  [ "$STARTED_APPLE_SYS" = 1 ] && container system stop >/dev/null 2>&1 || true
  rm -rf "$STAGE"
}
trap cleanup EXIT

# ---- 1. cross-link the arm64 test binaries on a Linux x86_64 host -------------
build_targets=""
for m in "${MODULES[@]}"; do build_targets="$build_targets :$m:linkDebugTestLinuxArm64"; done
gradle_cmd="${REMOTE_JAVA_HOME:+JAVA_HOME=$REMOTE_JAVA_HOME }./gradlew$build_targets --console=plain"

if [ "${ARM64_BUILD_LOCAL:-0}" = 1 ]; then
  echo "[arm64] cross-linking locally: $gradle_cmd"
  ( cd "$ROOT" && eval "$gradle_cmd" )
  fetch() { cp "$ROOT/$1" "$2"; }
  fetchdir() { cp -R "$ROOT/$1/." "$2/"; }
else
  : "${ARM64_BUILD_HOST:?set ARM64_BUILD_HOST=user@linuxbox (or ARM64_BUILD_LOCAL=1 on x86_64 Linux)}"
  SSH=(ssh -o RemoteCommand=none -o RequestTTY=no "$ARM64_BUILD_HOST")
  echo "[arm64] cross-linking on $ARM64_BUILD_HOST: cd $REMOTE_REPO && $gradle_cmd"
  "${SSH[@]}" "cd '$REMOTE_REPO' && $gradle_cmd"
  fetch()    { scp -q -o RemoteCommand=none "$ARM64_BUILD_HOST:$REMOTE_REPO/$1" "$2"; }
  fetchdir() { rsync -aq -e "ssh -o RemoteCommand=none" "$ARM64_BUILD_HOST:$REMOTE_REPO/$1/" "$2/"; }
fi

# ---- 2. stage each module's binary + its testcerts (run CWD needs testcerts/) --
declare -a RUN_MODULES=()
for m in "${MODULES[@]}"; do
  kexe="$m/build/bin/linuxArm64/debugTest/test.kexe"
  dest="$STAGE/$m"; mkdir -p "$dest/testcerts"
  if fetch "$kexe" "$dest/test.kexe" 2>/dev/null; then
    fetchdir "$m/testcerts" "$dest/testcerts" 2>/dev/null || true
    RUN_MODULES+=("$m")
    echo "[arm64] staged $m"
  else
    echo "[arm64] SKIP $m — no linuxArm64 test binary (NO-SOURCE? module has no linuxArm64 test set)"
  fi
done
[ ${#RUN_MODULES[@]} -eq 0 ] && { echo "ERROR: nothing to run" >&2; exit 1; }

# ---- 3. start an aarch64 Linux container, run each binary ----------------------
if [ "$RUNTIME" = container ]; then
  echo y | container system start >/dev/null 2>&1 && STARTED_APPLE_SYS=1 || true
fi
"$RUNTIME" run -d --name "$NAME" -v "$STAGE:/work" -w /work "$ARM64_IMAGE" sleep infinity >/dev/null
arch="$("$RUNTIME" exec "$NAME" uname -m)"
[ "$arch" = aarch64 ] || { echo "ERROR: container arch is '$arch', expected aarch64" >&2; exit 1; }
"$RUNTIME" exec "$NAME" sh -c 'apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq libstdc++6 libgcc-s1 libcrypt1 >/dev/null 2>&1' || true
echo "[arm64] container up (aarch64); runtime libs installed"

rc=0
for m in "${RUN_MODULES[@]}"; do
  echo "========================================================================"
  echo "[arm64] RUN $m"
  echo "========================================================================"
  if [ -n "$KTEST_FILTER" ]; then
    "$RUNTIME" exec -w "/work/$m" "$NAME" ./test.kexe --ktest_filter="$KTEST_FILTER" || rc=1
  else
    "$RUNTIME" exec -w "/work/$m" "$NAME" ./test.kexe || rc=1
  fi
done

echo "========================================================================"
[ $rc -eq 0 ] && echo "[arm64] ALL MODULES PASSED" || echo "[arm64] FAILURES (see above)"
exit $rc
