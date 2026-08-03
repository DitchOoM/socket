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
# TWO NATIVE BINARIES, as in run-netns-tests.sh: :network-monitor owns classifyLinkKind (and asserts it
# directly), :socket owns enumerateNetworkInterfaces (and asserts the same kind survives that path) —
# issue #269 split them across modules. Both read the SAME host wlan; neither mutates anything.
#
# USAGE: ./run-wifi-classify.sh [path/to/network-monitor-test.kexe] [path/to/socket-test.kexe]
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

require_kexe() {
    local path="$1" gradle_task="$2"
    if [ ! -x "$path" ]; then
        echo "ERROR: linuxX64 test binary not found/executable: $path" >&2
        echo "       build it with: ./gradlew $gradle_task" >&2
        exit 2
    fi
    echo "$(cd "$(dirname "$path")" && pwd)/$(basename "$path")"
}

NM_KEXE="$(require_kexe "${1:-network-monitor/build/bin/linuxX64/debugTest/test.kexe}" \
    ":network-monitor:linkDebugTestLinuxX64")" || exit 2
KEXE="$(require_kexe "${2:-build/bin/linuxX64/debugTest/test.kexe}" \
    ":linkDebugTestLinuxX64")" || exit 2

# sudo only if we are not already root (CI runners have passwordless sudo).
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    if sudo -n true 2>/dev/null; then SUDO="sudo -n"; else
        echo "SKIP: mac80211_hwsim needs root and passwordless sudo is unavailable — Wi-Fi classify skipped."
        echo "      (the classifyLinkKind Wi-Fi branch is still covered by unit tests.)"
        exit 0
    fi
fi

# Skip WITHOUT failing the build, but never let the green step imply Wi-Fi was integration-tested: under
# GitHub Actions emit a ::warning:: annotation so the run visibly flags that this coverage did NOT run
# here (a plain exit-0 step renders as a passing check, which would mislead). The Wi-Fi classifyLinkKind
# branch remains covered by unit tests; this integration path runs only where mac80211_hwsim is available.
skip() {
    local msg="Wi-Fi classify NOT run here (integration path skipped): $1"
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        echo "::warning title=Wi-Fi classify skipped — not integration-tested on this runner::$msg"
    fi
    echo "SKIP: $msg — classifyLinkKind Wi-Fi branch is still covered by unit tests; integration needs mac80211_hwsim."
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
# Leg 1: classifyLinkKind directly (:network-monitor). Leg 2: the same kind via the enumerate path
# an ICE agent actually reads (:socket). Both must pass; report which one failed.
if ! NETMON_WIFI_IFACE="$WLAN" "$NM_KEXE" --ktest_filter='*NetnsWifiClassifyTest*' \
        >/tmp/wifi-classify.log 2>&1; then
    echo "   ✗ FAIL (classifyLinkKind, :network-monitor) — output:"
    sed 's/^/     /' /tmp/wifi-classify.log
    exit 1
fi
if ! NETMON_WIFI_IFACE="$WLAN" "$KEXE" --ktest_filter='*NetnsInterfaceEnumerationTest*' \
        >/tmp/wifi-classify-enumerate.log 2>&1; then
    echo "   ✗ FAIL (enumerateNetworkInterfaces, :socket) — output:"
    sed 's/^/     /' /tmp/wifi-classify-enumerate.log
    exit 1
fi
echo "   ✓ PASS — '$WLAN' classified Wifi (direct + via enumerate)"
