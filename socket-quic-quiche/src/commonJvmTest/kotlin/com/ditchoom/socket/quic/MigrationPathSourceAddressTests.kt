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
     * with the number of paths open; and because the probe swallows failure to `null`, one lost draw
     * silently hands the real connect the unnamed bind. That is a fix that dissolves exactly when the
     * migration it protects gets hard.
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

                var unresolved = 0
                repeat(PROBE_DRAWS) { if (factory.routeSourceAddress() == null) unresolved++ }
                assertEquals(
                    0,
                    unresolved,
                    "the route probe lost $unresolved of $PROBE_DRAWS draws to the $PATHS paths it " +
                        "was resolving a source address for",
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
