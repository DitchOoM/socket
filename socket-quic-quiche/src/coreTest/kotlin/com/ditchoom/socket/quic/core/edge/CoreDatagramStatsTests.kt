@file:OptIn(ExperimentalDatagramApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.QuicConnectionStats
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a connection's statistics say of the datagrams it could not carry: those its sender was refused because the send
 * queue was full, and those its receiver dropped because the receive queue was full.
 */
class CoreDatagramStatsTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Whether the receiving endpoint's datagrams reach the network: while they do not, the sender hears no acknowledgement. */
    private sealed interface Acks {
        data object Flowing : Acks

        data object Withheld : Acks
    }

    private class WithholdingAcks(
        private val real: DatagramBatchIo,
        private val acks: AtomicReference<Acks>,
    ) : DatagramBatchIo by real {
        override fun send(batch: SendBatch): SendBatchResult =
            when (acks.load()) {
                Acks.Flowing -> real.send(batch)
                Acks.Withheld -> SendBatchResult.AllTaken
            }
    }

    private class Drained(
        val delivered: Long,
        val stats: QuicConnectionStats,
    )

    @Test
    fun aSenderThatOverrunsItsQueueAndASlowReceiverAreCountedInTheirStats() =
        coreTest("CoreDatagramStatsTests.aSenderThatOverrunsItsQueueAndASlowReceiverAreCountedInTheirStats") {
            val options = coreTestOptions.copy(datagrams = DatagramOptions(recvQueueLen = RECV_QUEUE, sendQueueLen = SEND_QUEUE))
            val acks = AtomicReference<Acks>(Acks.Flowing)
            val sentAll = CompletableDeferred<Unit>()
            val drained = CompletableDeferred<Drained>()
            withCoreServer(options = options, wrapIo = { WithholdingAcks(it, acks) }, onConnection = { connection ->
                // The receiver reads nothing until everything the sender will send has arrived: it is as slow as a receiver gets.
                sentAll.await()
                val channel = connection.datagramChannel()
                var delivered = 0L
                try {
                    while (true) {
                        withTimeout(QUIET) {
                            val result = channel.receive()
                            if (result is DatagramReadResult.Received) {
                                result.datagram.payload.freeIfNeeded()
                                delivered++
                            }
                        }
                    }
                } catch (quiet: TimeoutCancellationException) {
                    val read = assertIs<QuicStatsRead.Read>(connection.stats())
                    drained.complete(Drained(delivered, read.stats))
                }
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = options)
                try {
                    val channel = client.connection.datagramChannel()
                    // With no acknowledgement coming back the sender's congestion window fills, and then its send queue.
                    acks.store(Acks.Withheld)
                    var accepted = 0L
                    var refused = 0L
                    repeat(ATTEMPTS) {
                        try {
                            send(channel)
                            accepted++
                        } catch (full: QuicDatagramRefused) {
                            assertIs<DatagramRefusal.QueueFull>(full.refusal)
                            refused++
                        }
                    }
                    assertTrue(refused > 0, "the overrun must refuse some sends: $accepted accepted, $refused refused")
                    assertTrue(accepted > RECV_QUEUE, "the receiver must be sent more than its queue holds: $accepted accepted")
                    acks.store(Acks.Flowing)

                    val sent = withTimeout(WAIT) { readStatsUntil(client.connection) { it.datagramsSent == accepted } }
                    assertEquals(accepted, sent.datagramsSent, "sent = accepted")
                    assertEquals(refused, sent.datagramsRefusedQueueFull, "refused at the sender, by the queue")
                    assertEquals(ATTEMPTS.toLong(), sent.datagramsSent + sent.datagramsRefusedQueueFull, "accepted + refused = attempts")

                    delay(ARRIVE)
                    sentAll.complete(Unit)
                    val taken = withTimeout(WAIT) { drained.await() }
                    assertEquals(accepted, taken.stats.datagramsReceived, "the receiver took every datagram the sender sent")
                    assertTrue(taken.stats.datagramsDroppedReceiveQueue > 0, "a slow receiver drops: delivered ${taken.delivered}")
                    assertEquals(
                        taken.stats.datagramsReceived,
                        taken.delivered + taken.stats.datagramsDroppedReceiveQueue,
                        "received = delivered + dropped",
                    )
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    private suspend fun readStatsUntil(
        connection: CoreConnection,
        done: (QuicConnectionStats) -> Boolean,
    ): QuicConnectionStats {
        while (true) {
            val stats = assertIs<QuicStatsRead.Read>(connection.stats()).stats
            if (done(stats)) return stats
            delay(SETTLE)
        }
    }

    private suspend fun send(channel: ConnectedDatagramChannel) {
        val buffer = BufferFactory.network().allocate(PAYLOAD)
        repeat(PAYLOAD) { buffer.writeByte(it.toByte()) }
        buffer.resetForRead()
        try {
            channel.send(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val PAYLOAD = 1000
        const val ATTEMPTS = 300
        const val SEND_QUEUE = 8
        const val RECV_QUEUE = 3
        val QUIET = 500.milliseconds
        val ARRIVE = 300.milliseconds
        val WAIT = 20.seconds
        val SETTLE = 20.milliseconds
    }
}
