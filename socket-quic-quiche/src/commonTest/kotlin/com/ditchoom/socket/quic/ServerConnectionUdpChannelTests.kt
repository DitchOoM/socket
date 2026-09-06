@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramCapabilities
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.DatagramSendOptions
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The server's per-connection egress: where a reply goes, and — the #556 half — which local address it
 * leaves from.
 *
 * These run on every platform because the decision under test is pure routing in `commonMain`: given
 * what quiche put in `send_info`, which address does the channel name as the destination and which as
 * the source? A real socket would hide exactly that, because on a host whose kernel happens to pick the
 * right source (Linux, primary address) a channel that names nothing at all still passes — which is how
 * #556 stayed invisible on every Linux lane for the life of the project.
 */
class ServerConnectionUdpChannelTests {
    private val loopback = SocketAddress.ofLiteral("127.0.0.1", 4433)
    private val alias = SocketAddress.ofLiteral("127.0.0.2", 4433)
    private val peer = SocketAddress.ofLiteral("198.51.100.7", 51000)
    private val migratedPeer = SocketAddress.ofLiteral("198.51.100.9", 51999)

    private val peerKey = key(4, 51000, 7)
    private val migratedKey = key(4, 51999, 9)
    private val loopbackKey = key(4, 4433, 1)
    private val aliasKey = key(4, 4433, 2)

    /**
     * The gate. A server reply must name the local address quiche recorded for the path, so the kernel
     * is never left to choose one.
     *
     * RED before #556's plumbing: the channel had no `from` to name and sent with default options, so a
     * wildcard-bound server answered a client on 127.0.0.2 from 127.0.0.1 and the client — `connect()`ed
     * to 127.0.0.2 — dropped every reply as off-path.
     */
    @Test
    fun aServerReplyNamesTheLocalAddressQuicheRecordedForThePath() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ServerReply(to = peerKey, from = aliasKey))

            val (to, options) = socket.sent.single()
            assertSame(peer, to, "the destination is the connection's own peer")
            assertEquals(alias, options.fromLocal, "the reply must leave from the address the client dialled")
        }

    /** The destination still follows a migrated peer's new source address (RFC 9000 §9). */
    @Test
    fun aServerReplyFollowsAMigratedPeerAndStillPinsItsSource() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ServerReply(to = migratedKey, from = loopbackKey))

            val (to, options) = socket.sent.single()
            assertSame(migratedPeer, to, "a migrated peer's reply goes to its new source address")
            assertEquals(loopback, options.fromLocal, "and still names the local address it must leave from")
        }

    /**
     * A backend that decodes no egress address reports `family == 0`. That is a *known-absent* source,
     * not a wrong one — naming nothing and letting the platform choose is the only honest answer, and is
     * the behaviour every backend had before #556.
     */
    @Test
    fun anUndecodableSourceLeavesTheChoiceToThePlatform() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ServerReply(to = peerKey, from = PathKey(0, 0, 0L, 0L)))

            val options = socket.sent.single().second
            assertNull(options.fromLocal, "an absent source must not be invented")
        }

    /** A source the receive loop never recorded is a miss, and a miss is not a licence to guess. */
    @Test
    fun anUnknownSourceKeyIsAMissRatherThanAGuess() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ServerReply(to = peerKey, from = key(4, 9999, 99)))

            val options = socket.sent.single().second
            assertNull(options.fromLocal, "an unresolvable source must not fall back to another address")
        }

    /**
     * A destination the receive loop never saw falls back to the connection's own peer rather than
     * being dropped — the pre-existing contract, kept explicit so a future change has to break a test.
     */
    @Test
    fun anUnknownDestinationFallsBackToTheConnectionsOwnPeer() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ServerReply(to = key(4, 1, 200), from = aliasKey))

            val (to, _) = socket.sent.single()
            assertSame(peer, to, "an unresolvable destination falls back to the fixed peer")
        }

    /**
     * [SendTarget.ConnectedPeer] is the client shape and names neither half. The server egress channel
     * is never handed one in production, but the branch must not invent a source if it ever is.
     */
    @Test
    fun aConnectedPeerSendNamesNeitherHalf() =
        runTest {
            val socket = RecordingSocket()
            val channel = channel(socket)

            channel.send(payload(), LEN, SendTarget.ConnectedPeer)

            val (to, options) = socket.sent.single()
            assertSame(peer, to, "the fixed peer is the destination")
            assertNull(options.fromLocal, "and no source is named")
        }

    private fun channel(socket: AddressedDatagramChannel): ServerConnectionUdpChannel {
        val peers = mapOf(peerKey to peer, migratedKey to migratedPeer)
        val locals = mapOf(loopbackKey to loopback, aliasKey to alias)
        return ServerConnectionUdpChannel(
            channel = socket,
            fixedPeer = peer,
            fixedPeerKey = peerKey,
            peerFor = peers::get,
            localFor = locals::get,
        )
    }

    private fun payload(): PlatformBuffer = BufferFactory.Default.allocate(LEN)

    /** A [PathKey] built by hand — these tests never decode a real sockaddr, they assert routing. */
    private fun key(
        family: Int,
        port: Int,
        lo: Long,
    ): PathKey = PathKey(family = family, port = port, hi = 0L, lo = lo)

    private class RecordingSocket : AddressedDatagramChannel {
        val sent = mutableListOf<Pair<SocketAddress, DatagramSendOptions>>()

        override val localAddress: SocketAddress = SocketAddress.ofLiteral("0.0.0.0", 4433)
        override val isOpen: Boolean = true
        override val maxWritableSize: Int = 1200
        override val capabilities: DatagramCapabilities = DatagramCapabilities()

        override suspend fun receive(): DatagramReadResult = awaitCancellation()

        override suspend fun send(
            payload: ReadBuffer,
            to: SocketAddress,
            options: DatagramSendOptions,
        ) {
            sent += to to options
        }

        override fun close() = Unit
    }

    private companion object {
        const val LEN = 4
    }
}
