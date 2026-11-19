# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh and scripts/android-benchmark.sh, from INSIDE the
# emulator step.
#
# WHY THE LANES TAKE THEIR INPUTS HERE AND NOT IN AN EARLIER STEP
# ---------------------------------------------------------------
# Two jobs of android_integration.yaml build what every lane shares, once per run: `natives` (the
# x86_64 natives, the quic-echo jar, and the JVM test classes' build-cache entries) and `kotlin-build`
# (the Android Kotlin compile and dex, as build-cache entries). A lane that waited for them in a step
# BEFORE the emulator step sat idle for the whole build (6 min cold, run 44532703402) and only then
# started its emulator. The emulator step starts the emulator first, so the wait is here, after the
# boot: the boot, the SDK install and the runner's own setup overlap the shared build.
#
# Needs GH_TOKEN, and the Actions environment (GITHUB_RUN_ID, GITHUB_REPOSITORY).

LANE_INPUTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Downloads this run's artifact <name> into the workspace. Retried: a finished artifact can answer
# 404/5xx for a moment, and one failed download must not fail a lane that has an emulator booted.
lane_inputs_download() { # <artifact name>
  local name="$1" attempt
  for attempt in 1 2 3; do
    if gh run download "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" --name "$name" --dir .; then
      return 0
    fi
    echo "::warning::download of $name failed (attempt $attempt of 3)"
    sleep $((attempt * 5))
  done
  echo "::error::could not download the artifact $name of this run"
  return 1
}

# Waits for and applies the shared inputs, then prints the seconds it took.
#   $1  minutes to wait for the artifacts
#   $2  "with-build-cache": also take the Gradle build-cache entries (the test lanes; the benchmark
#       job builds release variants and takes only the natives)
lane_inputs_fetch() {
  local wait_min="$1" with_cache="${2:-}" started=$SECONDS
  : "${GH_TOKEN:?GH_TOKEN is not set: the emulator step passes github.token}"

  bash "$LANE_INPUTS_DIR/.github/scripts/await-artifact.sh" android-lane-inputs "Android lane inputs (natives" "$wait_min"
  lane_inputs_download android-lane-inputs
  # The Gradle build checks the Android natives are there and skips compiling them; the host markers
  # are touched so the JNI shim's mtime guard reads "current" (build-linux's trap #2).
  export ORG_GRADLE_PROJECT_quicheAndroidNativesPrebuilt=true
  find socket-quic-quiche/libs/quiche/linux-x64 \( -name '.built-*' -o -name '.jni-built-*' \) -print0 | xargs -0 -r touch

  if [ "$with_cache" = "with-build-cache" ]; then
    bash "$LANE_INPUTS_DIR/.github/scripts/await-artifact.sh" android-jvm-build-cache "Android lane inputs (natives" "$wait_min"
    lane_inputs_download android-jvm-build-cache
    bash "$LANE_INPUTS_DIR/.github/scripts/await-artifact.sh" android-kotlin-build-cache "Android Kotlin build cache" "$wait_min"
    lane_inputs_download android-kotlin-build-cache
    # Gradle's local build cache (both artifacts unpack into lane-build-cache/): the compiled Kotlin
    # and the dex of every module. The lane's Gradle takes those from here (FROM-CACHE) instead of
    # compiling them.
    mkdir -p "$HOME/.gradle/caches/build-cache-1"
    cp -a lane-build-cache/. "$HOME/.gradle/caches/build-cache-1/"
    echo "build cache entries taken from the shared jobs: $(find "$HOME/.gradle/caches/build-cache-1" -type f | wc -l) ($(du -sh "$HOME/.gradle/caches/build-cache-1" | cut -f1))"
  fi
  echo "lane inputs ready after $((SECONDS - started))s"
}
