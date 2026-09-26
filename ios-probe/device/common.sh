#!/usr/bin/env bash
# Shared by every script here. Source it.
BUNDLE="com.ditchoom.quicprobe"
# The first physical iPhone, read from devicectl's JSON: its table lists simulators too, and its columns
# shift when a device has no hostname. DEVICE in the environment wins.
physical_iphone() {
  local j; j=$(mktemp)
  xcrun devicectl list devices --json-output "$j" >/dev/null 2>&1
  jq -r 'first(.result.devices[] | select(.hardwareProperties.reality == "physical" and .hardwareProperties.deviceType == "iPhone") | .hardwareProperties.udid) // empty' "$j"
  rm -f "$j"
}
DEVICE="${DEVICE:-$(physical_iphone)}"
[ -n "$DEVICE" ] || { echo "FATAL: no physical iPhone visible to devicectl (plug it in, or pair it over Wi-Fi, and unlock it); DEVICE=<udid> overrides." >&2; exit 1; }
DD="${DD:-/tmp/qp-dd}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# The walk server (SERVER_HOST, SERVER_PORT) — the same local config the Android scripts read:
# socket-quic-quiche/device-probe/walk-server.env, see walk-server.env.example there.
. "$ROOT/socket-quic-quiche/device-probe/walk-server.sh"
