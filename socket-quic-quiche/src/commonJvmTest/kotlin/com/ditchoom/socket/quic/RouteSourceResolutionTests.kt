@file:OptIn(ExperimentalDatagramApi::class, InternalQuicApi::class)

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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A route source that cannot be resolved is an answer, not a `null` that binds the wildcard (#523).
 *
 * [UdpSocketChannelFactory.routeSourceAddress] used to catch every exception and return `null`, and
 * `openPath` read `null` as "bind the wildcard" — the exact configuration #434 exists to remove, and
 * the defect #482 named: *a silent fallback to the known-broken path is the bug*. #483 removed the one
 * trigger that fired in practice (the probe drawing against the peer's own paths, 127/2000 → 0/6000),
 * leaving the shape: any other reason the probe fails — no descriptors, a sandbox denying the socket,
 * a routing change mid-run — still reverted to the unnamed bind with nobody told.
 *
 * These drive each way the probe can fail and assert what `openPath` does about it. They need a seam
 * ([ConnectedUdpOpener]) because none of those conditions can be provoked on a real host on demand,
 * and a decision no test can drive is how #482 lived unnoticed under a green suite. The seam is
 * one function and the fake serves the *path* socket for real, so the assertions below are about the
 * decision and nothing else: the path connect that the wildcard fallback used to reach still works,
 * and is still refused.
 *
 * The sibling contention guards for #434/#482 live in [MigrationPathSourceAddressTests] and use real
 * sockets throughout.
 */
class RouteSourceResolutionTests {
    /**
     * The behavioural gate. With the probe refused and the real connect fully serviceable, `openPath`
     * must fail with the typed reason rather than open the path with an unnamed bind.
     *
     * RED against the swallowed `null`: `openPath` returns a `NewPath` bound to whatever the wildcard
     * drew, and the only trace of the refusal is that it happened.
     */
    @Test
    fun aRefusedRouteProbeFailsThePathOpenRatherThanBindingTheWildcard() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val refusal = IllegalStateException("no descriptor left for the route probe")
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Refuse(refusal))
            val factory = factoryFor(peer, opener)

            val opened = runCatching { factory.openPath(null, 0) }
            opened.getOrNull()?.let { path ->
                runCatching { path.channel.close() }
                runCatching { path.release() }
                fail(
                    "openPath bound ${path.localEndpoint} after the route probe was refused with " +
                        "\"${refusal.message}\": it asked for localHost=${opener.pathBinds.single()}, the " +
                        "unnamed bind #434 removed, and told the caller nothing about why",
                )
            }
            val failure = assertIs<UnresolvedRouteSourceException>(opened.exceptionOrNull())
            val reason = assertIs<RouteProbeFailure.ProbeRefused>(failure.reason)
            assertSame(refusal, reason.cause, "the platform's own refusal must survive as the cause")
            assertEquals(
                emptyList(),
                opener.pathBinds,
                "no path socket may be opened once the route is unresolved — a wildcard bind that " +
                    "happens to succeed is the #434 collision waiting for its next migration",
            )
        }

    /**
     * The positive control, through the same seam: with the probe served for real, the route resolves
     * and the path binds **that address** — the #434 fix doing its job. Without this the suite would
     * only ever prove that failures fail.
     */
    @Test
    fun aServedRouteProbeResolvesTheSourceThePathThenBinds() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Serve)
            val factory = factoryFor(peer, opener)

            assertEquals(RouteSource.Resolved("127.0.0.1"), factory.routeSourceAddress())

            val path = factory.openPath(null, 0)
            try {
                assertEquals(
                    listOf<String?>("127.0.0.1"),
                    opener.pathBinds,
                    "the path must bind the address the route resolved, never the unnamed default",
                )
                assertEquals("127.0.0.1", path.localEndpoint.host)
            } finally {
                runCatching { path.channel.close() }
                runCatching { path.release() }
            }
        }

    /**
     * A probe that connects but cannot say where from is unresolved, not "resolved to nothing".
     *
     * Both backends that run the probe do report it (NIO `getLocalAddress`, io_uring `getsockname`), so
     * this is the state where a platform that advertised [LocalEndpointSupport.Bindable] contradicts
     * itself — and the old code read that contradiction as permission to bind the wildcard.
     */
    @Test
    fun aProbeThatReportsNoLocalAddressIsUnresolved() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Report(LocalAddress.Unknown))

            val source = factoryFor(peer, opener).routeSourceAddress()

            assertEquals(RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown), source)
            assertTrue(
                opener.probeChannels.single().closed,
                "the probe socket must be closed whatever it reported — it must never be one of the " +
                    "sockets the path then draws against",
            )
        }

    /**
     * A probe that reports the **wildcard** is unresolved: binding that is the configuration #434
     * removed, so accepting it as an answer would restore the defect while looking like the fix.
     *
     * Measured on macOS/JDK 21, a wildcard-bound `DatagramChannel` reports `0:0:0:0:0:0:0:0` *before*
     * `connect` and the route's own address after it, so no real backend reaches this today — which is
     * exactly why it takes a seam to state the rule, and one comparison to keep it true.
     */
    @Test
    fun aProbeThatReportsTheWildcardIsUnresolved() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            for (wildcard in listOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0")) {
                val reported = SocketAddress.ofLiteral(wildcard, 54321)
                val opener = ScriptedOpener(peer.port, ProbeAnswer.Report(LocalAddress.of(reported)))

                val source = factoryFor(peer, opener).routeSourceAddress()

                assertEquals(
                    RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnnamed(reported.host)),
                    source,
                    "a probe reporting $wildcard names no interface, so binding it is the unnamed bind",
                )
            }
        }

    /**
     * A caller that names its own source is served exactly as before — and no probe is taken on its
     * behalf, so a host that cannot probe at all can still be told precisely where to bind. A
     * collision on an *explicitly requested* endpoint stays the honest terminal failure it always was.
     */
    @Test
    fun aCallerThatNamesASourceIsServedWithoutAProbe() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Refuse(IllegalStateException("no probe here")))
            val path = factoryFor(peer, opener).openPath("127.0.0.1", 0)
            try {
                assertEquals(emptyList(), opener.probes, "a named source needs no route probe")
                assertEquals(listOf<String?>("127.0.0.1"), opener.pathBinds)
            } finally {
                runCatching { path.channel.close() }
                runCatching { path.release() }
            }
        }

    /**
     * The one unnamed bind that is *correct*: on [LocalEndpointSupport.PlatformAssigned] the platform
     * names the endpoint (Apple hands it to `NWConnection`), so there is nothing to resolve, nothing to
     * probe, and `null` is the request the platform serves rather than a degradation.
     *
     * This is the branch the old `null` could not be told apart from, and it is why the failure above
     * is a member of its own rather than the same absence.
     */
    @Test
    fun aPlatformThatAssignsTheEndpointBindsUnnamedAndNeverProbes() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Refuse(IllegalStateException("no probe here")))
            val factory =
                UdpSocketChannelFactory(
                    peer = peer,
                    codec = SocketAddressCodec(hostOsSockAddrLayout()),
                    bufferFactory = BufferFactory.Default,
                    recvBufferFactory = BufferFactory.Default,
                    receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
                    localEndpointSupport = LocalEndpointSupport.PlatformAssigned,
                    openChannel = opener,
                )

            assertEquals(RouteSource.PlatformAssigned, factory.routeSourceAddress())

            val path = factory.openPath(null, 0)
            try {
                assertEquals(emptyList(), opener.probes, "a platform-assigned endpoint is not probed for")
                assertEquals(
                    listOf<String?>(null),
                    opener.pathBinds,
                    "the platform picks the endpoint, so the path asks for none",
                )
            } finally {
                runCatching { path.channel.close() }
                runCatching { path.release() }
            }
        }

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

    /** What the scripted opener does with the route probe. The path socket is always served for real. */
    private sealed interface ProbeAnswer {
        /** `UdpSocket.connect` refuses the probe — no descriptors, a sandbox, no route. */
        data class Refuse(
            val cause: Throwable,
        ) : ProbeAnswer

        /** The probe connects and reports [localAddress] — including the states no real host offers. */
        data class Report(
            val localAddress: LocalAddress,
        ) : ProbeAnswer

        /** The probe is served by the platform, exactly as in production. */
        data object Serve : ProbeAnswer
    }

    /**
     * Serves the migration path socket for real and scripts only the route probe, telling them apart by
     * destination port: the probe never goes to [peerPort] (that is #483's fix), and the path always
     * does. So a refusal here is a refusal of the probe alone, and the path connect the wildcard
     * fallback used to reach is genuinely available for `openPath` to take.
     */
    private class ScriptedOpener(
        private val peerPort: Int,
        private val probeAnswer: ProbeAnswer,
    ) : ConnectedUdpOpener {
        /** Destination ports the route probe was pointed at, in order. */
        val probes = mutableListOf<Int>()

        /** The `localHost` each *path* open asked to bind, in order — `null` is the unnamed bind. */
        val pathBinds = mutableListOf<String?>()

        /** Probe channels handed out, so a test can assert the probe was closed. */
        val probeChannels = mutableListOf<ScriptedChannel>()

        override suspend fun open(
            remoteHost: String,
            remotePort: Int,
            localHost: String?,
            localPort: Int,
            receiveBufferSize: Int,
            bufferFactory: BufferFactory,
        ): ConnectedDatagramChannel {
            if (remotePort == peerPort) {
                pathBinds += localHost
                return ConnectedUdpOpener.Platform.open(
                    remoteHost,
                    remotePort,
                    localHost,
                    localPort,
                    receiveBufferSize,
                    bufferFactory,
                )
            }
            probes += remotePort
            return when (probeAnswer) {
                is ProbeAnswer.Refuse -> throw probeAnswer.cause
                is ProbeAnswer.Report ->
                    ScriptedChannel(SocketAddress.ofLiteral(remoteHost, remotePort), probeAnswer.localAddress)
                        .also { probeChannels += it }

                ProbeAnswer.Serve ->
                    ConnectedUdpOpener.Platform.open(
                        remoteHost,
                        remotePort,
                        localHost,
                        localPort,
                        receiveBufferSize,
                        bufferFactory,
                    )
            }
        }
    }

    /** A connected channel that only answers [localAddress] — the one thing the route probe reads. */
    private class ScriptedChannel(
        override val peer: SocketAddress,
        override val localAddress: LocalAddress,
    ) : ConnectedDatagramChannel {
        var closed = false
            private set

        override val isOpen: Boolean get() = !closed
        override val capabilities: DatagramCapabilities = DatagramCapabilities.None
        override val maxWritableSize: Int = MAX_UDP_DATAGRAM_SIZE

        override suspend fun receive(): DatagramReadResult = DatagramReadResult.Closed()

        override suspend fun send(
            payload: ReadBuffer,
            options: DatagramSendOptions,
        ) = fail("the route probe must never send: a connected UDP socket fixes the 4-tuple and nothing else")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /**
         * The peer's port. Not the probe's own (9, RFC 863 discard) so the scripted opener can tell the
         * two apart, and not a port anything answers on — a connected UDP socket sends nothing.
         */
        private const val PEER_PORT = 9999
    }
}
