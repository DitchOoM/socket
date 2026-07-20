#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Network-namespace integration harness for Linux NetworkMonitor route resolution
# (native LinuxNetworkMonitor + the desktop-JVM monitors).
#
# WHY: LinuxNetworkMonitor.primaryNetworkId() resolves the primary link from the
# kernel's routing table (netlink RTM_GETROUTE) with /proc/net/{route,ipv6_route}
# + getifaddrs-scan fallbacks, then classifies the link kind from /sys/class/net.
# The desktop JVM does the same in two more places (:network-monitor): the
# commonJvmMain JvmNetworkId /proc/net/route parse + NetworkInterface scan, and the
# jvm21 FFM NetlinkNetworkMonitor. Unit tests can only check the pure PARSERS on
# synthetic text — on any real host netlink always answers and the interface/route
# table is whatever the host has, so the actual netlink + /proc + /sys code path,
# and the fallback tier, are never EXERCISED against a known input. This builds
# controlled network namespaces with a known interface + default route and runs the
# native NetnsRouteResolutionTest AND (when built) the JVM NetnsJvmProbe INSIDE each
# one, so the monitors read that namespace's real kernel state and we assert they
# pick the right interface + kind.
#
# HOW: rootless via `unshare -rnm` (user+net+mount namespaces — NO sudo/root). A
# sysfs remount makes /sys/class/net reflect the namespace so classifyLinkKind sees
# only the harness interface. /proc/net/* is already per-netns via /proc/self/net.
# The native .kexe and the JVM probe run in the SAME namespace per scenario.
#
# USAGE: ./run-netns-tests.sh [path/to/test.kexe]
#   default binary: build/bin/linuxX64/debugTest/test.kexe (linkDebugTestLinuxX64)
#   JVM leg (optional): ./gradlew :network-monitor:netnsJvmProbeClasspath first, or
#   set NETNS_JVM_CLASSPATH / NETNS_JVM_JAVA to the dump files; absent → native-only.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TUN_ATTACH="$SCRIPT_DIR/tun-attach.py"

KEXE="${1:-build/bin/linuxX64/debugTest/test.kexe}"
if [ ! -x "$KEXE" ]; then
    echo "ERROR: linuxX64 test binary not found/executable: $KEXE" >&2
    echo "       build it with: ./gradlew :linkDebugTestLinuxX64" >&2
    exit 2
fi
KEXE="$(cd "$(dirname "$KEXE")" && pwd)/$(basename "$KEXE")" # absolutize (cwd changes under unshare)

# ── Optional JVM leg ─────────────────────────────────────────────────────────
# Alongside the native .kexe, exercise the desktop-JVM Linux route resolution in the SAME namespace:
# the commonJvmMain JvmNetworkId path (drives PollingNetworkMonitor) and the jvm21 FFM NetlinkNetwork
# Monitor. The :network-monitor:netnsJvmProbeClasspath Gradle task writes the jvmTest runtime classpath
# and the JDK21 `java` launcher (required for the FFM classes) to build/netns/. Absent → skipped, so a
# native-only run still works. Override the two files via NETNS_JVM_CLASSPATH / NETNS_JVM_JAVA.
NETNS_JVM_CP_FILE="${NETNS_JVM_CLASSPATH:-network-monitor/build/netns/jvm-test-classpath.txt}"
NETNS_JVM_JAVA_FILE="${NETNS_JVM_JAVA:-network-monitor/build/netns/java21-launcher.txt}"
JVM_PROBE_MAIN="com.ditchoom.socket.NetnsJvmProbeKt"
JVM_CP="" ; JVM_JAVA=""
if [ -r "$NETNS_JVM_CP_FILE" ] && [ -r "$NETNS_JVM_JAVA_FILE" ]; then
    JVM_CP="$(cat "$NETNS_JVM_CP_FILE")"
    JVM_JAVA="$(cat "$NETNS_JVM_JAVA_FILE")"
    if [ -x "$JVM_JAVA" ]; then
        echo "JVM probe enabled: $("$JVM_JAVA" -version 2>&1 | head -1)"
    else
        echo "WARN: JDK21 launcher '$JVM_JAVA' not executable — JVM probe disabled." >&2
        JVM_CP="" ; JVM_JAVA=""
    fi
