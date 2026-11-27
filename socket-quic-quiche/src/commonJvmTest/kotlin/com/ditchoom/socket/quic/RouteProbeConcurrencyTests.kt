@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.LocalAddress
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.ERRNO_NOT_SURFACED
import com.ditchoom.socket.udp.MAX_UDP_DATAGRAM_SIZE
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpConnectError
import com.ditchoom.socket.udp.UdpConnectException
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/** The receive buffer the probes' sockets are opened with: one datagram. */
private const val RECEIVE_BUFFER_SIZE = 1350

/**
 * Concurrent route probes never share a 4-tuple, without a lock.
 *
 * Every probe binds unnamed and aims at `peer.host:port`; two alive at once that aim at the same port
 * can draw the same ephemeral source port and fail the second `connect` with `EADDRINUSE`. Each probe
 * therefore takes its own [RouteProbePort], so probes alive together are distinct by construction.
 * The scripted opener records the port of every probe alive at once; the real-socket arms run
 * hundreds of probes in parallel and require every one to resolve.
 */
class RouteProbeConcurrencyTests {
    /**
     * Records the destination port of every probe. `delay` inside the window keeps the probes alive
     * together on a multi-threaded dispatcher.
     */
    private class OverlapCountingOpener(
        private val peerPort: Int,
    ) : ConnectedUdpOpener {
        val ports: MutableSet<Int> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()
        private val alive = AtomicInteger(0)
        val highWater = AtomicInteger(0)
        val probes = AtomicInteger(0)

        override suspend fun open(
            remoteHost: String,
            remotePort: Int,
            localHost: String?,
            localPort: Int,
            receiveBufferSize: Int,
            bufferFactory: BufferFactory,
        ): ConnectedDatagramChannel {
            if (remotePort == peerPort) fail("only the route probe is expected through this opener")
            probes.incrementAndGet()
            ports += remotePort
            val now = alive.incrementAndGet()
            highWater.accumulateAndGet(now, ::maxOf)
            delay(PROBE_WINDOW)
            return CountedProbeChannel(SocketAddress.ofLiteral(remoteHost, remotePort)) { alive.decrementAndGet() }
        }
    }

    private class CountedProbeChannel(
        override val peer: SocketAddress,
        private val onClose: () -> Unit,
    ) : ConnectedDatagramChannel {
        private var closed = false
        override val isOpen: Boolean get() = !closed
        override val localAddress: LocalAddress = LocalAddress.of(SocketAddress.ofLiteral("127.0.0.1", 40000))
        override val capabilities: DatagramCapabilities = DatagramCapabilities.None
        override val maxWritableSize: Int = MAX_UDP_DATAGRAM_SIZE

        override suspend fun receive(): DatagramReadResult = DatagramReadResult.Closed()

        override suspend fun send(
            payload: ReadBuffer,
            options: DatagramSendOptions,
        ) = fail("the route probe must never send")

        override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }

    @Test
    fun probesAliveTogetherAimAtDistinctPortsNeverThePeers() =
        runBlocking {
            for (peerPort in listOf(PEER_PORT, 9, 20_000, 20_007, 24_096)) {
                val peer = UdpSocket.resolve("127.0.0.1", peerPort)
                val opener = OverlapCountingOpener(peer.port)
                val factory = factoryFor(peer, opener)

                val sources =
                    withContext(Dispatchers.Default) {
                        (1..PROBERS).map { async { factory.routeSourceAddress() } }.awaitAll()
                    }

                sources.forEach { assertIs<RouteSource.Resolved>(it) }
                assertEquals(PROBERS, opener.probes.get())
                assertEquals(PROBERS, opener.ports.size, "probes alive together shared a destination port (peer $peerPort)")
                assertTrue(peerPort !in opener.ports, "a probe aimed at the peer's own port $peerPort")
            }
        }

