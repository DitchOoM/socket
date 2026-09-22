#!/usr/bin/env bash
# Shared by every script in this directory. Source it.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-RFCX70ZEM6N}"
PKG="com.ditchoom.socket.quic.quiche.test"
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
PROBE_CLASS="com.ditchoom.socket.quic.DeviceHandoffProbe"
DEVICE_LOG="/sdcard/Android/data/$PKG/files/quic-handoff-probe.log"
DEVICE_TRACES="/sdcard/Android/data/$PKG/files/traces"
# One host, or a COMMA-separated list, one lane per host, all running at once:
#   SERVER_HOST="178.156.248.95,2a01:4ff:f4:eb1a::1"
# Comma, not colon — an IPv6 literal is made of colons. Quote it: the value reaches the device
# through `am instrument -e probeHost`, and an unquoted list is still one shell word only by luck.
SERVER_HOST="${SERVER_HOST:-178.156.248.95,2a01:4ff:f4:eb1a::1}"
SERVER_PORT="${SERVER_PORT:-44433}"
adbs() { "$ADB" -s "$SERIAL" "$@"; }
