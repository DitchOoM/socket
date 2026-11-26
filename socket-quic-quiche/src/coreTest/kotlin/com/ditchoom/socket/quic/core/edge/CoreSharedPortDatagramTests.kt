@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** RFC 9221 datagrams on a lent channel: a client on a demultiplexed port echoes them byte-exact with a core server. */
class CoreSharedPortDatagramTests {
    @Test
    fun datagramsEchoOverALentChannel() =
        coreTest("CoreSharedPortDatagramTests.datagramsEchoOverALentChannel") {
            val on = coreTestOptions.copy(datagrams = DatagramOptions(), sessionStore = QuicSessionStore.NotKept)
            withCoreServer(options = on, onConnection = { echoAll(it.datagramChannel()) }) { server ->
                val shared = UdpSocket.bind(LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
                try {
                    val client =
                        coreConnect(
                            host = LOOPBACK,
                            port = server.port,
                            serverName = "localhost",
                            options = on,
                            factory = BufferFactory.network(),
                            api = platformQuicheApi(),
                            timeout = WAIT,
                            readPolicy = ReadPolicy.Bounded(WAIT),
                            writePolicy = WritePolicy.Bounded(WAIT),
                            flight = TEST_FLIGHT,
                            ride = ClientRide.Lent(lentChannelOf(shared.quic)),
                        )
                    val channel = client.connection.datagramChannel()
                    for (n in 0 until ECHOES) {
                        val sent = ByteArray(1 + n * 37 % 1_000) { (it + n).toByte() }
                        channel.send(
                            BufferFactory.network().allocate(sent.size).apply {
                                writeBytes(sent)
                                resetForRead()
                            },
                        )
                        val got =
                            when (val result = withTimeout(WAIT) { channel.receive() }) {
                                is DatagramReadResult.Received -> result.datagram.payload
                                is DatagramReadResult.Closed -> fail("echo $n ended: ${result.reason}")
                            }
                        assertContentEquals(sent, got.readByteArray(got.remaining()), "echo $n")
                        got.freeIfNeeded()
                    }
                    client.connection.close(QuicError.NoError)
                } finally {
                    shared.close()
                }
            }
        }

    private suspend fun echoAll(channel: ConnectedDatagramChannel) {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    channel.send(result.datagram.payload)
                    result.datagram.payload.freeIfNeeded()
                }
                is DatagramReadResult.Closed -> return
            }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ECHOES = 50
        val WAIT = 5.seconds
    }
}
