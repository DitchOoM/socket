#!/usr/bin/env bash
# K/N framework -> xcodegen -> xcodebuild -> uninstall old -> install -> launch. Proves the app is
# resident afterwards by listing the process. The build targets generic iOS so it never waits on the
# phone; only the install does, and that needs the phone paired AND unlocked (wireless is fine).
set -euo pipefail
. "$(dirname "$0")/common.sh"
[ -n "$DEVICE" ] || { echo "no paired iPhone"; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(find ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2 -name Home -type d | head -1)}"
cd "$ROOT" && ./gradlew :ios-probe:linkDebugFrameworkIosArm64 --no-configuration-cache -q
GRADLE_RC=$?; [ "$GRADLE_RC" -eq 0 ] || { echo "framework build failed ($GRADLE_RC)"; exit 1; }
cd "$ROOT/ios-probe/iosApp" && xcodegen generate >/dev/null
xcodebuild -project QuicProbe.xcodeproj -scheme QuicProbe -configuration Debug -destination "generic/platform=iOS" -derivedDataPath "$DD" -allowProvisioningUpdates build 2>&1 | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED" | head -5
xcrun devicectl device uninstall app --device "$DEVICE" "$BUNDLE" >/dev/null 2>&1 || true
xcrun devicectl device install app --device "$DEVICE" "$DD/Build/Products/Debug-iphoneos/QuicProbe.app" 2>&1 | tail -1
echo "installed $(shasum -a 256 "$DD/Build/Products/Debug-iphoneos/QuicProbe.app/QuicProbe" | cut -c1-16)…"
