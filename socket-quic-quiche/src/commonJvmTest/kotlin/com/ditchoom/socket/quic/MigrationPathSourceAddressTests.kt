@file:OptIn(InternalQuicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A migrating connection must be able to open **many** paths to the same peer.
 *
 * Every path a connection migrates onto connects to the *same* peer, and a path stays open until it is
 * retired, so each new path is opened while its predecessors still hold their local ports against that
 * peer. If the source address is left unnamed, the kernel chooses the ephemeral port knowing only the
 * local side and can return one whose full 4-tuple is already taken — a collision that surfaces not
 * from the bind but from `connect`, as `EADDRINUSE`, and reaches the caller as
 * `MigrationResult.Unmoved.Failed.LocalPathUnavailable`: a transient draw wearing a terminal type.
 *
 * Measured on macOS across 3000 sockets to one peer: **263 failures with an unnamed bind, 0 once the
 * resolved source address is bound** (and 245 with no bind at all — the JVM binds the unnamed address
 * implicitly). At [PATHS] this test is therefore red most of the time against the unnamed bind and
 * green against the fix, without needing a retry anywhere.
 *
 * No peer has to exist: a UDP `connect` fixes the 4-tuple locally and sends nothing, so the discard
 * port is a legitimate target and this test opens no network traffic at all.
 *
 * **Reading a failure here.** The JDK raises `java.net.BindException("Address already in use")` for
 * `EADDRINUSE` from `bind0` and `connect0` alike, so the class does not say which syscall was refused —
 * only the stack frame does, and a log that shows the message without the frame settles nothing. That
 * is what #463 turns on: it reads a failure of this test as a bind-side collision the #434 model says
 * cannot happen, from a log with no frame in it. An ephemeral bind is not refused with `EADDRINUSE` at
 * all (measured on macOS/JDK 21: 0 in 8000 draws against 250 and 1000 holders of the contended peer,
 * named and unnamed), so a `bind0` frame here would be genuine news. A `connect0` frame is the #434
 * mechanism, and it reaches this test only through something that handed the real connect an unnamed
 * bind: the route probe's swallowed failure (#482, fixed in #483), or another process holding paths to
 * the same peer — the pcb table is system-wide.
 *
 * Reproduced outside Gradle, 250 paths per run exactly as below, with the probe pointed at the peer's
 * own port as #482 left it: **1 run in 12 red**, `BindException @ sun.nio.ch.Net.connect0`, from 25
 * lost probe draws in 3000. With the probe stepped aside as #483 fixed it: **0 runs in 24 red**, 0 lost
 * draws in 6000.
 */
class MigrationPathSourceAddressTests {
    @Test
    fun manyPathsToOnePeerAllOpenWithDistinctPorts() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", DISCARD_PORT)
            val factory = factoryFor(peer)

            val opened = ArrayList<NewPath>(PATHS)
            try {
                repeat(PATHS) { opened += factory.openPath(null, 0) }
                assertEquals(
                    PATHS,
                    opened.map { it.localEndpoint.port }.toSet().size,
                    "every path to one peer must get its own local port",
                )
            } finally {
                for (path in opened) {
                    runCatching { path.channel.close() }
                    runCatching { path.release() }
                }
            }
        }

    /**
     * The route probe must not contend with the paths it exists to serve.
     *
     * [UdpSocketChannelFactory.routeSourceAddress] learns the source address by opening a probe with an
     * *unnamed* bind — the one configuration this whole fix exists to avoid. Pointed at the peer's own
     * port, that probe draws from the very 4-tuple space the open paths hold, so its failure rate rises
     * with the number of paths open; and because the probe swallowed failure to `null`, one lost draw
     * silently handed the real connect the unnamed bind. That is a fix that dissolves exactly when the
     * migration it protects gets hard.
     *
     * A lost draw is now [RouteSource.Unresolved] and `openPath` refuses it outright (#523), so this
     * still measures the draw itself — the whole point being that it never has to be refused.
     *
     * This measures the probe alone, so it does not depend on the two-in-a-row coincidence the
     * end-to-end test needs. Measured on macOS with [PATHS] paths held, per [PROBE_DRAWS] probes:
     * **25 failures at the peer's port, 0 at any other** — so this is red by ~25 expected failures
     * against the unpatched probe, not by a rare draw.
     *
     * The peer sits on [UNUSED_PORT] rather than [DISCARD_PORT] so the probe takes its ordinary
     * destination; the sibling test above, whose peer *is* on the probe's own port, covers the
     * branch that has to step aside. Between them both choices are exercised under contention.
     */
    @Test
    fun theRouteProbeNeverLosesADrawToTheOpenPaths() =
        runBlocking {
            val peer = UdpSocket.resolve("127.0.0.1", UNUSED_PORT)
            val factory = factoryFor(peer)

            val opened = ArrayList<NewPath>(PATHS)
            try {
                repeat(PATHS) { opened += factory.openPath(null, 0) }

                val unresolved = ArrayList<RouteProbeFailure>()
                repeat(PROBE_DRAWS) {
                    when (val source = factory.routeSourceAddress()) {
                        is RouteSource.Unresolved -> unresolved += source.reason
                        is RouteSource.Resolved, RouteSource.PlatformAssigned -> Unit
                    }
                }
                assertEquals(
                    0,
                    unresolved.size,
                    "the route probe lost ${unresolved.size} of $PROBE_DRAWS draws to the $PATHS paths " +
                        "it was resolving a source address for: " +
                        unresolved.map { it.describe() }.distinct(),
                )
            } finally {
                for (path in opened) {
                    runCatching { path.channel.close() }
                    runCatching { path.release() }
                }
            }
        }

    private fun factoryFor(peer: SocketAddress) =
        UdpSocketChannelFactory(
            peer = peer,
            codec = SocketAddressCodec(hostOsSockAddrLayout()),
            bufferFactory = BufferFactory.Default,
            recvBufferFactory = BufferFactory.Default,
            receiveBufferSize = QuicheDriver.MAX_DATAGRAM_SIZE,
            localEndpointSupport = LocalEndpointSupport.Bindable,
        )

    private companion object {
        /**
         * Enough draws that an unnamed bind is overwhelmingly likely to collide (~8 failures per 500
         * measured), while staying a fraction of a second and a few hundred file descriptors.
         */
        private const val PATHS = 250

        /** RFC 863 discard. Nothing listens and nothing is sent — `connect` only fixes the 4-tuple. */
        private const val DISCARD_PORT = 9

        /** Draws per run: at [PATHS] the unpatched probe loses ~25 of these, so zero is a real gate. */
        private const val PROBE_DRAWS = 2000

        /** A peer port that is not the probe's own, so the probe takes its ordinary destination. */
        private const val UNUSED_PORT = 9999
    }
}
