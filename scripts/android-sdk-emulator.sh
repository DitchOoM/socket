#!/usr/bin/env bash
#
# Installs and verifies the Android Emulator + system image the android_integration lane boots.
#
# sdkmanager reports a package it failed to install as a *Warning* and exits 0 — a corrupt download
# prints "An error occurred while preparing SDK package Android Emulator: Error on ZipFile unknown
# archive." and leaves the emulator absent. `install` therefore treats sdkmanager output matching
# error|corrupt|unknown archive as a failure, retries the download a bounded number of times, and
# then requires the installed emulator to report its version (headless, as the lane runs it), so a
# bad download fails this step with its real cause instead of surfacing later as a boot timeout or
# an `adb emu kill` that cannot connect.
#
# reactivecircus/android-emulator-runner runs its own `sdkmanager --install emulator` and system
# image install (stderr unchecked) at the start of each of its steps. After `install` those are
# no-ops; `verify` runs as each action step's pre-emulator-launch-script to catch the case where they
# were not.
#
# Usage: android-sdk-emulator.sh install|verify <api-level>

set -euo pipefail

MODE="${1:?usage: android-sdk-emulator.sh install|verify <api-level>}"
API_LEVEL="${2:?usage: android-sdk-emulator.sh install|verify <api-level>}"
: "${ANDROID_HOME:?ANDROID_HOME is not set}"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
IMAGE_PKG="system-images;android-${API_LEVEL};default;x86_64"
IMAGE_DIR="$ANDROID_HOME/system-images/android-${API_LEVEL}/default/x86_64"
# The action's `channel: stable`, which this workflow leaves at its default.
CHANNEL=0
# sdkmanager output naming a package it did not install, while still exiting 0.
FAILURE_PATTERN='error|corrupt|unknown archive'
ATTEMPTS=3

# A workflow-command message is one line; encode the rest (GitHub renders %0A as a newline).
gh_escape() {
  local s="$1"
  s="${s//'%'/%25}"
  s="${s//$'\r'/%0D}"
  s="${s//$'\n'/%0A}"
  printf '%s' "$s"
}

# Prints why the installed emulator/system image is unusable and returns 1, or prints the emulator
# version and returns 0.
check_installed() {
  local out
  # -no-window as the lane launches it: that selects the headless qemu. Without it the emulator
  # runs the windowed qemu, which needs libpulse.so.0, absent on the runner.
  if ! out=$(timeout 60 "$EMULATOR" -no-window -version 2>&1); then
    echo "'$EMULATOR -no-window -version' failed:"
    echo "$out"
    return 1
  fi
  case "$out" in
    *"Android emulator version"*) ;;
    *)
      echo "'$EMULATOR -no-window -version' printed no version:"
      echo "$out"
      return 1
      ;;
  esac
  if [ ! -s "$IMAGE_DIR/system.img" ]; then
    echo "system image $IMAGE_PKG is not installed: no $IMAGE_DIR/system.img"
    return 1
  fi
  echo "${out%%$'\n'*}"
  echo "system image: $IMAGE_DIR"
}

verify() {
  local why
  if ! why=$(check_installed); then
    echo "::error title=Android Emulator not installed (API ${API_LEVEL})::$(gh_escape "$why")"
    echo "$why"
    exit 1
  fi
  echo "$why"
}

install() {
  if [ ! -x "$SDKMANAGER" ]; then
    echo "::error::sdkmanager not found at $SDKMANAGER"
    exit 1
  fi
  # `yes` dies of SIGPIPE once sdkmanager stops reading; that is not a failure.
  { yes 2>/dev/null || true; } | "$SDKMANAGER" --licenses >/dev/null

  local attempt raw shown status why
  raw=$(mktemp)
  shown=$(mktemp)
  for attempt in $(seq 1 "$ATTEMPTS"); do
    echo "sdkmanager install emulator + $IMAGE_PKG (attempt $attempt/$ATTEMPTS)"
    status=0
    "$SDKMANAGER" --install emulator "$IMAGE_PKG" --channel="$CHANNEL" >"$raw" 2>&1 || status=$?
    # Progress bars redraw with \r; keep every message line, drop the bars.
    tr '\r' '\n' <"$raw" | { grep -vE '^\[[= ]*\]' || true; } | { grep -v '^[[:space:]]*$' || true; } >"$shown"
    cat "$shown"

    why=""
    if [ "$status" != "0" ]; then
      why="sdkmanager exited $status"
    elif grep -qiE "$FAILURE_PATTERN" "$shown"; then
      why="sdkmanager reported a failed package: $(grep -m 3 -iE "$FAILURE_PATTERN" "$shown")"
    elif ! why=$(check_installed); then
      :
    else
      echo "$why"
      return 0
    fi

    if [ "$attempt" -lt "$ATTEMPTS" ]; then
      echo "::warning title=Android SDK download failed (API ${API_LEVEL}, attempt $attempt/$ATTEMPTS)::$(gh_escape "$why")"
      sleep $((attempt * 10))
    fi
  done
  echo "::error title=Android Emulator install failed (API ${API_LEVEL})::$(gh_escape "$why")"
  exit 1
}

case "$MODE" in
  install) install ;;
  verify) verify ;;
  *)
    echo "usage: android-sdk-emulator.sh install|verify <api-level>" >&2
    exit 2
    ;;
esac
