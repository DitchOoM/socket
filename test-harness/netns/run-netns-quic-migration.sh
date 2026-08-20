#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Network-namespace harness for QUIC path migration OFF A PATH THAT DIES.
#
# WHY: every migration test in this repo migrates away from a *healthy* path.
# QuicActiveMigrationTestSuite calls migrate() with no target — "a fresh ephemeral
# socket on the current default interface", i.e. a local PORT change on loopback,
# where 127.0.0.1 never dies. Issue #393 says it outright: that test "passes while
# the property it names does not hold in the field". Finding #393 took a 124-minute
# on-device recording precisely because CI could not express the condition.
#
# Three ingredients are needed together, and loopback supplies none of them:
#   1. the client's local ADDRESS changes (not just its port),
#   2. the old path DIES, for a reason outside the QUIC stack's control,
#   3. the stream is CARRYING DATA across it (a migration on an idle stream
#      cannot regress).
#
# HOW: rootless `unshare -rnm` (user+net+mount namespaces — NO sudo), same as
# run-netns-tests.sh. Three dummy interfaces: eth-srv holds the server address,
# eth-a and eth-b the two client addresses. Every address is local, so packets are
# delivered by the kernel rather than crossing a wire — what matters is that the
# addresses are distinct and SEPARATELY REVOCABLE. `ip link set eth-a down` revokes
# path A's address mid-stream, which is ingredient 2.
#
# The probe asserts byte-exact stream continuity, NOT "the connection survived" —
# the latter is exactly the assertion that failed to catch #393, where the
# connection stayed healthy for 101 minutes while the stream was dead.
#
# USAGE: ./run-netns-quic-migration.sh
#   Requires the JVM probe classpath:
#     ./gradlew :socket-quic-quiche:netnsQuicProbeClasspath
#   Override via NETNS_QUIC_CLASSPATH / NETNS_QUIC_JAVA.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PROBE_MAIN="com.ditchoom.socket.quic.NetnsQuicMigrationProbe"
CP_FILE="${NETNS_QUIC_CLASSPATH:-$REPO_ROOT/socket-quic-quiche/build/netns/jvm-test-classpath.txt}"
JAVA_FILE="${NETNS_QUIC_JAVA:-$REPO_ROOT/socket-quic-quiche/build/netns/java21-launcher.txt}"

if [ ! -r "$CP_FILE" ] || [ ! -r "$JAVA_FILE" ]; then
    echo "ERROR: netns QUIC probe classpath missing." >&2
    echo "       build it with: ./gradlew :socket-quic-quiche:netnsQuicProbeClasspath" >&2
    echo "       (looked for '$CP_FILE' and '$JAVA_FILE')" >&2
    exit 2
fi
JVM_CP="$(cat "$CP_FILE")"
JVM_JAVA="$(cat "$JAVA_FILE")"
if [ ! -x "$JVM_JAVA" ]; then
    echo "ERROR: JDK21 launcher '$JVM_JAVA' is not executable." >&2
    exit 2
fi

# Any cert works — the probe connects with verifyPeer=false. Reuse the quic-echo
# fixture rather than generating one, so this script has no build-order dependency
# on generateHarnessCerts.
CERT="$REPO_ROOT/test-harness/quic-echo/testcerts/cert.crt"
KEY="$REPO_ROOT/test-harness/quic-echo/testcerts/cert.key"
if [ ! -r "$CERT" ] || [ ! -r "$KEY" ]; then
    echo "ERROR: QUIC test cert/key not found at $CERT / $KEY" >&2
    exit 2
fi

# Rootless namespaces need unprivileged user namespaces. Ubuntu 24.04 gates these
# behind AppArmor — lift the knob if the first try fails and we have sudo, then
# re-check. Fail fast and loud if still blocked (same contract as run-netns-tests.sh).
if ! unshare -rnm true 2>/dev/null; then
    sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0 >/dev/null 2>&1 || true
fi
if ! unshare -rnm true 2>/dev/null; then
    echo "ERROR: 'unshare -rnm' unavailable — unprivileged user namespaces are required." >&2
    echo "       Ubuntu 24.04 restricts them via AppArmor; lift with:" >&2
    echo "         sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0" >&2
    exit 2
fi

# Documentation-range addresses (RFC 5737) keep the fixture self-evident.
SRV_ADDR="203.0.113.1"
PATH_A="192.0.2.2"
PATH_B="198.51.100.2"
IFACE_A="eth-a"
IFACE_B="eth-b"
IFACE_SRV="eth-srv"

LOG=/tmp/netns-quic-migration.log

echo "── scenario: quic-migration-off-dead-path"
echo "   server $SRV_ADDR on $IFACE_SRV; client $PATH_A ($IFACE_A) → $PATH_B ($IFACE_B)"

# NET_ADMIN inside the user namespace is what lets the probe itself run
# `ip link set eth-a down` mid-stream; the namespace is ours, so this needs no sudo.
if unshare -rnm sh -c "
        set -e
        ip link set lo up
        ip link add $IFACE_SRV type dummy; ip link set $IFACE_SRV up
        ip addr add $SRV_ADDR/24 dev $IFACE_SRV
        ip link add $IFACE_A type dummy; ip link set $IFACE_A up
        ip addr add $PATH_A/24 dev $IFACE_A
        ip link add $IFACE_B type dummy; ip link set $IFACE_B up
        ip addr add $PATH_B/24 dev $IFACE_B

        export QUIC_NETNS_SERVER_ADDR='$SRV_ADDR'
        export QUIC_NETNS_PATH_A='$PATH_A'
        export QUIC_NETNS_PATH_B='$PATH_B'
        export QUIC_NETNS_IFACE_A='$IFACE_A'
        export QUIC_NETNS_CERT='$CERT'
        export QUIC_NETNS_KEY='$KEY'

        '$JVM_JAVA' --enable-native-access=ALL-UNNAMED \
            --add-opens java.base/java.nio=ALL-UNNAMED \
            -cp '$JVM_CP' $PROBE_MAIN
    " >"$LOG" 2>&1; then
    echo "   ✓ PASS"
    sed 's/^/     /' "$LOG"
    exit 0
else
    echo "   ✗ FAIL — output:"
    sed 's/^/     /' "$LOG"
    exit 1
fi
