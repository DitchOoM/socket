#!/usr/bin/env bash
# K/N framework -> xcodegen -> xcodebuild -> install (in place; FRESH=1 uninstalls first) -> launch.sh. Proves the app is
# resident afterwards by listing the process. The build targets generic iOS so it never waits on the
# phone; only the install does, and that needs the phone paired AND unlocked (wireless is fine).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/common.sh"
[ -n "$DEVICE" ] || { echo "no paired iPhone"; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(find ~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2 -name Home -type d | head -1)}"
cd "$ROOT" && ./gradlew :ios-probe:linkDebugFrameworkIosArm64 --no-configuration-cache -q
GRADLE_RC=$?; [ "$GRADLE_RC" -eq 0 ] || { echo "framework build failed ($GRADLE_RC)"; exit 1; }
cd "$ROOT/ios-probe/iosApp" && xcodegen generate >/dev/null
xcodebuild -project QuicProbe.xcodeproj -scheme QuicProbe -configuration Debug -destination "generic/platform=iOS" -derivedDataPath "$DD" -allowProvisioningUpdates build 2>&1 | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED" | head -5
# An in-place install keeps the app's data and permissions: the walk on the phone survives the update
# and resumes on the next launch. FRESH=1 uninstalls first — that deletes the walk's log and traces
# (pull them first) and resets Location/Notifications/Motion to "ask".
if [ "${FRESH:-}" = "1" ]; then xcrun devicectl device uninstall app --device "$DEVICE" "$BUNDLE" >/dev/null 2>&1 || true; fi
# devicectl's text output is not a result: on a locked phone it can print "BSErrorCodeDescription =
# Locked" and still exit 0, and the app being listed afterwards may be LAST month's build. The JSON
# result names what this invocation installed, and that is the only proof.
J="$DD/install-result.json"
xcrun devicectl device install app --device "$DEVICE" "$DD/Build/Products/Debug-iphoneos/QuicProbe.app" --json-output "$J" >/dev/null 2>&1 || true
python3 -c "import json,sys; d=json.load(open('$J')); a=[x.get('bundleID') for x in d.get('result',{}).get('installedApplications',[])]; sys.exit(0 if '$BUNDLE' in a else 1)" \
  || { echo "NOT INSTALLED — $(python3 -c "import json; d=json.load(open('$J')); e=d.get('error',{}); print(e.get('code'), e.get('domain'))"); unlock the phone and rerun"; exit 1; }
echo "installed $(shasum -a 256 "$DD/Build/Products/Debug-iphoneos/QuicProbe.app/QuicProbe" | cut -c1-16)…"
# Installing over a running app terminates it, walk included, and the app comes back only when it is
# launched. Launch it now, in the same unlocked window the install needed: a launch left for later met
# a locked phone on 10-31 and the walk sat dead for 5.4 h. With no WALK_AUTOSTART this resumes the walk
# on the phone; WALK_AUTOSTART/WALK_MINUTES/WALK_PINNED pass through to launch.sh.
"$HERE/launch.sh"
