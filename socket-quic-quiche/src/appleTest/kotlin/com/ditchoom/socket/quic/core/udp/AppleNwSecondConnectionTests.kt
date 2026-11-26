@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.ApplePosixUdp
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.edge.NwConnector
import com.ditchoom.socket.quic.core.edge.connect
import com.ditchoom.socket.quic.core.edge.nwSocketIo
import com.ditchoom.socket.quic.network
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.sockaddr_in
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A client's move on Network.framework opens a second connection to the peer beside the carrying one: each datagram
 * leaves the connection its ticket names, one for a connection that is gone goes nowhere, and a promotion makes the
 * second the carrying one.
 */
class AppleNwSecondConnectionTests {
    @Test
    fun eachDatagramLeavesTheConnectionItsTicketNamesUntilTheSecondIsPromoted() =
        runBlocking<Unit> {
            val receiver = ApplePosixUdp.openBoundLoopbackSocket()
            val storage = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(4 * SIZE))).bytes
            try {
                ApplePosixUdp.setReceiveTimeout(receiver, RECEIVE_WAIT_MILLIS)
                val port = ApplePosixUdp.boundPortOf(receiver)
                val io = nwSocketIo(NwConnector().connect("127.0.0.1", port)) {}.io
                val second = SocketTicket.FIRST.next()
                assertIs<Beside.Arriving>(io.openBeside(ANY, second))
                withTimeout(5.seconds) {
                    while (true) {
                        when (io.settleBeside()) {
                            is Beside.Opened -> break
                            is Beside.Arriving -> delay(5)
                            is Beside.Refused, is Beside.Unsupported -> throw AssertionError("no second connection was made")
                        }
                    }
                }
                val batch = SendBatch(storage, BatchLimits(4, SIZE))

                sendOn(io, batch, listOf(SocketTicket.FIRST))
                val carrying = sourcesAt(receiver).single()
                sendOn(io, batch, listOf(SocketTicket.FIRST, second, SocketTicket(STALE)))
                val both = sourcesAt(receiver)
                assertEquals(2, both.size, "the stale ticket's datagram went nowhere: $both")
                assertEquals(2, both.toSet().size, "the two left two connections: $both")
                val moved = both.single { it != carrying }

                io.promote()
                sendOn(io, batch, listOf(SocketTicket.FIRST, second))
                assertEquals(listOf(moved), sourcesAt(receiver), "only the promoted connection carries")

                io.closeSecond()
                sendOn(io, batch, listOf(second))
                assertEquals(listOf(moved), sourcesAt(receiver), "closing no second leaves the carrying one")
                io.close()
            } finally {
                storage.free()
                close(receiver)
            }
        }

    @Test
    fun aClosedSecondConnectionLeavesTheCarryingOne() =
        runBlocking<Unit> {
            val receiver = ApplePosixUdp.openBoundLoopbackSocket()
            val storage = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(4 * SIZE))).bytes
            try {
                ApplePosixUdp.setReceiveTimeout(receiver, RECEIVE_WAIT_MILLIS)
                val io = nwSocketIo(NwConnector().connect("127.0.0.1", ApplePosixUdp.boundPortOf(receiver))) {}.io
                val second = SocketTicket.FIRST.next()
                io.openBeside(ANY, second)
                withTimeout(5.seconds) { while (io.settleBeside() !is Beside.Opened) delay(5) }
                io.closeSecond()
                val batch = SendBatch(storage, BatchLimits(4, SIZE))
                sendOn(io, batch, listOf(SocketTicket.FIRST, second))
                val sources = sourcesAt(receiver)
                assertEquals(1, sources.size, "only the carrying connection sent: $sources")
                io.close()
            } finally {
                storage.free()
                close(receiver)
            }
        }

    private fun sendOn(
        io: DatagramBatchIo,
        batch: SendBatch,
        tickets: List<SocketTicket>,
    ) {
        for (ticket in tickets) batch.commit(SIZE, SendTarget.ConnectedPeer, ticket)
        assertIs<SendBatchResult.AllTaken>(io.send(batch))
        // The io's caller takes what it answered taken.
        batch.took(batch.pending)
    }

    /** The source ports of what reaches [receiver] before it is quiet for the receive wait. */
    private fun sourcesAt(receiver: Int): List<Int> {
        val into = ByteArray(SIZE * 2)
        val ports = ArrayList<Int>()
        memScoped {
            val from = alloc<sockaddr_in>()
            while (ApplePosixUdp.receiveFrom(receiver, into, from) >
                0
            ) {
                ports += from.sin_port.toInt().let { ((it and 0xFF) shl 8) or ((it shr 8) and 0xFF) }
            }
        }
        return ports
    }

    private companion object {
        const val SIZE = 1200
        const val STALE = 7
        const val RECEIVE_WAIT_MILLIS = 300L
        val ANY = PathKey(family = 4, port = 0, hi = 0L, lo = 0L)
    }
}
