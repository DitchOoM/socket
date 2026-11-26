@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicIncomingStream
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicReceiveStream
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.readEach
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.udp.MultiplexedDatagram
import com.ditchoom.socket.udp.MultiplexedProtocol
import com.ditchoom.socket.udp.MultiplexedUdpSocket
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC on a port the caller shares with ICE and STUN (RFC 9443), the shape of direct peer-to-peer QUIC over ICE: the
 * controlled side serves on the QUIC branch of its demultiplexed socket and the controlling side dials from the QUIC
 * branch of its own. Each branch carries QUIC while the other branch of the same socket carries STUN, and closing the
 * connection and the listener leaves both sockets serving STUN.
 */
class CoreSharedPortTests {
    @Test
    fun aClientAndAServerEachOnASharedPortEchoWhileStunRidesTheSamePorts() =
        coreTest("CoreSharedPortTests.aClientAndAServerEachOnASharedPortEchoWhileStunRidesTheSamePorts") {
            sharedPorts { server, client ->
                val listener = serving(server)
                val handlers = launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
                val connection = dial(client, server)
                // STUN crosses both ports in both directions while the QUIC connection on them is open.
                assertStunCrosses(client, server)
                assertSharedPortEchoes(connection, 0)
                assertStunCrosses(server, client)
                assertSharedPortEchoes(connection, 1)
                connection.close(QuicCloseChoice.Immediate())
                handlers.cancel()
                listener.close()
            }
        }

    @Test
    fun closingTheConnectionAndTheListenerLeavesBothSharedPortsServing() =
        coreTest("CoreSharedPortTests.closingTheConnectionAndTheListenerLeavesBothSharedPortsServing") {
            sharedPorts { server, client ->
                val listener = serving(server)
                val handlers = launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
                val connection = dial(client, server)
                assertSharedPortEchoes(connection, 0)
                connection.close(QuicCloseChoice.Immediate())
                handlers.cancel()
                // The listener's close returns once its loop stopped and let go of its socket.
                listener.close()
                assertTrue(server.socket.quic.isOpen, "the server's QUIC branch is the caller's, and stays open")
                assertStunCrosses(client, server)
                assertStunCrosses(server, client)
                // A second connection on the same branches once the first ones let go of them: one QUIC role per channel.
                assertTrue(eventually(LENT_WAIT) { claimable(server.socket.quic) && claimable(client.socket.quic) }, "both let go")
                val second = serving(server)
                val again = launch { second.handleConnections { it.handleStreams(::sharedPortEcho) } }
                val reconnected = dial(client, server)
                assertSharedPortEchoes(reconnected, 2)
                assertTrue(client.socket.quic.isOpen, "the client's QUIC branch is the caller's, and stays open")
                reconnected.close(QuicCloseChoice.Immediate())
                again.cancel()
                second.close()
                assertStunCrosses(client, server)
            }
        }

    /** One demultiplexed socket, and the STUN its other branch received. */
    private class SharedPort(
        val socket: MultiplexedUdpSocket,
        val stun: Channel<MultiplexedDatagram>,
    )

    /** Two demultiplexed loopback sockets, closed once [body] returns. */
    private suspend fun CoroutineScope.sharedPorts(body: suspend (server: SharedPort, client: SharedPort) -> Unit) {
        val server = sharedPort()
        val client = sharedPort()
        try {
            body(server, client)
        } finally {
            server.socket.close()
            client.socket.close()
        }
    }

    private suspend fun CoroutineScope.sharedPort(): SharedPort {
        val socket = UdpSocket.bind(LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
        val stun = Channel<MultiplexedDatagram>(Channel.UNLIMITED)
        launch { socket.datagrams.collect { stun.send(it) } }
        return SharedPort(socket, stun)
    }

    private suspend fun serving(server: SharedPort): QuicListener =
        assertIs<QuicServe.Serving<QuicListener>>(serveQuicOnCore(server.socket.quic, platformTlsConfig(), options)).value

    private suspend fun dial(
        client: SharedPort,
        server: SharedPort,
    ): QuicConnection {
        val at = QuicEndpoint(ResolvedAddress(LOOPBACK, IpFamily.V4), server.socket.localAddress.port)
        return connectQuicOnCore(client.socket.quic, at, "localhost", options, WAIT)
            .getOrElse { throw AssertionError("the client on the shared port did not connect: $it") }
    }

    /** A STUN Binding request sent from [from]'s socket arrives on [to]'s STUN branch, not its QUIC one. */
    private suspend fun assertStunCrosses(
        from: SharedPort,
        to: SharedPort,
    ) {
        from.socket.send(stunRequest(), to.socket.localAddress)
        val got = withTimeout(WAIT) { to.stun.receive() }
        assertEquals<MultiplexedProtocol>(MultiplexedProtocol.Stun, got.protocol)
        assertEquals(STUN_REQUEST.size, got.datagram.payload.remaining())
        got.datagram.payload.freeIfNeeded()
    }

    private fun stunRequest(): ReadBuffer = bytes(STUN_REQUEST)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val WAIT = 10.seconds
        val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

        /** A STUN Binding request (RFC 8489): type 0x0001, no attributes, the magic cookie, a 12-byte transaction id. */
        val STUN_REQUEST =
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + ByteArray(12) { (it + 1).toByte() }
    }
}

private const val SHARED_PAYLOAD = 5_000

/** [channel], claimed for one connect of a test's own: no other QUIC user holds it. */
internal fun lentChannelOf(channel: AddressedDatagramChannel): LentChannel =
    assertIs<Claimed.Lent>(LentChannels.claim(channel), "a test's channel is free").channel

/** A stream of [connection] echoes round [round]'s bytes. */
internal suspend fun assertSharedPortEchoes(
    connection: QuicConnection,
    round: Int,
) {
    val sent = ByteArray(SHARED_PAYLOAD) { (it + round).toByte() }
    val echoed =
        connection
            .openStream { (send, receive) ->
                assertIs<StreamWrite.Written>(send.writeAndFinish(bytes(sent)))
                val got = ReceivedBytes(SHARED_PAYLOAD)
                assertEquals<ScopedRead.NoData>(ScopedRead.End.PeerFinished, withTimeout(10.seconds) { receive.readEach { got.add(it) } })
                got.toByteArray()
            }.getOrElse { throw AssertionError("no stream was opened: $it") }
    assertContentEquals(sent, echoed, "round $round echoed")
}

/** A server's handler: a bidirectional stream's bytes go back on it. */
internal suspend fun sharedPortEcho(incoming: QuicIncomingStream) {
    when (incoming) {
        is QuicStream -> incoming.receive.readEach { incoming.send.write(it) }
        is QuicReceiveStream -> incoming.readEach { }
    }
}

private fun bytes(content: ByteArray): ReadBuffer =
    BufferFactory.network().allocate(content.size).apply {
        writeBytes(content)
        resetForRead()
    }
