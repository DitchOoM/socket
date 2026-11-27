@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.conn.MoveAsk
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Who a lent client's channel admits: its one peer, in either of the forms a dual-stack socket reports it in, and no
 * one else, each stranger's datagram counted as dropped. A move asked of it is refused at once, typed.
 */
class CoreLentPeerTests {
    @Test
    fun aClientAndAServerOnDualStackWildcardChannelsEchoOverIpv4() =
        coreTest("CoreLentPeerTests.aClientAndAServerOnDualStackWildcardChannelsEchoOverIpv4") {
            // The wildcard bind of each platform: dual-stack `::` where the platform has it, so IPv4 arrives mapped.
            val server = UdpSocket.bind(bufferFactory = BufferFactory.network()).demultiplex(this)
            val client = UdpSocket.bind(bufferFactory = BufferFactory.network()).demultiplex(this)
            val (listener, handlers) = echoingListener(server.quic)
            val loops = LoopSet(1)
            try {
                val lent = lentClient(client.quic, server.localAddress.port, loops)
                assertSharedPortEchoes(lent.connection.public(), 0)
                assertEquals(0L, lent.lentDrops(LentDrop.Foreign), "the server's datagrams were taken as its peer's")
                lent.connection.closeNow(QuicError.NoError)
            } finally {
                loops.close()
                handlers.cancel()
                listener.close()
                server.close()
                client.close()
            }
        }

    @Test
    fun aStrangersDatagramIsDroppedAndCountedAndAMoveIsRefusedTyped() =
        coreTest("CoreLentPeerTests.aStrangersDatagramIsDroppedAndCountedAndAMoveIsRefusedTyped") {
            val (listener, handlers) = echoingOwnedListener()
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val stranger = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network())
            val loops = LoopSet(1)
            try {
                val lent = lentClient(shared.quic, listener.port, loops)
                stranger.send(
                    BufferFactory.network().allocate(STRANGER).apply {
                        writeByte(0x40)
                        repeat(STRANGER - 1) { writeByte(0) }
                        resetForRead()
                    },
                    shared.localAddress,
                )
                assertTrue(eventually { lent.lentDrops(LentDrop.Foreign) == 1L }, "the stranger's datagram was counted")
                assertSharedPortEchoes(lent.connection.public(), 0)
                val asked = lent.parts.migrate(PathKey(4, 0, 0L, 0x7F000001L), RttPrior.CONFIGURED, NetworkId.Unidentified)
                assertEquals<MoveRefusal>(MoveRefusal.SocketLent, assertIs<MoveAsk.Refused>(asked).why)
                lent.connection.closeNow(QuicError.NoError)
            } finally {
                loops.close()
                stranger.close()
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }

    private companion object {
        const val STRANGER = 30
    }
}
