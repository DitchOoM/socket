@file:Suppress("unused") // Loaded at runtime via multi-release JAR (META-INF/versions/21)

package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * FFM (Java 21+ Foreign Function & Memory) bindings to the handful of libc
 * routing-socket calls the reactive desktop-JVM [NetworkMonitor] needs.
 *
 * Only `socket`/`bind`/`recv`/`poll`/`pipe`/`write`/`close` are bound. The actual availability check
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
    private val hPipe = handle("pipe", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val hWrite = handle("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))

    // poll(2) reports EINTR through errno, which a downcall only returns if it is asked to capture it.
    private val errnoState = Linker.Option.captureStateLayout()
    private val errnoOffset =
        errnoState.byteOffset(
            java.lang.foreign.MemoryLayout.PathElement
                .groupElement("errno"),
        )
    private val hPoll =
        linker.downcallHandle(
            lookup.find("poll").orElseThrow { UnsatisfiedLinkError("libc symbol not found: poll") },
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT),
            Linker.Option.captureCallState("errno"),
        )

    /** A segment for [poll] to capture `errno` into. */
    fun allocateErrnoCapture(arena: Arena): MemorySegment = arena.allocate(errnoState)

    /** What the last [poll] on [capture] left in `errno`. */
    fun errno(capture: MemorySegment): Int = capture.get(JAVA_INT, errnoOffset)

    /** Blocks until one of the `struct pollfd`s in [fds] is ready or [timeoutMillis] passes (-1: never). */
    fun poll(
        capture: MemorySegment,
        fds: MemorySegment,
        count: Long,
        timeoutMillis: Int,
    ): Int = hPoll.invokeExact(capture, fds, count, timeoutMillis) as Int

    /** Creates a pipe into the two ints at [ends] (read end first). */
    fun pipe(ends: MemorySegment): Int = hPipe.invokeExact(ends) as Int

    fun write(
        fd: Int,
        buf: MemorySegment,
        len: Long,
    ): Long = hWrite.invokeExact(fd, buf, len) as Long

    const val EINTR = 4

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
 * routing socket via FFM and re-resolve state with [resolveJvmNetworkState].
 *
 * Subclasses only supply the platform routing socket ([openRoutingSocket]); the
 * event loop, threading, and state resolution are shared. The resolution itself lives in
 * `commonJvmMain` as a single `public` function rather than being mirrored here — this compilation is a
 * separate Kotlin module, and a mirrored copy would drift from the original.
 *
 * Lifetime: the recv-loop coroutine owns the native scratch buffer (its own [Arena],
 * closed in `finally` on the coroutine's own thread). It cannot be cancelled mid-downcall, and
 * closing a socket does not wake a thread blocked on it (on Linux a thread parked in `recv` on a
 * descriptor another thread closes stays parked), and `shutdown(2)` is refused by netlink sockets
 * (`ENOTSUP`). So the loop waits in `poll(2)` on the routing socket and on the read end of a pipe, and
 * [close] writes a byte to the pipe: the loop leaves, and the descriptors are closed by whichever of the
 * loop and [close] leaves last, so no descriptor is closed (and its number reused by another thread's
 * `socket()`) while the loop may still name it. The arena is deliberately NOT closed from [close]:
 * closing an arena while a downcall is still using it throws `IllegalStateException: Session is acquired`.
 */
abstract class FfmRoutingSocketNetworkMonitor : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /**
     * One bump per routing-socket wake-up (each successful `recv`). The kernel batches multiple
     * messages into one datagram, so this counts deliveries, not messages — still a faithful density
     * signal, since a flapping link produces many *deliveries*, and re-resolution frequently folds
     * them to an unchanged [NetworkState] that [state] de-dupes away.
     */
    private val observationRelay = ObservationRelay(_state)
    override val observationCount: StateFlow<Long> = observationRelay.count
    override val observations: Flow<NetworkObservation> = observationRelay.observations

    /**
     * The whole point of the FFM subclasses: a blocking read on a routing socket, not a poll — and
     * [ReachResolution.RouteOnly], because the routing socket says which link carries the default route
     * and nothing at all about whether traffic reaches the internet.
     */
    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteOnly)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var listening: Listening = Listening.None

    /** Whether there is a routing socket to listen on. */
    private sealed interface Listening {
        data object None : Listening

        class Open(
            val descriptors: RoutingDescriptors,
        ) : Listening
    }

    /**
     * The routing socket and the pipe that wakes its reader, closed by whichever of the reader and
     * [beginClose] leaves last.
     */
    private class RoutingDescriptors(
        val route: Int,
        val wakeRead: Int,
        private val wakeWrite: Int,
    ) {
        /** The reader, and [beginClose]'s caller. */
        private val parties = AtomicInteger(2)
        private val closeBegun = AtomicBoolean(false)

        /** Wakes the reader to end, once however often it is called. */
        fun beginClose() {
            if (!closeBegun.compareAndSet(false, true)) return
            Arena.ofConfined().use { arena ->
                val wake = arena.allocate(1)
                wake.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, 1)
                Libc.write(wakeWrite, wake, 1)
            }
            leave()
        }

        fun leave() {
            if (parties.decrementAndGet() != 0) return
            Libc.close(route)
            Libc.close(wakeRead)
            Libc.close(wakeWrite)
        }
    }

    /**
     * Opens (and binds, if the protocol requires it) the platform routing socket,
     * allocating any transient native memory in its own short-lived [Arena]. Returns
     * the fd, or a negative value on failure.
     */
    protected abstract fun openRoutingSocket(): Int

    protected fun start() {
        // A synchronous seed, not the network talking: published without advancing the count.
        observationRelay.publish(resolveJvmNetworkState())
        val route = openRoutingSocket()
        if (route < 0) return
        val open =
            Arena.ofConfined().use { arena ->
                val ends = arena.allocate(2L * JAVA_INT.byteSize())
                if (Libc.pipe(ends) < 0) {
                    Libc.close(route)
                    return
                }
                Listening.Open(RoutingDescriptors(route, ends.get(JAVA_INT, 0), ends.get(JAVA_INT, JAVA_INT.byteSize())))
            }
        listening = open
        val descriptors = open.descriptors
        // ATOMIC: the loop must start even if [close] cancels the scope first, because it is a party to the
        // descriptors and the last party out closes them.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                // The scratch buffer's arena is owned by THIS coroutine and closed on its
                // own thread once the recv loop ends — never cross-thread from close().
                // The loop has no suspension point, so the coroutine never hops threads.
                Arena.ofConfined().use { arena ->
                    val scratch = arena.allocate(RECV_BUFFER_SIZE.toLong())
                    // Two `struct pollfd { int fd; short events; short revents; }`: the routing socket, then the pipe.
                    val waits = arena.allocate(2 * POLLFD_SIZE)
                    val errno = Libc.allocateErrnoCapture(arena)
                    try {
                        while (isActive) {
                            waits.set(JAVA_INT, 0, descriptors.route)
                            waits.set(JAVA_SHORT, 4, POLLIN)
                            waits.set(JAVA_SHORT, 6, 0)
                            waits.set(JAVA_INT, POLLFD_SIZE, descriptors.wakeRead)
                            waits.set(JAVA_SHORT, POLLFD_SIZE + 4, POLLIN)
                            waits.set(JAVA_SHORT, POLLFD_SIZE + 6, 0)
                            if (Libc.poll(errno, waits, 2, -1) < 0) {
                                if (Libc.errno(errno) == Libc.EINTR) continue
                                break
                            }
                            // [close] wrote to the pipe.
                            if (waits.get(JAVA_SHORT, POLLFD_SIZE + 6).toInt() != 0) break
                            val n = Libc.recv(descriptors.route, scratch, RECV_BUFFER_SIZE.toLong(), 0)
                            if (n <= 0L) break
                            // One call publishes and counts, so the observation carries the state this
                            // delivery resolved to — including a re-resolution that changed nothing.
                            observationRelay.record(resolveJvmNetworkState())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Socket torn down out from under the blocking wait, or an FFM
                        // downcall failure — stop monitoring; last known state is retained.
                    }
                }
            } finally {
                descriptors.leave()
            }
        }
    }

    override fun close() {
        (listening as? Listening.Open)?.descriptors?.beginClose()
        scope.cancel()
    }

    private companion object {
        private const val RECV_BUFFER_SIZE = 4096
        private const val POLLFD_SIZE = 8L
        private const val POLLIN: Short = 1
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
