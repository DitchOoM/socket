#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Network-namespace integration harness for LinuxNetworkMonitor route resolution.
#
# WHY: LinuxNetworkMonitor.primaryNetworkId() resolves the primary link from the
# kernel's routing table (netlink RTM_GETROUTE) with /proc/net/{route,ipv6_route}
# + getifaddrs-scan fallbacks, then classifies the link kind from /sys/class/net.
# Unit tests can only check the pure PARSERS on synthetic text — on any real host
# netlink always answers and the interface/route table is whatever the host has,
# so the actual netlink + /proc + /sys code path, and the fallback tier, are never
# EXERCISED against a known input. This builds controlled network namespaces with a
# known interface + default route and runs the NetnsRouteResolutionTest INSIDE each
# one, so the monitor reads that namespace's real kernel state and we assert it
# picks the right interface + kind.
#
# HOW: rootless via `unshare -rnm` (user+net+mount namespaces — NO sudo/root). A
# sysfs remount makes /sys/class/net reflect the namespace so classifyLinkKind sees
# only the harness interface. /proc/net/* is already per-netns via /proc/self/net.
#
# USAGE: ./run-netns-tests.sh [path/to/test.kexe]
#   default binary: build/bin/linuxX64/debugTest/test.kexe (linkDebugTestLinuxX64)
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

KEXE="${1:-build/bin/linuxX64/debugTest/test.kexe}"
if [ ! -x "$KEXE" ]; then
    echo "ERROR: linuxX64 test binary not found/executable: $KEXE" >&2
    echo "       build it with: ./gradlew :linkDebugTestLinuxX64" >&2
    exit 2
fi
KEXE="$(cd "$(dirname "$KEXE")" && pwd)/$(basename "$KEXE")" # absolutize (cwd changes under unshare)

# Rootless namespaces need unprivileged user namespaces. Ubuntu 24.04 (GitHub's
# ubuntu-latest) gates these behind AppArmor — lift the knob if the first try fails
# and we have sudo (CI runners do), then re-check. Fail fast + loud if still blocked.
if ! unshare -rnm true 2>/dev/null; then
    sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 >/dev/null 2>&1 || true
fi
if ! unshare -rnm true 2>/dev/null; then
    echo "ERROR: 'unshare -rnm' unavailable — unprivileged user namespaces are required." >&2
    echo "       Ubuntu 24.04 restricts them via AppArmor; lift with:" >&2
    echo "         sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0" >&2
    echo "       (or enable kernel.unprivileged_userns_clone=1 on older kernels)." >&2
    exit 2
fi

FILTER="*NetnsRouteResolutionTest*"
pass=0
fail=0
failed=()

# run_scenario <name> <expect_iface> <expect_kind> <setup-commands>
# Runs <setup> inside a fresh rootless namespace, then the test with the expectation.
run_scenario() {
    local name="$1" iface="$2" kind="$3" setup="$4"
    printf '── scenario: %-20s (expect %s / %s)\n' "$name" "$iface" "$kind"
    export NETMON_EXPECT_IFACE="$iface" NETMON_EXPECT_KIND="$kind"
    if unshare -rnm sh -c "
            set -e
            mount -t sysfs sys /sys        # netns-consistent /sys/class/net for classifyLinkKind
            ip link set lo up
            $setup
            exec '$KEXE' --ktest_filter='$FILTER'
        " >/tmp/netns-$name.log 2>&1; then
        echo "   ✓ PASS"
        pass=$((pass + 1))
    else
        echo "   ✗ FAIL — output:"
        sed 's/^/     /' "/tmp/netns-$name.log"
        fail=$((fail + 1))
        failed+=("$name")
    fi
}

# A dummy interface reports ARPHRD_ETHER (type 1) → Ethernet, unless its NAME matches
# a cellular prefix (wwan/rmnet/ppp). A tun device has /sys/.../tun_flags → Vpn.
# Documentation-range addresses (RFC 5737 / RFC 3849) keep the fixtures self-evident.

run_scenario ipv4-only-default eth-test Ethernet '
    ip link add eth-test type dummy; ip link set eth-test up
    ip addr add 192.0.2.2/24 dev eth-test
    ip route add default via 192.0.2.1 dev eth-test'

run_scenario ipv6-only-default eth-test Ethernet '
    ip link add eth-test type dummy; ip link set eth-test up
    ip -6 addr add 2001:db8::2/64 dev eth-test
    ip -6 route add default via 2001:db8::1 dev eth-test'

run_scenario dual-stack-default eth-test Ethernet '
    ip link add eth-test type dummy; ip link set eth-test up
    ip addr add 192.0.2.2/24 dev eth-test;    ip route add default via 192.0.2.1 dev eth-test
    ip -6 addr add 2001:db8::2/64 dev eth-test; ip -6 route add default via 2001:db8::1 dev eth-test'

run_scenario no-default-scan eth-test Ethernet '
    ip link add eth-test type dummy; ip link set eth-test up
    ip addr add 192.0.2.2/24 dev eth-test'   # up + addressed but NO default route → getifaddrs scan

run_scenario cellular-by-name wwan0 Cellular '
    ip link add wwan0 type dummy; ip link set wwan0 up
    ip addr add 192.0.2.2/24 dev wwan0
    ip route add default via 192.0.2.1 dev wwan0'

run_scenario vpn-tun-device tun0 Vpn '
    ip tuntap add tun0 mode tun; ip link set tun0 up
    ip addr add 198.51.100.2/24 dev tun0
    ip route add default via 198.51.100.1 dev tun0'

echo
echo "netns route-resolution: $pass passed, $fail failed"
if [ "$fail" -ne 0 ]; then
    printf '  failed: %s\n' "${failed[@]}"
    exit 1
fi
