#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Wi-Fi link-kind classification harness (the one NetworkKind branch the rootless
# netns route harness cannot reach).
#
# WHY: LinuxNetworkMonitor.classifyLinkKind maps an interface to NetworkKind.Wifi
# when /sys/class/net/<iface>/phy80211 exists. dummy/tun devices never carry that
# entry, so the netns route harness (which builds only those) can't exercise the
# Wi-Fi branch — it stays unit-tested on synthetic input only. A real 802.11 device
# is needed; mac80211_hwsim simulates one (a wlanN with a real phy80211).
#
# HOW: unlike the rootless route harness, this needs PRIVILEGE (a kernel module) and
# no network namespace — the classification is a read-only /sys lookup of the host's
# wlan. modprobe mac80211_hwsim, bring the wlan up, run NetnsWifiClassifyTest against
# it, then unload the module. BEST-EFFORT: when the module is unavailable (e.g. the
# WSL2 kernel, or a runner without linux-modules-extra) it SKIPS (exit 0) rather than
# failing — the Wi-Fi branch is also covered by the pure classifyLinkKind unit tests.
#
# USAGE: ./run-wifi-classify.sh [path/to/test.kexe]
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

KEXE="${1:-build/bin/linuxX64/debugTest/test.kexe}"
if [ ! -x "$KEXE" ]; then
    echo "ERROR: linuxX64 test binary not found/executable: $KEXE" >&2
    echo "       build it with: ./gradlew :linkDebugTestLinuxX64" >&2
    exit 2
fi
KEXE="$(cd "$(dirname "$KEXE")" && pwd)/$(basename "$KEXE")"

# sudo only if we are not already root (CI runners have passwordless sudo).
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    if sudo -n true 2>/dev/null; then SUDO="sudo -n"; else
        echo "SKIP: mac80211_hwsim needs root and passwordless sudo is unavailable — Wi-Fi classify skipped."
        echo "      (the classifyLinkKind Wi-Fi branch is still covered by unit tests.)"
        exit 0
    fi
fi

skip() {
    echo "SKIP: $1 — Wi-Fi classify skipped (branch still covered by unit tests)."
    exit 0
}

# Try to load the simulator. If the module isn't installed, best-effort install linux-modules-extra for
# the running kernel (GitHub's kernel ships it there), rebuild the module dep map (depmod — a freshly
# apt-installed .ko is invisible to modprobe until then), and retry. Skip gracefully if still absent, and
# surface modprobe's own error so an environment-blocked load (some CI VMs deny init_module) is diagnosable.
MODPROBE_ERR=/tmp/hwsim-modprobe.err
load_hwsim() { $SUDO modprobe mac80211_hwsim radios=1 2>"$MODPROBE_ERR"; }
if ! load_hwsim; then
    $SUDO apt-get update -qq 2>/dev/null || true
    $SUDO apt-get install -y -qq "linux-modules-extra-$(uname -r)" 2>/dev/null || true
    $SUDO depmod -a 2>/dev/null || true
    load_hwsim || skip "mac80211_hwsim won't load on kernel $(uname -r): $(head -1 "$MODPROBE_ERR" 2>/dev/null)"
fi

cleanup() { $SUDO modprobe -r mac80211_hwsim 2>/dev/null || true; }
trap cleanup EXIT

# Discover the wlan the module created (name is kernel-assigned; usually wlan0).
WLAN=""
for _ in $(seq 1 100); do
    WLAN="$(ls /sys/class/net 2>/dev/null | grep -E '^wlan' | head -1)"
    [ -n "$WLAN" ] && break
    sleep 0.05
done
[ -n "$WLAN" ] || skip "mac80211_hwsim loaded but no wlan interface appeared"
[ -e "/sys/class/net/$WLAN/phy80211" ] || skip "'$WLAN' has no phy80211 entry (unexpected)"

$SUDO ip link set "$WLAN" up 2>/dev/null || true
echo "── Wi-Fi classify: $WLAN (phy80211 present) ⇒ expect NetworkKind.Wifi"

# Read-only /sys classification — no namespace needed; the wlan lives in the host netns.
if NETMON_WIFI_IFACE="$WLAN" "$KEXE" --ktest_filter='*NetnsWifiClassifyTest*' >/tmp/wifi-classify.log 2>&1; then
    echo "   ✓ PASS — '$WLAN' classified Wifi"
else
    echo "   ✗ FAIL — output:"
    sed 's/^/     /' /tmp/wifi-classify.log
    exit 1
fi
