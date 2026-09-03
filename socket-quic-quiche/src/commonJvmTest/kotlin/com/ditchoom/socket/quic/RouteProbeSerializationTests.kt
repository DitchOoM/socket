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
import com.ditchoom.socket.udp.MAX_UDP_DATAGRAM_SIZE
import com.ditchoom.socket.udp.SocketAddressCodec
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
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression test for #547: two route probes must never be alive at the same time.
 *
 * ## The mechanism
 *
 * [UdpSocketChannelFactory.routeSourceAddress] learns a path's source address by opening a probe
 * with an **unnamed** bind — it has to, it is learning the address it would otherwise name — and
 * every probe in the process aims at the same `peer.host:9`. Two probes alive at once can therefore
 * draw the same ephemeral port and form the same 4-tuple, and the second `connect` fails with
 * `EADDRINUSE`: measured at 4–5 lost per 32 000 with 64 threads probing back to back. Since #523 a
 * lost draw is `RouteProbeFailure.ProbeRefused` and fails the open — a migration retries, a connect
 * (#519) does not.
 *
 * A probe holds its socket for microseconds and sends nothing, so the fix is not to retry the draw
 * (a second draw can collide too, at p²) but to make the collision impossible: probes run one at a
 * time, process-wide. Socket 1 is closed before socket 2 binds, so the kernel is free to hand the
 * same ephemeral port out again and nothing is in the way.
 *
 * ## Why this is measured as overlap and not as refusals
 *
 * The refusal rate is 1-in-thousands and needs a peer holding hundreds of sockets to reach even
 * that, so a test asserting "0 refused" at a runnable size passes against the defect by luck. The
 * single mechanism is *overlap* — a second probe open while a first is still open — and that is
 * countable exactly through the opener seam: the scripted opener below counts probes alive across
 * its `open`/`close` and remembers the high-water mark. 64 coroutines each probing 8 times, with a
 * suspension inside every probe window so the dispatcher has every chance to overlap them, must
 * report a high-water mark of exactly 1. Against `main` it reports tens.
 *
 * [aRealProbeStormLosesNoDraws] is the end-to-end measurement the issue made, kept small enough
 * for the ordinary suite and raised with `HUNT547_PROBES` when hunting; on its own it is the
 * instrument, not the regression guard.
 */
class RouteProbeSerializationTests {
    /**
     * Counts probes alive between `open` and `close`, and the most that were ever alive at once.
     * `delay` inside the window is what turns "could overlap" into "will overlap" on a
     * multi-threaded dispatcher, so the unpatched factory cannot pass by scheduling luck.
     */
    private class OverlapCountingOpener(
        private val peerPort: Int,
    ) : ConnectedUdpOpener {
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
    fun concurrentRouteProbesNeverOverlap() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = OverlapCountingOpener(peer.port)
            val factory = factoryFor(peer, opener)

            val sources =
                withContext(Dispatchers.Default) {
                    (1..PROBERS)
                        .map {
                            async {
                                (1..PROBES_EACH).map { factory.routeSourceAddress() }
                            }
                        }.awaitAll()
                        .flatten()
                }

            assertEquals(PROBERS * PROBES_EACH, opener.probes.get(), "every call must have probed")
            sources.forEach { assertIs<RouteSource.Resolved>(it) }
            assertEquals(
                1,
                opener.highWater.get(),
                "${opener.highWater.get()} route probes were alive at once (#547): two probes alive at the same " +
                    "instant draw from the same ephemeral range against the same peer.host:9 and can form the " +
                    "same 4-tuple, which fails the second connect with EADDRINUSE and fails the open. A probe " +
                    "sends nothing and lives for microseconds; they must run one at a time.",
            )
        }

    /**
     * The measurement behind the issue, as a runnable arm: real probes, real sockets, against a
     * peer address on loopback. [PROBERS] coroutines each probe [realProbesEach] times back to back.
     * Every draw must resolve; a `ProbeRefused` is the collision. Raise the count to hunt:
     *
     * ```
     * HUNT547_PROBES=32000 ./gradlew :socket-quic-quiche:jvmTest \
     *   --tests 'com.ditchoom.socket.quic.RouteProbeSerializationTests' --rerun
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
                    lost.take(3).joinToString { it.reason.describe() },
            )
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
        receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
        localEndpointSupport = LocalEndpointSupport.Bindable,
        openChannel = opener,
    )

    private companion object {
        /** Not the probe's own port (9), so the opener can tell the two apart. */
        const val PEER_PORT = 9999

        /** The issue's own shape: 64 threads probing back to back. */
        const val PROBERS = 64
        const val PROBES_EACH = 8

        /** Long enough that two probes scheduled at once are certainly both inside it. */
        val PROBE_WINDOW = 2.milliseconds

        /** Small enough for the ordinary suite; the hunt knob raises it. */
        const val DEFAULT_REAL_PROBES = 512
    }
}
