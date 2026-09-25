#!/usr/bin/env bash
# Shared by every script in this directory. Source it.
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-RFCX70ZEM6N}"
PKG="com.ditchoom.socket.quic.quiche.test"
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
PROBE_CLASS="com.ditchoom.socket.quic.DeviceHandoffProbe"
DEVICE_LOG="/sdcard/Android/data/$PKG/files/quic-handoff-probe.log"
DEVICE_TRACES="/sdcard/Android/data/$PKG/files/traces"
# The server (SERVER_HOST, SERVER_PORT, SERVER_SSH) comes from local config: walk-server.env, see
# walk-server.env.example. SERVER_HOST is one host, or a COMMA-separated list, one lane per host, all
# running at once. Comma, not colon — an IPv6 literal is made of colons. Quote it: the value reaches
# the device through `am instrument -e probeHost`, and an unquoted list is one shell word only by luck.
# A script that talks to the server calls `walk_server_require` for what it needs.
. "$(dirname "${BASH_SOURCE[0]}")/walk-server.sh"
adbs() { "$ADB" -s "$SERIAL" "$@"; }
