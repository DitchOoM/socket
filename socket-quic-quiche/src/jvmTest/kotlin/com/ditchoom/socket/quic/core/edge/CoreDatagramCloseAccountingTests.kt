package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every datagram a send accepted, accounted for when the connection closes with quiche's own queue still full behind a
 * full congestion window; and the room a prefixed send reports.
 */
class CoreDatagramCloseAccountingTests {
    private fun options(datagrams: DatagramOptions) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            datagrams = datagrams,
        )

    @Test
    fun aCloseWithQuichesQueueHeldByAFullWindowCountsEveryAcceptedDatagram() =
        VirtualPair.with(options(DatagramOptions(sendQueueLen = DEPTH))) { pair ->
            pair.accept()
            val connection = pair.client.connection
            val channel = connection.coreDatagrams
            val payload = payloadOf(LARGE)
            var accepted = 0L
            // The first round fills quiche's queue; less than a round trip later, no ACK has opened the window.
            repeat(QUEUED) { if (channel.send(payload) === DatagramSend.Sent) accepted++ }
            pair.runFor(2.milliseconds)
            repeat(QUEUED) { if (channel.send(payload) === DatagramSend.Sent) accepted++ }
            val before = statsOf(pair, connection)
            assertTrue(before.datagramsSent in 1 until accepted - QUEUED, "the window held some in quiche's queue: $before")
            connection.closeNow(QuicError.NoError)
            pair.runUntil { connection.end() is EdgeEnd.Ended }
            val counted = connection.counts
            assertEquals(
                accepted,
                before.datagramsSent + counted.datagramsDroppedAtClose + counted.datagramsEvicted +
                    counted.datagramsExpiredOutgoing + counted.datagramsDroppedUnsendable,
                "accepted = sent + dropped at the close, those in quiche's queue included",
            )
            payload.freeIfNeeded()
        }

    @Test
    fun aPrefixedSendTooLargeReportsTheRoomLeftForThePayload() =
        VirtualPair.with(options(DatagramOptions())) { pair ->
            pair.accept()
            val channel = pair.client.connection.coreDatagrams
            val max = channel.maxWritableSize
            val prefix = payloadOf(PREFIX)
            val payload = payloadOf(max - PREFIX + 1)
            assertEquals(DatagramSend.NotSent.TooLarge(max - PREFIX), channel.send(prefix, payload))
            val fits = payloadOf(max - PREFIX)
            assertSame(DatagramSend.Sent, channel.send(prefix, fits), "a payload of the reported size goes")
            listOf(prefix, payload, fits).forEach { it.freeIfNeeded() }
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.statsOf(
        pair: VirtualPair,
        connection: CoreConnection,
    ): QuicConnectionStats {
        val read = async(start = CoroutineStart.UNDISPATCHED) { connection.stats() }
        pair.runUntil { read.isCompleted }
        return assertIs<QuicStatsRead.Read>(read.await()).stats
    }

    private fun payloadOf(size: Int): PlatformBuffer =
        BufferFactory.network().allocate(maxOf(size, 1)).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val DEPTH = 64

        /** What the core's queue admits of [DEPTH]: the rest is quiche's hand-off of 32 and the door. */
        const val QUEUED = DEPTH - 33
        const val LARGE = 1000
        const val PREFIX = 2
    }
}
