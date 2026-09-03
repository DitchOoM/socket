#!/usr/bin/env bash
# Install the test APK built by `./gradlew :socket-quic-quiche:assembleDebugAndroidTest` and PROVE it
# is the one on the device by sha256 — a stale APK validates the old code and reports it fixed.
set -euo pipefail
. "$(dirname "$0")/common.sh"
APK="${1:-$(dirname "$0")/../build/outputs/apk/androidTest/debug/socket-quic-quiche-debug-androidTest.apk}"
[ -f "$APK" ] || { echo "no APK at $APK"; exit 1; }
local_sha=$(shasum -a 256 "$APK" | cut -d' ' -f1)
adbs install -r -t "$APK"
path=$(adbs shell pm path "$PKG" | sed 's/^package://' | tr -d '\r')
device_sha=$(adbs shell sha256sum "$path" | cut -d' ' -f1 | tr -d '\r')
echo "local  $local_sha"
echo "device $device_sha"
[ "$local_sha" = "$device_sha" ] && echo "INSTALL PROVEN" || { echo "INSTALL MISMATCH"; exit 1; }
adbs shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
