package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.net.NetworkInterface

/**
 * Runnable JVM counterpart to the native `NetnsRouteResolutionTest`, exercising the desktop-JVM Linux
 * route-resolution paths against a **controlled network namespace** — the integration coverage unit
 * tests cannot reach (on any normal host the route table is whatever the host has, so the actual
 * `/proc/net/route` parse + `NetworkInterface` scan, and the reactive netlink monitor's seed, are never
 * asserted against a *known* interface).
 *
 * This is a plain `main()` — not a JUnit test — so the `test-harness/netns` runner can `exec` it inside
 * `unshare -rnm` exactly like it does the `.kexe`, with the exit code as pass/fail. It lives in
 * `jvmTest` (never shipped in the published artifact) and is launched off the compiled **jvmTest runtime
 * classpath**, which the build augments with the `java21` FFM output — so both source sets under test are
 * reachable:
 *  - [currentPrimaryNetworkId] — the `commonJvmMain` [JvmNetworkId] path that drives [PollingNetworkMonitor]
 *    (an `internal`, visible here because the test source set is a friend of the module's main sources);
 *  - [NetlinkNetworkMonitor] — the `jvm21Main` FFM `AF_NETLINK` monitor, whose `networkId` is seeded from
 *    its own duplicated copy of the route-aware logic.
 *
 * Self-skips (exit 0) unless `NETMON_EXPECT_IFACE` is set, so it is a no-op outside the harness. The
 * runner must launch it under a **JDK 21** `java` so the FFM (`java.lang.foreign`) classes load.
 *
 * ## Deliberate native-vs-JVM difference
 * The native monitor classifies the link *kind* from `/sys/class/net` (Ethernet / Cellular / Vpn); a raw
 * `NetworkInterface` scan cannot, so the JVM path intentionally reports [NetworkKind.Other] labelled with
 * the interface name. This probe therefore asserts the interface **identity** (`NetworkId.Link` handle ==
 * OS ifindex) and `Other(iface)`, and does **not** assert `NETMON_EXPECT_KIND`'s semantic kind — that is
 * the native harness's job. Identity (the ifindex handle) is what QUIC auto-migration and the
 * per-network capability-cache scope actually key on.
 */
private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

private val failures = mutableListOf<String>()

private fun check(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) failures += message()
}

/**
 * Asserts [id] is a [NetworkId.Link] for [iface]: handle == the OS ifindex, and the raw-scan kind is
 * [NetworkKind.Other] labelled with the interface name (the JVM never guesses semantic kind — see the
 * class doc). [via] names which resolver produced [id].
 */
private fun assertResolvesTo(
    id: NetworkId,
    iface: String,
    expectIdx: Long,
    via: String,
) {
    if (id !is NetworkId.Link) {
        failures += "$via: expected a NetworkId.Link for '$iface', got $id"
        return
    }
    check(id.handle == expectIdx) { "$via: handle ${id.handle} must equal '$iface' ifindex $expectIdx" }
    val kind = id.kind
    if (kind is NetworkKind.Other) {
        check(kind.raw == iface) { "$via: raw-scan kind must be Other('$iface'), was Other('${kind.raw}')" }
    } else {
        failures += "$via: raw-scan derivation must produce NetworkKind.Other, got $kind"
    }
}

fun main() {
    val iface = env("NETMON_EXPECT_IFACE") ?: return // not under the netns harness — no-op
    val nif =
        NetworkInterface.getByName(iface)
            ?: run {
                System.err.println("FAIL: harness interface '$iface' not visible to the JVM in this namespace")
                kotlin.system.exitProcess(1)
            }
    val expectIdx = nif.index.toLong()
    check(expectIdx > 0L) { "harness interface '$iface' must carry a positive OS ifindex, was ${nif.index}" }

    // commonJvmMain JvmNetworkId path (drives PollingNetworkMonitor): /proc/net/route default-route
    // interface, else the up/non-loopback/non-virtual lowest-index scan fallback. Note the fallback is
    // what catches the ipv6-only scenario — this path parses only IPv4 /proc/net/route, so with no IPv4
    // default it falls through to the scan, which still picks the sole namespace interface.
    assertResolvesTo(
        currentPrimaryNetworkId(),
        iface,
        expectIdx,
        via = "currentPrimaryNetworkId (commonJvmMain JvmNetworkId)",
    )

    // jvm21Main FFM path: NetlinkNetworkMonitor opens an AF_NETLINK route socket and seeds networkId
    // synchronously in start() (before the blocking recv loop), from its own route-aware companion — so
    // reading .value straight after construction is race-free. close() unblocks/tears down the socket.
    val monitor = NetlinkNetworkMonitor()
    try {
        assertResolvesTo(
            monitor.networkId.value,
            iface,
            expectIdx,
            via = "NetlinkNetworkMonitor.networkId (jvm21 FFM)",
        )
    } finally {
        monitor.close()
    }

    if (failures.isEmpty()) {
        println("JVM netns probe OK — '$iface' (ifindex $expectIdx) resolved by both JvmNetworkId and NetlinkNetworkMonitor")
    } else {
        System.err.println("JVM netns probe FAILED for '$iface':")
        failures.forEach { System.err.println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
