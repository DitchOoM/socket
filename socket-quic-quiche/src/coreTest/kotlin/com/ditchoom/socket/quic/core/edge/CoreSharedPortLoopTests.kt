@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicListener
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A lent channel's connection shares its loop with connections on sockets the loop owns. On the native stack an owned
 * socket keeps the loop's receive buffers armed in its receive records between reads, so a lent channel's io finds the
 * loop's pool empty and must take its buffer from an armed slot.
 */
class CoreSharedPortLoopTests {
    @Test
    fun aLentClientReceivesOnALoopWhoseOwnedSocketHoldsItsReceiveBuffers() =
        coreTest("CoreSharedPortLoopTests.aLentClientReceivesOnALoopWhoseOwnedSocketHoldsItsReceiveBuffers") {
            val served = serveQuicOnCore(0, LOOPBACK, platformTlsConfig(), coreTestOptions)
            val listener = assertIs<QuicServe.Serving<QuicListener>>(served).value
            val handlers = launch { listener.handleConnections { it.handleStreams(::sharedPortEcho) } }
            val loops = LoopSet(1)
            val shared = UdpSocket.bind(LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            try {
                // An owned socket first: its reads arm the loop's receive buffers.
                val owned = connectCore(listener.port, loops = loops)
                assertSharedPortEchoes(owned.connection.public(), 0)
                val lent =
                    coreConnect(
                        host = LOOPBACK,
                        port = listener.port,
                        serverName = "localhost",
                        options = coreTestOptions.copy(sessionStore = QuicSessionStore.NotKept),
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        timeout = WAIT,
                        readPolicy = ReadPolicy.Bounded(WAIT),
                        writePolicy = WritePolicy.Bounded(WAIT),
                        loops = loops,
                        ride = ClientRide.Lent(LentChannel(shared.quic)),
                    )
                assertSharedPortEchoes(lent.connection.public(), 1)
                assertSharedPortEchoes(owned.connection.public(), 2)
                lent.connection.closeNow(QuicError.NoError)
                owned.connection.closeNow(QuicError.NoError)
            } finally {
                loops.close()
                shared.close()
                handlers.cancel()
                listener.close()
            }
        }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val WAIT = 10.seconds
    }
}
