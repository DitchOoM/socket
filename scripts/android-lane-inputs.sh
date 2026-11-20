# shellcheck shell=bash
# Sourced by scripts/android-emulator-tests.sh and scripts/android-benchmark.sh, from INSIDE the
# emulator step.
#
# THE LANES NEED THE JOBS THAT BUILD THESE, SO NOTHING HERE WAITS
# ----------------------------------------------------------------
# Two jobs of android_integration.yaml build what every lane shares, once per run: `natives` (the
# x86_64 natives, the quic-echo jar, and the JVM test classes' build-cache entries) and `kotlin-build`
# (the Android Kotlin compile and dex, as build-cache entries). The lanes `need` them. They used to
# start beside them and wait here, inside the emulator step, so the boot overlapped the shared build;
# but the wait then counted against the step's deadline, and run 44683082787 (API 29) spent 312 s of
# its 960 s waiting and ran out of time while its tests still passed.
#
# Needs GH_TOKEN, and the Actions environment (GITHUB_RUN_ID, GITHUB_REPOSITORY). Everything fetched
# is named by the artifacts of the SAME run, so a lane never reads another run's inputs.

# Downloads this run's artifact <name> into the workspace. Retried: a finished artifact can answer
# 404/5xx for a moment, and one failed download must not fail a lane that has an emulator booted.
lane_inputs_download() { # <artifact name> <directory>
  local name="$1" dir="${2:-.}" attempt
  for attempt in 1 2 3; do
    if gh run download "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" --name "$name" --dir "$dir"; then
      return 0
    fi
    echo "::warning::download of $name failed (attempt $attempt of 3)"
    sleep $((attempt * 5))
  done
  echo "::error::could not download the artifact $name of this run"
  return 1
}

# Downloads and applies the shared inputs, then prints the seconds it took.
#   $1  "with-build-cache": also take the Gradle build-cache entries (the test lanes; the benchmark
#       job builds release variants and takes only the natives)
lane_inputs_fetch() {
  local with_cache="${1:-}" started=$SECONDS
  : "${GH_TOKEN:?GH_TOKEN is not set: the emulator step passes github.token}"

  lane_inputs_download android-lane-inputs
  # The Gradle build checks the Android natives are there and skips compiling them; the host markers
  # are touched so the JNI shim's mtime guard reads "current" (build-linux's trap #2).
  export ORG_GRADLE_PROJECT_quicheAndroidNativesPrebuilt=true
  find socket-quic-quiche/libs/quiche/linux-x64 \( -name '.built-*' -o -name '.jni-built-*' \) -print0 | xargs -0 -r touch

  if [ "$with_cache" = "with-build-cache" ]; then
    lane_inputs_download android-jvm-build-cache lane-build-cache/jvm
    lane_inputs_download android-kotlin-build-cache lane-build-cache/kotlin
    # Gradle's local build cache: the compiled Kotlin and the dex of every module. The lane's Gradle
    # takes those from here (FROM-CACHE) instead of compiling them. Each artifact has its own
    # directory (`gh run download` refuses to overwrite a file, and the two hold some entries both),
    # and the entries are named by their content key, so a repeat is the same file.
    mkdir -p "$HOME/.gradle/caches/build-cache-1"
    cp -a lane-build-cache/jvm/. "$HOME/.gradle/caches/build-cache-1/"
    cp -a lane-build-cache/kotlin/. "$HOME/.gradle/caches/build-cache-1/"
    echo "build cache entries taken from the shared jobs: $(find "$HOME/.gradle/caches/build-cache-1" -type f | wc -l) ($(du -sh "$HOME/.gradle/caches/build-cache-1" | cut -f1))"
  fi
  echo "lane inputs ready after $((SECONDS - started))s"
}
