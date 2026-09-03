#!/usr/bin/env bash
# Shared by every script in this directory. Source it.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-RFCX70ZEM6N}"
PKG="com.ditchoom.socket.quic.quiche.test"
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
PROBE_CLASS="com.ditchoom.socket.quic.DeviceHandoffProbe"
DEVICE_LOG="/sdcard/Android/data/$PKG/files/quic-handoff-probe.log"
SERVER_HOST="${SERVER_HOST:-178.156.248.95}"
SERVER_PORT="${SERVER_PORT:-44433}"
adbs() { "$ADB" -s "$SERIAL" "$@"; }