    @Test
    fun theProbePortRotationNeverYieldsThePeersPortAndIsOneToOne() {
        for (avoiding in listOf(9, 19_999, 20_000, 20_001, 22_000, 24_095, 24_096, 24_097)) {
            val ports = (1..RouteProbePort.SPAN).map { RouteProbePort.next(avoiding).number }
            assertTrue(avoiding !in ports, "port $avoiding was handed out against itself")
            assertEquals(ports.size, ports.toSet().size, "one rotation repeated a port (avoiding $avoiding)")
        }
    }

    @Test
    fun manyConcurrentRealProbesAllResolveOnLoopback() =
        runBlocking {
            for (host in listOf("127.0.0.1", "::1")) {
                val peer = UdpSocket.resolve(host, PEER_PORT)
                val factory = factoryFor(peer, ConnectedUdpOpener.Platform)
                val outcomes =
                    withContext(Dispatchers.Default) {
                        (1..CONCURRENT_REAL).map { async { factory.routeSourceAddress() } }.awaitAll()
                    }
                val lost = outcomes.filterIsInstance<RouteSource.Unresolved>()
                assertEquals(
                    emptyList(),
                    lost,
                    "$host: ${lost.size} of ${outcomes.size} probes failed: " + lost.take(3).joinToString { it.reason.describe() } +
                        lost.take(1).joinToString { it.refusalTrace() },
                )
            }
        }

    /**
     * The measurement behind the issue, as a runnable arm: real probes, real sockets, against a
     * peer address on loopback. [PROBERS] coroutines each probe [realProbesEach] times back to back.
     * Every draw must resolve; a `ProbeRefused` is the collision. Raise the count to hunt:
     *
     * ```
     * HUNT547_PROBES=32000 ./gradlew :socket-quic-quiche:jvmTest \
     *   --tests 'com.ditchoom.socket.quic.RouteProbeConcurrencyTests' --rerun
     * ```
     */
    @Test
    fun aRealProbeStormLosesNoDraws() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val factory = factoryFor(peer, ConnectedUdpOpener.Platform)
            val each = realProbesEach()

            val outcomes =
                withContext(Dispatchers.Default) {
                    (1..PROBERS)
                        .map {
                            async { (1..each).map { factory.routeSourceAddress() } }
                        }.awaitAll()
                        .flatten()
                }

