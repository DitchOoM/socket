@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramMaxAge
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramOverflow
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicDatagramCount
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The core's datagram send and receive on a real client and server over a virtual link: every outcome is a result, a
 * send queued or refused allocates nothing, the incoming maximum age, one receiver at a time, and the accounting of
 * every datagram an overrun sender and a slow receiver could not carry.
 */
class CoreDatagramSendTests {
    private fun options(datagrams: DatagramOptions) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            datagrams = datagrams,
        )

    @Test
    fun everySendOutcomeIsAResultAndNoneIsAnException() =
        VirtualPair.with(options(DatagramOptions(sendQueueLen = DEPTH))) { pair ->
            pair.accept()
            val channel = pair.client.connection.coreDatagrams
            val payload = payloadOf(SMALL)
            // The loop does not run while the test sends, so the queue fills to its depth exactly.
            repeat(QUEUED) { assertSame(DatagramSend.Sent, channel.send(payload), "send $it") }
            val full = assertIs<DatagramSend.NotSent.QueueFull>(channel.send(payload))
            assertSame(full, channel.send(payload), "a channel's QueueFull is made once")
            val max = channel.maxWritableSize
            assertEquals(DatagramSend.NotSent.TooLarge(max), channel.send(payloadOf(max + 1)))
            pair.runFor(100.milliseconds)
            assertSame(DatagramSend.Sent, channel.send(payload), "the queue drained into quiche")
            pair.client.connection.closeNow(QuicError.NoError)
            pair.runFor(1.milliseconds)
            assertIs<DatagramSend.NotSent.Unavailable.ConnectionEnded>(
                channel.send(payload),
                "a send after the close point is refused, not queued to never leave",
            )
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            val ended = assertIs<DatagramSend.NotSent.Unavailable.ConnectionEnded>(channel.send(payload))
            assertIs<QuicConnectionEnd.ByLocal>(ended.end)
            assertFailsWith<QuicCloseException>("the buffer-flow send still raises the close") {
                (channel as ConnectedDatagramChannel).send(payload)
            }
            payload.freeIfNeeded()
        }

    @Test
    fun datagramsQueuedWhenAGracefulCloseBeginsAreDroppedAndCountedAndLaterSendsRefused() =
        VirtualPair.with(options(DatagramOptions(sendQueueLen = DEPTH))) { pair ->
            val serverSide = pair.accept()
            val channel = pair.client.connection.coreDatagrams
            val payload = payloadOf(SMALL)
            repeat(QUEUED) { assertSame(DatagramSend.Sent, channel.send(payload)) }
            // The close joins the connection's work behind the sends, before any of them was fed to quiche.
            pair.client.connection.closeNow(QuicError.NoError)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertEquals(QUEUED.toLong(), pair.client.connection.counts.datagramsDroppedAtClose, "dropped at the close point, counted")
            assertEquals(0L, serverSide.counts.datagramsOverflowed + received(pair, serverSide), "none of them left")
            assertIs<DatagramSend.NotSent.Unavailable.ConnectionEnded>(channel.send(payload))
            payload.freeIfNeeded()
        }

    /** Datagrams [connection] received: none waiting for a receive once one finds nothing within a round trip. */
    private suspend fun kotlinx.coroutines.CoroutineScope.received(
        pair: VirtualPair,
        connection: CoreConnection,
    ): Long {
        val got = async(start = CoroutineStart.UNDISPATCHED) { connection.coreDatagrams.receiveDatagram() }
        pair.runFor(pair.oneWay * 2)
        return when (val received = if (got.isCompleted) got.await() else null.also { got.cancel() }) {
            is DatagramReceive.Received -> 1L.also { received.buffer.freeIfNeeded() }
            else -> 0L
        }
    }

    @Test
    fun aConnectionWithoutDatagramsAnswersNotNegotiated() =
        VirtualPair.with(options(DatagramOptions()).copy(datagrams = null)) { pair ->
            pair.accept()
            val channel = pair.client.connection.coreDatagrams
            assertSame(DatagramSend.NotSent.Unavailable.NotNegotiated, channel.send(payloadOf(SMALL)))
            assertSame(DatagramReceive.NotReceived.Unavailable.NotNegotiated, channel.receiveDatagram())
        }

    @Test
    fun aSendQueuedOrRefusedAllocatesNothing() =
        VirtualPair.with(options(DatagramOptions(sendQueueLen = DEPTH))) { pair ->
            pair.accept()
            val channel = pair.client.connection.coreDatagrams
            val payload = payloadOf(SMALL)
            // Every cell is given its memory, and every path through a send is run once.
            repeat(WARM_ROUNDS) {
                while (channel.send(payload) === DatagramSend.Sent) Unit
                repeat(REFUSED) { channel.send(payload) }
                pair.runFor(100.milliseconds)
            }
            val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val self = Thread.currentThread().id
            var sent = 0
            val before = threads.getThreadAllocatedBytes(self)
            while (channel.send(payload) === DatagramSend.Sent) sent++
            val queued = threads.getThreadAllocatedBytes(self)
            repeat(REFUSED) { channel.send(payload) }
            val refused = threads.getThreadAllocatedBytes(self)
            println("[core datagram send allocation] $sent sends queued: ${queued - before} B; $REFUSED refused: ${refused - queued} B")
            assertEquals(QUEUED, sent, "the queue was empty when the measured sends began")
            assertEquals(0L, queued - before, "$sent sends queued")
            assertEquals(0L, refused - queued, "$REFUSED sends refused at a full queue")
            payload.freeIfNeeded()
        }

    @Test
    fun aSecondReceiverWhileOneWaitsIsTypedMisuse() =
        VirtualPair.with(options(DatagramOptions())) { pair ->
            val serverSide = pair.accept()
            val server = serverSide.coreDatagrams
            val first = async(start = CoroutineStart.UNDISPATCHED) { server.receiveDatagram() }
            assertSame(DatagramReceive.NotReceived.ReceiverBusy, server.receiveDatagram())
            val refusal = assertFailsWith<QuicDatagramRefused> { serverSide.datagramChannel().receive() }
            assertIs<DatagramRefusal.ReceiverBusy>(refusal.refusal)
            assertSame(
                DatagramSend.Sent,
                pair.client.connection.coreDatagrams
                    .send(payloadOf(SMALL)),
            )
            pair.runUntil { first.isCompleted }
            assertIs<DatagramReceive.Received>(first.await()).buffer.freeIfNeeded()
            val next = async(start = CoroutineStart.UNDISPATCHED) { server.receiveDatagram() }
            assertTrue(next.isActive, "once the first receive returned, another may wait")
            pair.client.connection.coreDatagrams
                .send(payloadOf(SMALL))
            pair.runUntil { next.isCompleted }
            assertIs<DatagramReceive.Received>(next.await()).buffer.freeIfNeeded()
        }

    @Test
    fun aReceivedDatagramPastTheIncomingAgeIsDroppedWhenItWouldBeDeliveredAndCounted() =
        VirtualPair.with(options(DatagramOptions(incomingMaxAge = DatagramMaxAge.Within(AGE)))) { pair ->
            val serverSide = pair.accept()
            val client = pair.client.connection.coreDatagrams
            repeat(STALE) { client.send(payloadOf(SMALL, seed = it)) }
            pair.runFor(AGE * 3)
            client.send(payloadOf(SMALL, seed = FRESH))
            pair.runFor(pair.oneWay * 2)
            val got = async(start = CoroutineStart.UNDISPATCHED) { serverSide.coreDatagrams.receiveDatagram() }
            pair.runUntil { got.isCompleted }
            val buffer = assertIs<DatagramReceive.Received>(got.await()).buffer
            assertEquals(FRESH.toByte(), buffer.readByte(), "the stale datagrams were passed over for the fresh one")
            buffer.freeIfNeeded()
            val stats = statsOf(pair, serverSide)
            assertEquals(STALE.toLong(), stats.datagramsExpiredIncoming.n)
            assertEquals(
                stats.datagramsReceived,
                1 + stats.datagramsExpiredIncoming.n + stats.datagramsDroppedReceiveQueue.n,
                "received = delivered + dropped",
            )
        }

    @Test
    fun anOverrunRefusingSenderAndASlowReceiverAccountForEveryDatagram() =
        accountsForEveryDatagram(DatagramOptions(sendQueueLen = DEPTH, recvQueueLen = RECEIVE_DEPTH))

    @Test
    fun anOverrunEvictingSenderWithAMaximumAgeAccountsForEveryDatagram() =
        accountsForEveryDatagram(
            DatagramOptions(
                sendQueueLen = DEPTH,
                recvQueueLen = RECEIVE_DEPTH,
                overflow = DatagramOverflow.EvictOldest,
                outgoingMaxAge = DatagramMaxAge.Within(AGE),
                incomingMaxAge = DatagramMaxAge.Within(AGE),
            ),
        )

    /**
     * Bursts far past the send queue's depth, with the loop running only between bursts, and a receiver that reads only
     * after the last: attempts = sent + refused, sent = what quiche sent + evicted + expired + unsendable, and
     * received = delivered + dropped + expired.
     */
    private fun accountsForEveryDatagram(datagrams: DatagramOptions) =
        VirtualPair.with(options(datagrams)) { pair ->
            val serverSide = pair.accept()
            val client = pair.client.connection.coreDatagrams
            val payload = payloadOf(SMALL)
            var accepted = 0L
            var refused = 0L
            repeat(BURSTS) {
                repeat(BURST) {
                    when (client.send(payload)) {
                        is DatagramSend.Sent -> accepted++
                        is DatagramSend.NotSent.QueueFull -> refused++
                        is DatagramSend.NotSent.TooLarge, is DatagramSend.NotSent.Unavailable -> error("unexpected")
                    }
                }
                pair.runFor(AGE / 2)
            }
            pair.runFor(1.seconds)
            val sender = statsOf(pair, pair.client.connection)
            assertEquals((BURSTS * BURST).toLong(), accepted + refused, "attempts = accepted + refused")
            assertEquals(refused, sender.datagramsRefusedQueueFull.n)
            assertEquals(
                accepted,
                sender.datagramsSent + sender.datagramsEvicted.n + sender.datagramsExpiredOutgoing.n + sender.datagramsDroppedUnsendable.n,
                "accepted = sent + evicted + expired + unsendable: $sender",
            )
            when (datagrams.overflow) {
                is DatagramOverflow.RefuseNew -> assertTrue(refused > 0, "the overrun refused some sends")
                is DatagramOverflow.EvictOldest ->
                    assertTrue(
                        refused == 0L && sender.datagramsEvicted.n > 0,
                        "the overrun evicted: $sender",
                    )
            }
            var delivered = 0L
            while (true) {
                val got = async(start = CoroutineStart.UNDISPATCHED) { serverSide.coreDatagrams.receiveDatagram() }
                pair.runFor(pair.oneWay)
                if (!got.isCompleted) {
                    got.cancel()
                    pair.runFor(pair.oneWay)
                    break
                }
                assertIs<DatagramReceive.Received>(got.await()).buffer.freeIfNeeded()
                delivered++
            }
            val receiver = statsOf(pair, serverSide)
            assertEquals(sender.datagramsSent, receiver.datagramsReceived, "the link loses nothing")
            assertTrue(receiver.datagramsDroppedReceiveQueue.n + receiver.datagramsExpiredIncoming.n > 0, "the slow receiver dropped some")
            assertEquals(
                receiver.datagramsReceived,
                delivered + receiver.datagramsDroppedReceiveQueue.n + receiver.datagramsExpiredIncoming.n,
                "received = delivered + dropped + expired: $receiver",
            )
            payload.freeIfNeeded()
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.statsOf(
        pair: VirtualPair,
        connection: CoreConnection,
    ): QuicConnectionStats {
        val read = async(start = CoroutineStart.UNDISPATCHED) { connection.stats() }
        pair.runUntil { read.isCompleted }
        return assertIs<QuicStatsRead.Read>(read.await()).stats
    }

    /** A count the core keeps: every one it reports is counted. */
    private val QuicDatagramCount.n: Long get() = assertIs<QuicDatagramCount.Counted>(this).datagrams

    private fun payloadOf(
        size: Int,
        seed: Int = 0,
    ): PlatformBuffer =
        BufferFactory.network().allocate(maxOf(size, 1)).apply {
            repeat(size) { writeByte((it + seed).toByte()) }
            resetForRead()
        }

    private companion object {
        const val DEPTH = 64

        /** What the core's queue admits of [DEPTH]: the rest is quiche's hand-off of 32, half the depth at most, and the door. */
        const val QUEUED = DEPTH - 33
        const val RECEIVE_DEPTH = 8
        const val SMALL = 64
        const val WARM_ROUNDS = 3
        const val REFUSED = 10_000
        const val STALE = 3
        const val FRESH = 77
        const val BURSTS = 6
        const val BURST = 400
        val AGE = 100.milliseconds
    }
}
