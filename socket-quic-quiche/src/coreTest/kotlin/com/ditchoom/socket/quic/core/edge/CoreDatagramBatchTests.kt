package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramReceive
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicDatagramBatch
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.forEach
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The public datagram receive on the core, on every platform: one resume takes every datagram the connection holds,
 * the batch is read in place and only inside its block, one receive runs at a time, and a cancelled receive takes
 * nothing.
 */
class CoreDatagramBatchTests {
    private val loops = LoopSet(1)
    private val on = coreTestOptions.copy(datagrams = DatagramOptions())

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun oneReceiveTakesEveryDatagramTheConnectionHoldsInOrder() =
        coreTest("CoreDatagramBatchTests.oneReceiveTakesEveryDatagramTheConnectionHoldsInOrder") {
            withAccepted { client, server ->
                repeat(HELD) { sent(client, payloadOf(it)) }
                awaitReceived(server, HELD.toLong())
                val received = server.coreDatagrams.receive { batch -> copied(batch) }
                val batch = assertIs<DatagramReceive.Batch<List<ByteArray>>>(received)
                assertEquals(HELD, batch.count, "every held datagram at one resume")
                batch.value.forEachIndexed { at, bytes -> assertContentEquals(payloadOf(at), bytes, "datagram $at") }
            }
        }

    @Test
    fun aBatchIsReadableOnlyInsideItsBlock() =
        coreTest("CoreDatagramBatchTests.aBatchIsReadableOnlyInsideItsBlock") {
            withAccepted { client, server ->
                sent(client, payloadOf(0))
                val received = withTimeout(WAIT) { server.coreDatagrams.receive { batch -> batch } }
                val kept = assertIs<DatagramReceive.Batch<QuicDatagramBatch>>(received).value
                assertEquals(0, kept.size, "the batch is empty once its block returned")
                assertFailsWith<IndexOutOfBoundsException> { kept[0] }
            }
        }

    @Test
    fun aSecondReceiveWhileOneIsParkedIsReceiverBusy() =
        coreTest("CoreDatagramBatchTests.aSecondReceiveWhileOneIsParkedIsReceiverBusy") {
            withAccepted { client, server ->
                val parked = async(start = CoroutineStart.UNDISPATCHED) { server.coreDatagrams.receive { it.size } }
                assertEquals(DatagramReceive.NotReceived.ReceiverBusy, server.coreDatagrams.receive { it.size })
                sent(client, payloadOf(1))
                assertEquals(1, assertIs<DatagramReceive.Batch<Int>>(withTimeout(WAIT) { parked.await() }).value)
            }
        }

    @Test
    fun aCancelledParkedReceiveTakesNothingAndTheNextOneGetsTheDatagram() =
        coreTest("CoreDatagramBatchTests.aCancelledParkedReceiveTakesNothingAndTheNextOneGetsTheDatagram") {
            withAccepted { client, server ->
                val parked = async { server.coreDatagrams.receive { it.size } }
                delay(SETTLE)
                parked.cancelAndJoin()
                sent(client, payloadOf(2))
                val next = withTimeout(WAIT) { server.coreDatagrams.receive { batch -> copied(batch) } }
                assertContentEquals(payloadOf(2), assertIs<DatagramReceive.Batch<List<ByteArray>>>(next).value.single())
            }
        }

    @Test
    fun receiveEachDeliversEveryDatagramThenWhyNoneWillCome() =
        coreTest("CoreDatagramBatchTests.receiveEachDeliversEveryDatagramThenWhyNoneWillCome") {
            withAccepted { client, server ->
                val seen = ArrayList<ByteArray>()
                val reading = async { server.coreDatagrams.receiveEach { seen += it.readByteArray(it.remaining()) } }
                repeat(HELD) { sent(client, payloadOf(it)) }
                awaitReceived(server, HELD.toLong())
                delay(SETTLE)
                client.connection.close(QuicError.NoError)
                val end = assertIs<DatagramReceive.NotReceived.Unavailable.ConnectionEnded>(withTimeout(WAIT) { reading.await() })
                assertIs<QuicConnectionEnd.ByPeer>(end.end)
                val again = server.coreDatagrams.receive { it.size }
                assertEquals<DatagramReceive<Int>>(end, again, "a receive after the end answers the end")
                assertEquals(HELD, seen.size)
                seen.forEachIndexed { at, bytes -> assertContentEquals(payloadOf(at), bytes, "datagram $at") }
            }
        }

    @Test
    fun aConnectionWithDatagramsOffAnswersNotNegotiated() =
        coreTest("CoreDatagramBatchTests.aConnectionWithDatagramsOffAnswersNotNegotiated") {
            withCoreServer(options = on, onConnection = { delay(HOLD) }) { server ->
                val client = connectCore(server.port, loops = loops)
                assertEquals(DatagramReceive.NotReceived.Unavailable.NotNegotiated, client.connection.coreDatagrams.receive { it.size })
                client.connection.close(QuicError.NoError)
            }
        }

    /** A client with datagrams on, and the server's side of its connection, accepted once the client's first datagram made it. */
    private suspend fun withAccepted(body: suspend (CoreClient, CoreConnection) -> Unit) {
        val accepted = CompletableDeferred<CoreConnection>()
        val done = CompletableDeferred<Unit>()
        withCoreServer(options = on, onConnection = { connection ->
            accepted.complete(connection)
            done.await()
        }) { server ->
            val client = connectCore(server.port, loops = loops, options = on)
            try {
                body(client, withTimeout(WAIT) { accepted.await() })
            } finally {
                done.complete(Unit)
                client.connection.close(QuicError.NoError)
            }
        }
    }

    private suspend fun awaitReceived(
        connection: CoreConnection,
        count: Long,
    ) = withTimeout(WAIT) {
        while (assertIs<QuicStatsRead.Read>(connection.stats()).stats.datagramsReceived < count) delay(SETTLE)
    }

    private fun sent(
        client: CoreClient,
        bytes: ByteArray,
    ) {
        val buffer = BufferFactory.network().allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        try {
            assertEquals<DatagramSend>(DatagramSend.Sent, client.connection.coreDatagrams.send(buffer))
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private fun copied(batch: QuicDatagramBatch): List<ByteArray> {
        val out = ArrayList<ByteArray>(batch.size)
        batch.forEach { out += it.readByteArray(it.remaining()) }
        assertTrue(out.isNotEmpty())
        return out
    }

    private fun payloadOf(seed: Int): ByteArray = ByteArray(PAYLOAD) { (it * 7 + seed).toByte() }

    private companion object {
        const val HELD = 12
        const val PAYLOAD = 200
        val WAIT = 10.seconds
        val SETTLE = 20.milliseconds
        val HOLD = 5.seconds
    }
}
