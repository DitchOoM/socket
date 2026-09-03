#!/usr/bin/env bash
# Exempt the test package from Doze/App Standby so a stationary night does not stall the probe.
# Samsung's own "sleeping apps" list is NOT reachable from adb: on the phone, Settings > Battery >
# Background usage limits > Never sleeping apps > add the probe if it appears there.
set -euo pipefail
. "$(dirname "$0")/common.sh"
adbs shell dumpsys deviceidle whitelist "+$PKG"
adbs shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true
adbs shell cmd appops set "$PKG" RUN_IN_BACKGROUND allow || true
adbs shell am set-inactive "$PKG" false || true
adbs shell am set-standby-bucket "$PKG" active || true
adbs shell dumpsys deviceidle whitelist | grep "$PKG"
