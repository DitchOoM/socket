@file:OptIn(ExperimentalDatagramApi::class, InternalQuicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.fail

/**
 * The **primary** path of a QUIC connection binds the route's source address, like every path after it.
 *
 * #434 removed the unnamed bind from migration paths, #483 stopped the route probe contending with them
 * and #523 made an unresolved route a typed refusal rather than a silent return to the wildcard. None of
 * that reached the socket the connection actually starts on: `buildJvmQuicConnection` opened it with
 * `UdpSocket.connect(peer)` and no local endpoint — `bind([::], 0)` then `connect(peer)` — behind an
 * 8-deep `BindException` retry whose KDoc blamed a race between concurrent `bind(0)` calls.
 *
 * It is not a race. It is #434's mechanism, single-threaded and deterministic: `udp6_bind([::]:0)` picks
 * the port through `in6_pcblookup_local`, which skips every pcb without `INP_IPV6`, and `udp6_connect`
 * to a v4-mapped peer clears `INP_IPV6` on success — so every socket already connected to that peer is
 * invisible to the next wildcard bind, and `in_pcbconnect` then finds the exact 4-tuple and answers
 * `EADDRINUSE`, which the JDK raises as `java.net.BindException` from `Net.connect0`.
 *
 * Measured on this machine (macOS 26.6, JDK 21), single-threaded, sockets held to one peer, replicating
 * `UdpSocket.connect`'s exact NIO shape:
 *
 * | held | draws | `bind0` | `connect0`, unnamed bind | `connect0`, route-source bind |
 * |---|---|---|---|---|
 * | 250 | 2000 | 0 | **29** | **0** |
 * | 1000 | 2000 | 0 | **120** | **0** |
 *
 * The primary path is also the one that carries the collision longest: a migration path is retired, the
 * primary socket is held for the connection's life, so every later draw in the process — including this
 * connection's own migrations — contends with it.
 *
 * The sibling suites are [MigrationPathSourceAddressTests] (the same contention, for paths) and
 * [RouteSourceResolutionTests] (the same decision, for paths); the seam below is shared with the latter
 * because both are asking [UdpSocketChannelFactory] one question.
 */
class PrimaryPathSourceAddressTests {
    /**
     * The primary path must ask to bind the address the route resolved, never the unnamed default.
     *
     * RED against the pre-#519 primary open: it asks for `localHost=null`, which is the configuration
     * every other socket this factory opens stopped using in #434.
     */
    @Test
    fun thePrimaryPathBindsTheRouteSourceAddressAndNotTheWildcard() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedConnectedUdpOpener(peer.port, ProbeAnswer.Serve)
            val channel = factoryFor(peer, opener).openPrimaryChannel()
            try {
                assertEquals(
                    listOf<String?>("127.0.0.1"),
                    opener.pathBinds,
                    "the connection's first path must bind the route's source address, exactly as every " +
                        "migration path has since #434 — `null` here is the unnamed bind that fix removed",
                )
                assertEquals("127.0.0.1", channel.localAddress.orNull()?.host)
                assertEquals(listOf(DISCARD_PORT), opener.probes, "one route probe, stepped aside from the peer's port")
            } finally {
                runCatching { channel.close() }
            }
        }

    /**
     * **The design call, and it is not #523's by inheritance.**
     *
     * For a migration path, refusing costs the caller nothing it had: the connection keeps living where
     * it is and `AutoMigrationWiring` already retries `LocalPathUnavailable`. A connect has no such
     * fallback position — refusing it means the caller gets no connection at all — so the question has
     * to be answered again here rather than assumed.
     *
     * It comes out the same way, for a stronger reason: **the unnamed bind does not buy a connection
     * either.** Every member of [RouteProbeFailure], reached from the primary path, is a state in which
     * the fallback produces a failure or a broken connection, not a working one:
     *
     * - [RouteProbeFailure.ProbeRefused] — the probe and the real connect are the same call to the same
     *   address, differing only in destination port, and a connected UDP socket sends nothing, so the
     *   port cannot change reachability. No descriptor, a sandbox, no route: all three refuse both. The
     *   one refusal that is a *draw* rather than the environment is `EADDRINUSE`, and #483 made the
     *   probe's draw disjoint from the peer's paths (measured 0 in 6000 by #522).
     * - [RouteProbeFailure.SourceAddressUnknown] — the platform cannot say what it bound. The primary
     *   open already fails on exactly this two statements later (`connected UDP channel has no local
     *   address`), because quiche needs that address. Refusing at the probe is the same failure, named
     *   better and one socket earlier.
     * - [RouteProbeFailure.SourceAddressUnnamed] — the wildcard would be encoded into `quiche_connect`'s
     *   local sockaddr and into `recv_info.to` for the connection's whole life. quiche matches paths on
     *   that 4-tuple, so what the fallback returns there is not a degraded connection, it is a broken
     *   one that reports success.
     *
     * So the fallback trades a typed refusal for an untyped one arriving later, which is what #482
     * named as the defect. It also, unlike a migration path, cannot be corrected by anything upstream:
     * `SocketConnectionException` reaches the application, which can retry a connect; a wildcard-bound
     * primary socket reaches the application looking healthy.
     *
     * RED against the pre-#519 primary open, which asks for `localHost=null` unconditionally and never
     * calls [UdpSocketChannelFactory.routeSourceAddress] at all.
     */
    @Test
    fun aRefusedRouteProbeFailsTheConnectRatherThanBindingTheWildcard() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val refusal = IllegalStateException("no descriptor left for the route probe")
            val opener = ScriptedConnectedUdpOpener(peer.port, ProbeAnswer.Refuse(refusal))

            val opened = runCatching { factoryFor(peer, opener).openPrimaryChannel() }
            opened.getOrNull()?.let { channel ->
                val bound = channel.localAddress.orNull()
                runCatching { channel.close() }
                fail(
                    "the primary path opened on $bound after the route probe was refused with " +
                        "\"${refusal.message}\": it asked for localHost=${opener.pathBinds.single()}, the " +
                        "unnamed bind #434 removed, and told the caller nothing about why",
                )
            }
            val failure = assertIs<UnresolvedRouteSourceException>(opened.exceptionOrNull())
            assertSame(
                refusal,
                assertIs<RouteProbeFailure.ProbeRefused>(failure.reason).cause,
                "the platform's own refusal must survive as the cause",
            )
            assertEquals(
                emptyList(),
                opener.pathBinds,
                "no primary socket may be opened once the route is unresolved — a wildcard bind that " +
                    "happens to succeed is a connection built on a port whose 4-tuple nobody checked",
            )
        }

    /**
     * The one unnamed bind that is *correct*, and the reason the primary path could be routed through
     * this factory at all: on [LocalEndpointSupport.PlatformAssigned] the platform names the endpoint
     * (Apple hands it to `NWConnection`), so there is nothing to resolve and nothing to probe.
     *
     * This is what keeps the Apple client byte-identical across #519: `null` there is the request the
     * platform serves, not a degradation.
     */
    @Test
    fun aPlatformThatAssignsTheEndpointOpensThePrimaryPathUnnamedAndNeverProbes() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", PEER_PORT)
            val opener = ScriptedConnectedUdpOpener(peer.port, ProbeAnswer.Refuse(IllegalStateException("no probe here")))
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

            val channel = factory.openPrimaryChannel()
            try {
                assertEquals(emptyList(), opener.probes, "a platform-assigned endpoint is not probed for")
                assertEquals(
                    listOf<String?>(null),
                    opener.pathBinds,
                    "the platform picks the endpoint, so the primary path asks for none",
                )
            } finally {
                runCatching { channel.close() }
            }
        }

    /**
     * The mechanism measurement, on real sockets: a process already holding [HELD] sockets to a peer
     * opens the primary path of another connection to it, [DRAWS] times, and loses no draw.
     *
     * This is the shape #519 describes — a process holding many connections to one server pays the
     * collision on every new connection — and it has to be measured here rather than through a
     * handshake, because an end-to-end test cannot see it: the 8-deep retry this change deletes turns a
     * per-draw probability of `p` into `p^8`, so at [HELD] the old code fails an observable connect
     * about once in 10^15. The draw itself fails about **29 times in [DRAWS]** (measured), which is a
     * gate. It is the same instrument #483 needed for the probe, pointed one level down.
     *
     * The contended set is built with [UdpChannelFactory.openPath], already bound to the route's source
     * since #434, so building it cannot itself lose a draw and every failure reported below is the
     * primary open's own.
     *
     * Mutation-proven: restore the primary open to `localHost = null` and this reports ~29 lost draws
     * while [MigrationPathSourceAddressTests] and [RouteSourceResolutionTests] stay green.
     */
    @Test
    fun thePrimaryPathNeverLosesADrawToTheSocketsAlreadyHeldToThePeer() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", UNUSED_PORT)
            val factory = factoryFor(peer, ConnectedUdpOpener.Platform)

            val held = ArrayList<NewPath>(HELD)
            try {
                repeat(HELD) { held += factory.openPath(null, 0) }

                val lost = ArrayList<String>()
                repeat(DRAWS) {
                    val drawn = runCatching { factory.openPrimaryChannel() }
                    drawn.getOrNull()?.let { runCatching { it.close() } }
                    drawn.exceptionOrNull()?.let { lost += it.toString() }
                }
                assertEquals(
                    0,
                    lost.size,
                    "the primary path lost ${lost.size} of $DRAWS draws against the $HELD sockets this " +
                        "process already holds to the peer: ${lost.distinct()}",
                )
            } finally {
                for (path in held) {
                    runCatching { path.channel.close() }
                    runCatching { path.release() }
                }
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

    private companion object {
        /**
         * The peer's port for the scripted suites. Not the probe's own so the opener can tell the two
         * apart, and not a port anything answers on — a connected UDP socket sends nothing.
         */
        private const val PEER_PORT = 9999

        /** Same, for the contention suite: a peer port that is not the probe's own. */
        private const val UNUSED_PORT = 9998

        /** RFC 863 discard — where [UdpSocketChannelFactory] points the route probe. */
        private const val DISCARD_PORT = 9

        /** Sockets held to the peer while drawing. At 250, an unnamed draw loses ~1.5% of the time. */
        private const val HELD = 250

        /** Draws per run: at [HELD] the unnamed primary open loses ~29 of these, so zero is a real gate. */
        private const val DRAWS = 2000
    }
}
