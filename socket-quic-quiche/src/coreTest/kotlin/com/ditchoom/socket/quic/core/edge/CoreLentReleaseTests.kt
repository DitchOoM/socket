@file:OptIn(ExperimentalDatagramApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.quic.QuicCloseChoice
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSocketDrops
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A connection's or a listener's close returns with its lent channel free again: a connect or serve right after it,
 * as an ICE restart or a retry makes one, succeeds with no wait of its own.
 */
class CoreLentReleaseTests {
    @Test
    fun aConnectRightAfterTheCloseOfTheLastOneOnTheSameChannelSucceeds() =
        coreTest("CoreLentReleaseTests.aConnectRightAfterTheCloseOfTheLastOneOnTheSameChannelSucceeds") {
            val (listener, handlers) = echoingOwnedListener()
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            try {
                repeat(ROUNDS) { round ->
                    val connection = dial(shared.quic, listener.port)
                    assertSharedPortEchoes(connection, round)
                    assertIs<QuicSocketDrops.LentChannel>(assertIs<QuicStatsRead.Read>(connection.stats()).stats.socketDrops)
                    connection.close(QuicCloseChoice.Immediate())
                }
            } finally {
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }

    @Test
    fun aServeRightAfterTheCloseOfTheLastListenerOnTheSameChannelSucceeds() =
        coreTest("CoreLentReleaseTests.aServeRightAfterTheCloseOfTheLastListenerOnTheSameChannelSucceeds") {
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            // The listener's last sends, its CONNECTION_CLOSEs, never return: its pump lets go only at its drain bound.
            val hanging = AtomicInt(0)
            val faulty = FaultyChannel(shared.quic) { _, _ -> if (hanging.load() == 1) SendFault.Hangs else SendFault.Passes }
            try {
                repeat(ROUNDS) { round ->
                    val served = serveQuicOnCore(faulty, platformTlsConfig(), coreTestOptions)
                    val listener = assertIs<QuicServe.Serving<QuicListener>>(served, "round $round").value
                    val handlers = launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
                    val client = connectCore(shared.localAddress.port)
                    assertSharedPortEchoes(client.connection.public(), round)
                    hanging.store(1)
                    handlers.cancel()
                    listener.close()
                    hanging.store(0)
                    client.connection.closeNow(QuicError.NoError)
                }
            } finally {
                shared.close()
            }
        }

    private suspend fun dial(
        channel: AddressedDatagramChannel,
        port: Int,
    ): QuicConnection {
        val at = QuicEndpoint(ResolvedAddress(LENT_LOOPBACK, IpFamily.V4), port)
        return assertIs<QuicConnect.Connected<QuicConnection>>(
            connectQuicOnCore(channel, at, "localhost", coreTestOptions, LENT_WAIT),
        ).value
    }

    private companion object {
        const val ROUNDS = 3
    }
}
