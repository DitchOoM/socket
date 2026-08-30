@file:OptIn(ExperimentalDatagramApi::class, InternalQuicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
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
 * The connection's **primary** path binds a named source address, exactly as every migration path does
 * (#519).
 *
 * #434 removed the unnamed bind from migration paths and measured why: `bind([::], 0)` chooses the
 * ephemeral port knowing only the local side, so the kernel can return one whose full 4-tuple against
 * the peer is already held. Every client on this backend reached its *first* socket a different way —
 * a bare `UdpSocket.connect(peer)` in `CommonJvmWithQuicConnection`, which is that same unnamed bind —
 * behind an 8-attempt `BindException` retry whose KDoc explained the collision as a race between
 * concurrent binds.
 *
 * It is not a race. Read out of XNU while hunting #463: `udp6_bind([::]:0)` picks its port through
 * `in6_pcblookup_local`, which skips every pcb without `INP_IPV6`, and `udp6_connect` to a v4-mapped
 * peer sets `INP_IPV4` and clears `INP_IPV6` on success. So every socket already connected to that peer
 * is invisible to the next wildcard bind, `in_pcbconnect` then finds the exact 4-tuple, and the refusal
 * arrives from `connect` as `EADDRINUSE`. Single-threaded, sequential, macOS 26.6 / JDK 21, sockets held
 * to one peer:
 *
 * | approach | held | `EADDRINUSE` at `connect` |
 * |---|---|---|
 * | `bind([::],0)` + `connect` — what the primary path did | 3000 | **264** |
 * | `bind(127.0.0.1,0)` + `connect` — what `openPath` does since #434 | 3000 | 0 |
 *
 * Per draw the odds are `k / 16384` with `k` sockets already connected to the same peer, so a process
 * holding many connections to one server pays it on every new connection and the retry lowered it to
 * `(k / 16384)^8` rather than to zero. #434's own words apply: *a retry would paper over the collision
 * instead of not causing it.*
 *
 * **What convicts what.** All three tests drive [UdpSocketChannelFactory.openConnectedChannel], the call
 * the primary path now makes, and all three are mutation-proven against it binding `localHost` unchanged
 * instead of the resolved source: **5 of 5 mutated runs red, 3 of 3 fixed runs green.** The two seam
 * tests assert the decision directly; the contention test measures the collision the decision exists to
 * prevent, which is why [PRIMARIES] is sized for it rather than left at the sibling suite's 250.
 *
 * What no test here asserts is that the *builder* makes that call, and none can without threading a seam
 * through `buildJvmQuicConnection`. It is instead a statically visible fact: after #519
 * `CommonJvmWithQuicConnection.kt` contains no `UdpSocket.connect` at all, so it has no second way to
 * open a socket to the peer to regress into. Note too that the contention test is green against the
 * *pre-#519 code as it shipped* — the 8-attempt retry hid the very collision it measures — so it convicts
 * the mechanism, not the diff. That is #482's lesson taken literally: an end-to-end probe that needs two
 * draws to lose goes as r², so measure the single mechanism.
 *
 * The sibling suites are [MigrationPathSourceAddressTests] (the same contention for migration paths, real
 * sockets) and [RouteSourceResolutionTests] (what each [RouteProbeFailure] does to `openPath`, #523).
 */
class PrimaryPathSourceAddressTests {
    /**
     * The behavioural gate, positive side: with the route probe served, the primary channel binds the
     * address the route resolved.
     *
     * RED against the pre-#519 primary open, which asked for no source at all.
     */
    @Test
    fun thePrimaryChannelBindsTheResolvedSourceAddress() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", UNUSED_PORT)
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Serve)
            val factory = factoryFor(peer, opener)

            val channel = factory.openConnectedChannel(localHost = null, localPort = 0)
            try {
                assertEquals(
                    listOf<String?>("127.0.0.1"),
                    opener.pathBinds,
                    "the connection's first socket must bind the address the route resolved — an unnamed " +
                        "bind here is #434's collision, drawn against every connection this process " +
                        "already holds to the same peer",
                )
            } finally {
                runCatching { channel.close() }
            }
        }

    /**
     * The behavioural gate, negative side: a source the probe cannot name refuses the primary open
     * rather than falling back to the unnamed bind — the same answer `openPath` gives (#523).
     *
     * **Why failing the connection is the right answer here, when the argument in
     * [UnresolvedRouteSourceException] was written about a path that could be retried.** For a migration
     * path, failing costs the caller nothing it had. For the primary it costs the connection, so the
     * justification has to be different: every way this probe fails is a way the real connect fails too
     * — a refused socket, a sandbox, no route — with the single exception of a lost ephemeral draw,
     * which #483 made disjoint from the peer's own ports and #522 measured at 0 in 6000. And nothing is
     * hidden by refusing: [RouteProbeFailure.ProbeRefused] carries the platform's own exception, so the
     * caller receives the very throwable the unnamed connect would have produced, as the `cause`, plus
     * the fact that it was source resolution that failed.
     */
    @Test
    fun anUnresolvedRouteRefusesThePrimaryChannelRatherThanBindingTheWildcard() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", UNUSED_PORT)
            val refusal = IllegalStateException("no descriptor left for the route probe")
            val opener = ScriptedOpener(peer.port, ProbeAnswer.Refuse(refusal))
            val factory = factoryFor(peer, opener)

            val opened = runCatching { factory.openConnectedChannel(localHost = null, localPort = 0) }
            opened.getOrNull()?.let { channel ->
                runCatching { channel.close() }
                fail(
                    "the primary channel was opened with localHost=${opener.pathBinds.single()} after the " +
                        "route probe was refused with \"${refusal.message}\" — a silent fallback to the " +
                        "unnamed bind is the defect, not the recovery",
                )
            }
            val failure = assertIs<UnresolvedRouteSourceException>(opened.exceptionOrNull())
            val reason = assertIs<RouteProbeFailure.ProbeRefused>(failure.reason)
            assertSame(
                refusal,
                reason.cause,
                "the platform's own refusal must survive as the cause — refusing costs the caller the " +
                    "connection, so it may not also cost them the reason",
            )
            assertEquals(emptyList(), opener.pathBinds, "no socket may be bound once the route is unresolved")
        }

    /**
     * The real-socket shape: a process that already holds many connections to one peer opens the next
     * one without a retry budget.
     *
     * This is the scenario the issue names — a fan-out client, or the 24-way soak — where `k` grows with
     * every live connection and the unnamed bind's odds grow with it. Every channel here is opened by
     * the same call the primary path makes, once each, with nothing catching a `BindException`.
     *
     * Green against the *retry* the old code carried; red against the mechanism the retry was hiding —
     * 5 of 5 mutated runs, 0 of 3 fixed ones. [PRIMARIES] is sized from the measured rate: the `k`-th open draws
     * against `k` held sockets at `k / 16384`, so 500 opens expect `sum(k) / 16384 ~= 7.6` collisions and
     * a run that loses none is `e^-7.6 ~= 1 in 2000`. At 250 the expectation is 1.9 and one mutated run
     * in six comes up green, which is the whole reason the number is not 250. Still a fraction of a
     * second and 500 descriptors.
     *
     * Nothing listens on [UNUSED_PORT] and nothing is sent: a connected UDP socket fixes the 4-tuple
     * locally, so this opens no network traffic at all.
     */
    @Test
    fun manyPrimaryChannelsToOnePeerAllOpenWithDistinctPorts() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", UNUSED_PORT)
            val factory = factoryFor(peer, ConnectedUdpOpener.Platform)

            val opened = ArrayList<ConnectedDatagramChannel>(PRIMARIES)
            try {
                repeat(PRIMARIES) { opened += factory.openConnectedChannel(localHost = null, localPort = 0) }
                assertEquals(
                    PRIMARIES,
                    opened.mapNotNull { it.localAddress.orNull()?.port }.toSet().size,
                    "every connection to one peer must get its own local port",
                )
            } finally {
                for (channel in opened) runCatching { channel.close() }
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
         * Draws per run. See the contention test's KDoc: 500 puts the expected collision count for an
         * unnamed bind at ~7.6, so a mutated run coming up green is ~1 in 2000.
         */
        private const val PRIMARIES = 500

        /** A peer port that is not the route probe's own (9, RFC 863 discard), so the probe steps aside. */
        private const val UNUSED_PORT = 9999
    }
}