            val lost = outcomes.filterIsInstance<RouteSource.Unresolved>()
            assertEquals(
                emptyList(),
                lost,
                "${lost.size} of ${outcomes.size} route probes lost their draw (#547): " +
                    lost.take(3).joinToString { it.reason.describe() } + lost.take(1).joinToString { it.refusalTrace() + udpTable() },
            )
        }

    /**
     * On Windows, how many UDP endpoints this process and the host hold, and the excluded port ranges:
     * whether a `WSAENOBUFS` refusal was the dynamic port range running out or something else. Read only
     * after a loss.
     */
    private fun udpTable(): String {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return ""
        val pid = ProcessHandle.current().pid().toString()
        val endpoints = run("netstat", "-ano", "-p", "UDP").lines().filter { it.trim().startsWith("UDP") }
        val ours = endpoints.count { it.trim().split(Regex("\\s+")).lastOrNull() == pid }
        return "\nUDP endpoints: this process $ours, host ${endpoints.size}\n" +
            run("netsh", "int", "ipv4", "show", "dynamicport", "udp") +
            run("netsh", "int", "ipv4", "show", "excludedportrange", "udp")
    }

    private fun run(vararg command: String): String =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            output
        }.getOrElse { "${command.joinToString(" ")} failed: $it\n" }

    /** The first refusal's whole chain, the JDK's exception and the `Net` frame that raised it included. */
    private fun RouteSource.Unresolved.refusalTrace(): String =
        when (reason) {
            is RouteProbeFailure.ProbeRefused -> "\n" + reason.cause.stackTraceToString()
            is RouteProbeFailure.SourceAddressUnknown, is RouteProbeFailure.SourceAddressUnnamed -> ""
        }

    /** Refuses the first [refusals] probes with [refusal], then serves. */
    private class RefusingOpener(
        private val refusals: Int,
        private val refusal: () -> Exception,
    ) : ConnectedUdpOpener {
        var calls = 0

        override suspend fun open(
            remoteHost: String,
            remotePort: Int,
            localHost: String?,
            localPort: Int,
            receiveBufferSize: Int,
            bufferFactory: BufferFactory,
        ): ConnectedDatagramChannel {
            calls++
            if (calls <= refusals) throw refusal()
            return CountedProbeChannel(SocketAddress.ofLiteral(remoteHost, remotePort)) {}
        }
    }

    private fun collision() = UdpConnectException(UdpConnectError.AddressInUse(ERRNO_NOT_SURFACED))

    /**
     * A probe's 4-tuple collides only with another process's socket on `peer.host:port`, a holder that
     * lives for microseconds, so a collided probe draws again rather than failing the open.
     */
    @Test
    fun aProbeThatCollidesIsRedrawn() =
        runBlocking {
            val opener = RefusingOpener(refusals = 2, refusal = ::collision)
            val source = factoryFor(UdpSocket.resolve("127.0.0.1", PEER_PORT), opener).routeSourceAddress()

            assertIs<RouteSource.Resolved>(source, "two collided draws then a free one must resolve; got $source")
            assertEquals(3, opener.calls)
        }

    /** The redraw is bounded, and the refusal it ends in carries every collision. */
    @Test
    fun aProbeThatCollidesOnEveryDrawIsRefusedCarryingEveryCollision() =
        runBlocking {
            val opener = RefusingOpener(refusals = Int.MAX_VALUE, refusal = ::collision)
            val source = factoryFor(UdpSocket.resolve("127.0.0.1", PEER_PORT), opener).routeSourceAddress()

            val refused = assertIs<RouteProbeFailure.ProbeRefused>(assertIs<RouteSource.Unresolved>(source).reason)
            val last = assertIs<UdpConnectException>(refused.cause)
            assertEquals(opener.calls, 1 + last.suppressed.size, "every collision must ride along")
            assertTrue(opener.calls in 2..8, "a collision must be redrawn, a bounded number of times; made ${opener.calls} draws")
        }

    /** Every other refusal is the environment — no descriptor, a sandbox, no route — and is not redrawn. */
    @Test
    fun aRefusalThatIsNotACollisionIsNotRedrawn() =
        runBlocking {
            val opener = RefusingOpener(refusals = 1) { UdpConnectException(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED)) }
            val source = factoryFor(UdpSocket.resolve("127.0.0.1", PEER_PORT), opener).routeSourceAddress()

            assertIs<RouteProbeFailure.ProbeRefused>(assertIs<RouteSource.Unresolved>(source).reason)
            assertEquals(1, opener.calls)
        }

    private fun realProbesEach(): Int = (System.getenv("HUNT547_PROBES")?.toIntOrNull() ?: DEFAULT_REAL_PROBES) / PROBERS

    private fun factoryFor(
        peer: SocketAddress,
        opener: ConnectedUdpOpener,
    ) = UdpSocketChannelFactory(
        peer = peer,
        codec = SocketAddressCodec(hostOsSockAddrLayout()),
        bufferFactory = BufferFactory.Default,
        recvBufferFactory = BufferFactory.Default,
        receiveBufferSize = RECEIVE_BUFFER_SIZE,
        localEndpointSupport = LocalEndpointSupport.Bindable,
        openChannel = opener,
    )

    private companion object {
        /** Not the probe's own port (9), so the opener can tell the two apart. */
        const val PEER_PORT = 9999

        /** The issue's own shape: 64 threads probing back to back. */
        const val PROBERS = 64
        const val CONCURRENT_REAL = 256

        /** Long enough that two probes scheduled at once are certainly both inside it. */
        val PROBE_WINDOW = 2.milliseconds

        /** Small enough for the ordinary suite; the hunt knob raises it. */
        const val DEFAULT_REAL_PROBES = 512
    }
}
