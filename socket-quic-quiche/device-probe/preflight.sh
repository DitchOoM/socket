#!/usr/bin/env bash
# Everything that must be true before a run is worth starting. Exit 1 on the first thing that is not.
# Run AFTER the phone is unlocked: the wifi/route services answer garbage while it is still locked.
set -uo pipefail
. "$(dirname "$0")/common.sh"
fail=0
say() { printf '%-28s %s\n' "$1" "$2"; }
adbs get-state >/dev/null 2>&1 || { echo "FAIL device $SERIAL not attached"; exit 1; }
tun=$(adbs shell ip addr show tun0 2>&1 | head -1)
case "$tun" in *"does not exist"*) say "tailscale" "OFF (no tun0)";; *) say "tailscale" "ON — $tun"; echo "  FAIL: turn Tailscale OFF or zero migrations will be attempted"; fail=1;; esac
# SERVER_HOST is one host, or a COMMA-separated rotation (common.sh) — check every target, never
# just the joined string: `ip route get` on a comma-joined value silently misparses instead of
# testing anything, which is how this check stopped meaning anything after the rotation pair became
# the default (#634). A v6 literal is made of colons, so split ONLY on comma and quote every target.
IFS=',' read -r -a targets <<< "$SERVER_HOST"
route_fail=0
multi=$([ "${#targets[@]}" -gt 1 ] && echo 1 || echo 0)
for target in "${targets[@]}"; do
  label="route to server"
  if [ "$multi" -eq 1 ]; then
    case "$target" in *:*) label="route to server (IPv6)";; *) label="route to server (IPv4)";; esac
  fi
  route=$(adbs shell ip route get "$target" 2>&1 | head -1)
  status=$?
  if [ "$status" -ne 0 ]; then
    say "$label" "UNROUTABLE — $route"
    echo "  FAIL: $target is not reachable from this device"
    route_fail=1
    continue
  fi
  say "$label" "$route"
  case "$route" in *wlan0*|*rmnet*|*ccmni*) ;; *) echo "  WARN: expected wlan0 or a cellular interface";; esac
done
[ "$route_fail" -eq 0 ] || fail=1
inst=$(adbs shell dumpsys package "$PKG" 2>/dev/null | grep -E "lastUpdateTime" | head -1 | sed 's/^ *//')
say "APK installed" "${inst:-NOT INSTALLED}"
[ -n "$inst" ] || fail=1
notif=$(adbs shell dumpsys package "$PKG" 2>/dev/null | grep -E "POST_NOTIFICATIONS.*granted=true" | head -1)
say "POST_NOTIFICATIONS" "${notif:+granted}${notif:-NOT granted (adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS)}"
wl=$(adbs shell dumpsys deviceidle whitelist 2>/dev/null | grep -c "$PKG")
say "doze whitelist" "$([ "$wl" -gt 0 ] && echo yes || echo "NO (run ./doze.sh)")"
[ "$wl" -gt 0 ] || fail=1
bat=$(adbs shell dumpsys battery 2>/dev/null | head -14 | grep -E "^ *(level|AC powered|USB powered)" | tr -s ' ' | tr '\n' ' ')
say "battery" "$bat"
old=$(adbs shell ls -la "$DEVICE_LOG" 2>/dev/null | awk '{print $5" bytes "$6" "$7" "$8}')
say "previous log on device" "${old:-none}"
[ "$fail" -eq 0 ] && echo "PREFLIGHT OK" || { echo "PREFLIGHT FAILED"; exit 1; }