else
    echo "note: no netns JVM classpath at '$NETNS_JVM_CP_FILE' — running native-only" \
         "(build it with ./gradlew :network-monitor:netnsJvmProbeClasspath)."
fi

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

# run_scenario <name> <expect_iface> <expect_kind> <setup-commands> [jvm=yes|no] [daemon-cmd]
# Runs <setup> inside a fresh rootless namespace, then the native test and (unless jvm=no) the JVM probe,
# both against that namespace's kernel state. An optional <daemon-cmd> is backgrounded before <setup> and
# killed on exit — used to hold a tun device's fd open so it reports carrier (see tun-attach.py).
run_scenario() {
    local name="$1" iface="$2" kind="$3" setup="$4" jvm="${5:-yes}" daemon="${6:-}"
    printf '── scenario: %-20s (expect %s / %s)\n' "$name" "$iface" "$kind"
    export NETMON_EXPECT_IFACE="$iface" NETMON_EXPECT_KIND="$kind"
    # JVM leg (same namespace, after the native .kexe) — empty when the probe is disabled or opted out for
    # this scenario. FFM downcalls are restricted methods on JDK21; --enable-native-access silences the
    # warning without changing behaviour.
    local jvm_cmd=""
    if [ "$jvm" = "yes" ] && [ -n "$JVM_JAVA" ]; then
        jvm_cmd="echo '── JVM probe (JvmNetworkId + NetlinkNetworkMonitor):'
            '$JVM_JAVA' --enable-native-access=ALL-UNNAMED -cp '$JVM_CP' $JVM_PROBE_MAIN"
    fi
    # Optional carrier-holding daemon: background it, then reap it on ANY exit (incl. set -e aborts) so it
    # never outlives the namespace. \$ is escaped so the inner sh evaluates $! / the pid, not the parent.
    local daemon_cmd=""
    if [ -n "$daemon" ]; then
        daemon_cmd="$daemon & __daemon_pid=\$!; trap 'kill \$__daemon_pid 2>/dev/null' EXIT"
    fi
    if unshare -rnm sh -c "
            set -e
            mount -t sysfs sys /sys        # netns-consistent /sys/class/net for classifyLinkKind
            ip link set lo up
            $daemon_cmd
            $setup
            '$KEXE' --ktest_filter='$FILTER'
            $jvm_cmd
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

# VPN: a real tun. Native classifies Vpn from /sys/.../tun_flags regardless of carrier, but the JVM's
# route-aware pick needs the tun UP *with carrier* (isUp() = IFF_UP && IFF_RUNNING) — a bare `ip tuntap`
# tun is NO-CARRIER until a process attaches, exactly as a real VPN daemon does. When python3 + /dev/net/tun
# are available we hold the tun open (tun-attach.py) so both legs see a live VPN link; otherwise we fall
# back to a carrier-less tun and run the native leg only (a down link is legitimately not the JVM primary).
if command -v python3 >/dev/null 2>&1 && [ -e /dev/net/tun ]; then
    run_scenario vpn-tun-device tun0 Vpn '
        for _ in $(seq 1 100); do if [ -e /sys/class/net/tun0 ]; then break; fi; sleep 0.02; done
        ip link set tun0 up
        ip addr add 198.51.100.2/24 dev tun0
        ip route add default via 198.51.100.1 dev tun0' \
        yes "python3 '$TUN_ATTACH' tun0"
else
    echo "note: python3/dev-tun unavailable — vpn-tun runs native-only (JVM needs a carrier-holding tun)."
    run_scenario vpn-tun-device tun0 Vpn '
        ip tuntap add tun0 mode tun; ip link set tun0 up
        ip addr add 198.51.100.2/24 dev tun0
        ip route add default via 198.51.100.1 dev tun0' \
        no ""
fi

echo
echo "netns route-resolution: $pass passed, $fail failed"
if [ "$fail" -ne 0 ]; then
    printf '  failed: %s\n' "${failed[@]}"
    exit 1
fi
