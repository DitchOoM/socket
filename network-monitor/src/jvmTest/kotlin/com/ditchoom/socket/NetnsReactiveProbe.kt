package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.NetworkInterface
import kotlin.system.exitProcess

/**
 * Reactive JVM counterpart to the native [NetnsReactiveChangeTest]: proves the **live** jvm21 FFM
 * [NetlinkNetworkMonitor]'s `AF_NETLINK` event loop re-emits the new primary link when the namespace's
 * routing state changes — not just the construction-time seed (which [NetnsJvmProbe] already covers).
 *
 * Constructs the monitor, awaits the initial primary (`eth-a`), then drives `ip link set eth-a down`
 * from inside the namespace: a `RTMGRP_LINK` event that wakes the monitor's recv loop and drops eth-a's
 * default route, leaving `eth-b` as the sole default. The `networkId` flow must flip to `eth-b`.
 *
 * A plain `main()` (not a JUnit test) so the harness can exec it under a JDK 21 `java` inside
 * `unshare -rnm`, exit code = pass/fail. Self-skips unless `NETMON_REACT_PRIMARY` is set.
 */
private fun rEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

private suspend fun await(
    monitor: NetworkMonitor,
    handle: Long,
    label: String,
) {
    try {
        withTimeout(REACT_TIMEOUT_MS) {
            monitor.networkId.first { it is NetworkId.Link && it.handle == handle }
        }
    } catch (e: Throwable) {
        throw IllegalStateException("timed out awaiting networkId=Link($label); had ${monitor.networkId.value}", e)
    }
}

private fun ifIndexOrDie(name: String): Long =
    NetworkInterface.getByName(name)?.index?.toLong()
        ?: run {
            System.err.println("FAIL: interface '$name' not visible to the JVM in this namespace")
            exitProcess(1)
        }

fun main() {
    val primary = rEnv("NETMON_REACT_PRIMARY") ?: return // not under the reactive netns harness — no-op
    val after = rEnv("NETMON_REACT_AFTER") ?: return
    val primaryIdx = ifIndexOrDie(primary)
    val afterIdx = ifIndexOrDie(after)

    val monitor = NetlinkNetworkMonitor()
    try {
        runBlocking {
            await(monitor, primaryIdx, "$primary($primaryIdx)")
            // Drive the change in this namespace: link-down of the current primary (a RTMGRP_LINK event
            // that also removes eth-a's default route). inheritIO so any ip error surfaces in the log.
            val rc = ProcessBuilder("ip", "link", "set", primary, "down").inheritIO().start().waitFor()
            if (rc != 0) {
                System.err.println("FAIL: 'ip link set $primary down' exited $rc")
                exitProcess(1)
            }
            // The recv loop must react and re-emit the new primary.
            await(monitor, afterIdx, "$after($afterIdx) after link-down")
        }
        println("JVM reactive probe OK — networkId flipped $primary($primaryIdx) → $after($afterIdx) on link-down")
    } catch (e: Throwable) {
        System.err.println("JVM reactive probe FAILED (last networkId=${monitor.networkId.value}): ${e.message}")
        exitProcess(1)
    } finally {
        monitor.close()
    }
}

private const val REACT_TIMEOUT_MS = 5_000L
