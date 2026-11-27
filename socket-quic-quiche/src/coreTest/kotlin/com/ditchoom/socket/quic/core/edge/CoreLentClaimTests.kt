@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.quic.QuicConnect
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRefusal
import com.ditchoom.socket.quic.QuicServe
import com.ditchoom.socket.quic.connectQuicOnCore
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.serveQuicOnCore
import com.ditchoom.socket.quic.toPathKey
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A lent channel carries one QUIC role at a time, and an io that never reached its loop neither reads the channel nor
 * keeps it.
 */
class CoreLentClaimTests {
    @Test
    fun aSecondConnectOrServeOnAHeldChannelIsRefusedTypedUntilTheFirstHasLetGo() =
        coreTest("CoreLentClaimTests.aSecondConnectOrServeOnAHeldChannelIsRefusedTypedUntilTheFirstHasLetGo") {
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            try {
                val (listener, handlers) = echoingListener(shared.quic)
                val at = QuicEndpoint(ResolvedAddress(LENT_LOOPBACK, IpFamily.V4), 1)
                val connect = connectQuicOnCore(shared.quic, at, "localhost", coreTestOptions, LENT_WAIT)
                assertEquals<QuicRefusal>(QuicRefusal.ChannelInUse, assertIs<QuicConnect.Refused>(connect).refusal)
                val serve = serveQuicOnCore(shared.quic, platformTlsConfig(), coreTestOptions)
                assertEquals<QuicRefusal>(QuicRefusal.ChannelInUse, assertIs<QuicServe.Refused>(serve).refusal)
                handlers.cancel()
                listener.close()
                // Free again once the listener's io has let go of the channel.
                assertTrue(eventually { claimable(shared.quic) }, "the closed listener gave the channel back")
                val (again, serving) = echoingListener(shared.quic)
                val client = connectCore(shared.localAddress.port)
                assertSharedPortEchoes(client.connection.public(), 0)
                client.connection.closeNow(QuicError.NoError)
                serving.cancel()
                again.close()
            } finally {
                shared.close()
            }
        }

    @Test
    fun anIoThatNeverReachedItsLoopNeitherReadsTheChannelNorKeepsIt() =
        coreTest("CoreLentClaimTests.anIoThatNeverReachedItsLoopNeitherReadsTheChannelNorKeepsIt") {
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val sender = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network())
            val loops = LoopSet(1)
            try {
                val lent = lentChannelOf(shared.quic)
                val peers = onePeerOn(lent, sender.localAddress, sender.localAddress.toPathKey())
                // The io a setup made, and a raise right after: nobody closes it; the connect gives its claim back.
                StackSocket.Lent(lent, peers, shared.localAddress.toPathKey(), BufferFactory.network()).ioOf(emptyList(), 0, 0) { }
                lent.claim.entryFailed()
                sender.send(quicShaped(), shared.localAddress)
                val got = assertIs<DatagramReadResult.Received>(withTimeout(LENT_WAIT) { shared.quic.receive() })
                assertEquals(QUIC_SHAPED, got.datagram.payload.remaining(), "the caller reads its own channel")
                got.datagram.payload.freeIfNeeded()
                assertTrue(claimable(shared.quic), "the claim was given back")
            } finally {
                loops.close()
                sender.close()
                shared.close()
            }
        }

    private fun quicShaped() =
        BufferFactory.network().allocate(QUIC_SHAPED).apply {
            // A short header's first byte: QUIC in RFC 9443's table.
            writeByte(0x40)
            repeat(QUIC_SHAPED - 1) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val QUIC_SHAPED = 40
    }
}
