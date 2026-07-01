@file:Suppress("unused") // Loaded at runtime via multi-release JAR (META-INF/versions/21)

package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.net.NetworkInterface
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * FFM (Java 21+ Foreign Function & Memory) bindings to the handful of libc
 * routing-socket calls the reactive desktop-JVM [NetworkMonitor] needs.
 *
 * Only `socket`/`bind`/`recv`/`close` are bound. The actual availability check
 * is done with the portable JDK [NetworkInterface] API — FFM is used *solely* to
 * block until the kernel signals a routing change (Linux netlink / macOS PF_ROUTE),
 * exactly mirroring the Linux Kotlin/Native [LinuxNetworkMonitor] hybrid approach.
 */
internal object Libc {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private fun handle(
        name: String,
        desc: FunctionDescriptor,
    ): MethodHandle =
        linker.downcallHandle(
            lookup.find(name).orElseThrow { UnsatisfiedLinkError("libc symbol not found: $name") },
            desc,
        )

    private val hSocket = handle("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
    private val hBind = handle("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    private val hRecv = handle("recv", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT))
    private val hClose = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    fun socket(
        domain: Int,
        type: Int,
        protocol: Int,
    ): Int = hSocket.invokeExact(domain, type, protocol) as Int

    fun bind(
        fd: Int,
        addr: MemorySegment,
        addrLen: Int,
    ): Int = hBind.invokeExact(fd, addr, addrLen) as Int

    fun recv(
        fd: Int,
        buf: MemorySegment,
        len: Long,
        flags: Int,
    ): Long = hRecv.invokeExact(fd, buf, len, flags) as Long

    fun close(fd: Int): Int = hClose.invokeExact(fd) as Int
}

/**
 * Base for the desktop-JVM reactive [NetworkMonitor]s that block on a kernel
 * routing socket via FFM and re-derive availability from [NetworkInterface].
 *
 * Subclasses only supply the platform routing socket ([openRoutingSocket]); the
 * event loop, threading, and availability check are shared.
 *
 * Lifetime: the recv-loop coroutine owns the native scratch buffer (its own [Arena],
 * closed in `finally` on the coroutine's own thread). [close] merely closes the fd,
 * which unblocks the native `recv` — the only way to interrupt the blocking downcall,
 * since the loop has no suspension point to cancel at. The arena is deliberately NOT
 * closed from [close]: closing an arena while a `recv` downcall is still reading from
 * it throws `IllegalStateException: Session is acquired`.
 */
abstract class FfmRoutingSocketNetworkMonitor : NetworkMonitor {
    private val _availability = MutableStateFlow(NetworkAvailability.UNKNOWN)
    override val availability: StateFlow<NetworkAvailability> = _availability.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var fd: Int = -1

    /**
     * Opens (and binds, if the protocol requires it) the platform routing socket,
     * allocating any transient native memory in its own short-lived [Arena]. Returns
     * the fd, or a negative value on failure.
     */
    protected abstract fun openRoutingSocket(): Int

    protected fun start() {
        _availability.value = checkInterfaces()
        fd = openRoutingSocket()
        if (fd < 0) return

        val localFd = fd
        scope.launch {
            // The scratch buffer's arena is owned by THIS coroutine and closed on its
            // own thread once the recv loop ends — never cross-thread from close().
            // The loop has no suspension point, so the coroutine never hops threads.
            Arena.ofConfined().use { arena ->
                val scratch = arena.allocate(RECV_BUFFER_SIZE.toLong())
                try {
                    while (isActive) {
                        val n = Libc.recv(localFd, scratch, RECV_BUFFER_SIZE.toLong(), 0)
                        if (n <= 0L) break
                        _availability.value = checkInterfaces()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Socket torn down out from under the blocking recv, or an FFM
                    // downcall failure — stop monitoring; last known state is retained.
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        if (fd >= 0) {
            Libc.close(fd)
            fd = -1
        }
    }

    private companion object {
        private const val RECV_BUFFER_SIZE = 4096

        /**
         * Portable availability check: at least one non-loopback interface is up.
         * Identical semantics to [PollingNetworkMonitor.defaultCheck].
         */
        fun checkInterfaces(): NetworkAvailability =
            try {
                val hasUp =
                    NetworkInterface
                        .getNetworkInterfaces()
                        ?.toList()
                        ?.any { !it.isLoopback && it.isUp } == true
                if (hasUp) NetworkAvailability.AVAILABLE else NetworkAvailability.UNAVAILABLE
            } catch (_: Exception) {
                NetworkAvailability.UNKNOWN
            }
    }
}

/**
 * Linux desktop-JVM [NetworkMonitor] using an `AF_NETLINK` / `NETLINK_ROUTE`
 * socket subscribed to link and IPv4/IPv6 address multicast groups. Event-driven,
 * no native libraries, no polling — the FFM analogue of [LinuxNetworkMonitor].
 */
class NetlinkNetworkMonitor : FfmRoutingSocketNetworkMonitor() {
    init {
        start()
    }

    override fun openRoutingSocket(): Int {
        val fd = Libc.socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE)
        if (fd < 0) return -1

        Arena.ofConfined().use { arena ->
            // struct sockaddr_nl { u16 nl_family; u16 nl_pad; u32 nl_pid; u32 nl_groups; } — 12 bytes
            val addr = arena.allocate(SOCKADDR_NL_SIZE.toLong())
            addr.fill(0)
            addr.set(JAVA_SHORT, 0, AF_NETLINK.toShort())
            addr.set(JAVA_INT, 8, NETLINK_GROUPS)
            if (Libc.bind(fd, addr, SOCKADDR_NL_SIZE) < 0) {
                Libc.close(fd)
                return -1
            }
        }
        return fd
    }

    private companion object {
        private const val AF_NETLINK = 16
        private const val SOCK_RAW = 3
        private const val NETLINK_ROUTE = 0
        private const val SOCKADDR_NL_SIZE = 12

        // RTMGRP_LINK (0x1) | RTMGRP_IPV4_IFADDR (0x10) | RTMGRP_IPV6_IFADDR (0x100)
        private const val NETLINK_GROUPS = 0x1 or 0x10 or 0x100
    }
}

/**
 * macOS desktop-JVM [NetworkMonitor] using a `PF_ROUTE` routing socket — the BSD
 * analogue of netlink. Every routing/interface/address change is delivered as a
 * routing message; any message triggers a re-check.
 *
 * Compile-faithful on non-macOS hosts (pure JDK FFM); the `PF_ROUTE` socket only
 * exists at runtime on Darwin. Deliberately NOT `NWPathMonitor`: its Objective-C
 * completion block is not FFM-friendly.
 */
class RouteNetworkMonitor : FfmRoutingSocketNetworkMonitor() {
    init {
        start()
    }

    // PF_ROUTE sockets need no bind(): the kernel delivers all routing messages.
    override fun openRoutingSocket(): Int = Libc.socket(PF_ROUTE, SOCK_RAW, 0)

    private companion object {
        private const val PF_ROUTE = 17
        private const val SOCK_RAW = 3
    }
}
