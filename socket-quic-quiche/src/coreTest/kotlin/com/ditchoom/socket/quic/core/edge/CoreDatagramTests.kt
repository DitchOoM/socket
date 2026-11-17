@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicDatagramTooLarge
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
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
 * RFC 9221 datagrams on the core over a real loopback socket, on every platform: echoed in both directions, a send
 * refused with a typed reason, and a receive queue that is bounded and counts what it drops.
 */
class CoreDatagramTests {
    private val loops = LoopSet(1)
    private val on = coreTestOptions.copy(datagrams = DatagramOptions())

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aConnectionWithDatagramsOnClosesCleanlyWithNoneSent() =
        coreTest("CoreDatagramTests.aConnectionWithDatagramsOnClosesCleanlyWithNoneSent") {
            withCoreServer(options = on, onConnection = { delay(HOLD) }) { server ->
                val client = connectCore(server.port, loops = loops, options = on)
                assertTrue(client.connection.datagramChannel().maxWritableSize > 0)
                client.connection.close(QuicError.NoError)
            }
        }

    @Test
    fun datagramsEchoInBothDirectionsByteExact() =
        coreTest("CoreDatagramTests.datagramsEchoInBothDirectionsByteExact") {
            withCoreServer(options = on, onConnection = { connection ->
                val channel = connection.datagramChannel()
                send(channel, payloadOf(SERVER_FIRST, 0))
                echo(channel)
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = on)
                try {
                    val channel = client.connection.datagramChannel()
                    assertTrue(channel.maxWritableSize > 0, "datagrams are negotiated")
                    assertContentEquals(payloadOf(SERVER_FIRST, 0), receive(channel), "the server's own datagram reaches the client")
                    for (n in 0 until ECHOES) {
                        val sent = payloadOf(1 + n * 11 % 1_000, n)
                        send(channel, sent)
                        assertContentEquals(sent, receive(channel), "echo $n")
                    }
                    send(channel, ByteArray(0))
                    assertEquals(0, receive(channel).size, "an empty datagram is a datagram")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aDatagramLargerThanThePathCarriesIsRefusedWithWhatFits() =
        coreTest("CoreDatagramTests.aDatagramLargerThanThePathCarriesIsRefusedWithWhatFits") {
            withCoreServer(options = on, onConnection = { echo(it.datagramChannel()) }) { server ->
                val client = connectCore(server.port, loops = loops, options = on)
                try {
                    val channel = client.connection.datagramChannel()
                    val max = channel.maxWritableSize
                    assertTrue(max > 0)
                    val refusal = assertFailsWith<QuicDatagramTooLarge> { send(channel, ByteArray(max + 1)) }
                    assertEquals(max + 1, refusal.size)
                    assertEquals(max, refusal.maxBytes)
                    assertFailsWith<IllegalArgumentException> { send(channel, ByteArray(max + 1)) }
                    val whole = payloadOf(max, 7)
                    send(channel, whole)
                    assertContentEquals(whole, receive(channel), "a datagram of exactly what fits goes through")
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aPeerThatDoesNotAdvertiseDatagramsIsATypedNotNegotiated() =
        coreTest("CoreDatagramTests.aPeerThatDoesNotAdvertiseDatagramsIsATypedNotNegotiated") {
            withCoreServer(onConnection = { delay(HOLD) }) { server ->
                val client = connectCore(server.port, loops = loops, options = on)
                try {
                    val channel = client.connection.datagramChannel()
                    assertEquals(0, channel.maxWritableSize, "no datagram can be sent to this peer")
                    val refusal = assertFailsWith<QuicDatagramRefused> { send(channel, ByteArray(4)) }
                    assertIs<DatagramRefusal.NotNegotiated>(refusal.refusal)
                    assertFailsWith<IllegalStateException> { send(channel, ByteArray(4)) }
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aConnectionWithDatagramsOffRefusesBothDirectionsAsNotNegotiated() =
        coreTest("CoreDatagramTests.aConnectionWithDatagramsOffRefusesBothDirectionsAsNotNegotiated") {
            withCoreServer(options = on, onConnection = { delay(HOLD) }) { server ->
                val client = connectCore(server.port, loops = loops)
                try {
                    val channel = client.connection.datagramChannel()
                    assertEquals(0, channel.maxWritableSize)
                    assertIs<DatagramRefusal.NotNegotiated>(assertFailsWith<QuicDatagramRefused> { send(channel, ByteArray(4)) }.refusal)
                    val receive = assertFailsWith<QuicDatagramRefused> { channel.receive() }
                    assertIs<DatagramRefusal.NotNegotiated>(receive.refusal)
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aReceiverThatStopsReadingKeepsTheNewestDatagramsAndCountsTheRest() =
        coreTest("CoreDatagramTests.aReceiverThatStopsReadingKeepsTheNewestDatagramsAndCountsTheRest") {
            val small = on.copy(datagrams = DatagramOptions(recvQueueLen = QUEUE, sendQueueLen = SENT))
            withCoreServer(options = small, onConnection = { connection ->
                val channel = connection.datagramChannel()
                send(channel, payloadOf(SERVER_FIRST, 0))
                // The client reads that one, then goes quiet; the server sends the flood once it is told to.
                receive(channel)
                for (n in 1..SENT) {
                    send(channel, payloadOf(SERVER_FIRST, n))
                    delay(PACE)
                }
                delay(HOLD)
            }) { server ->
                val client = connectCore(server.port, loops = loops, options = small)
                try {
                    val channel = client.connection.datagramChannel()
                    receive(channel)
                    send(channel, ByteArray(1))
                    withTimeout(WAIT) {
                        while (client.connection.datagramsOverflowed() < SENT - QUEUE) delay(SETTLE)
                    }
                    assertEquals((SENT - QUEUE).toLong(), client.connection.datagramsOverflowed(), "everything past the bound is counted")
                    val kept = (0 until QUEUE).map { receive(channel) }
                    assertEquals(
                        (SENT - QUEUE + 1..SENT).toList(),
                        kept.map { it[1].toInt() and 0xFF },
                        "the newest datagrams are kept, in order",
                    )
                    assertFailsWith<TimeoutCancellationException> { withTimeout(QUIET) { channel.receive() } }
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }

    @Test
    fun aReceiveParkedWhenTheConnectionClosesEndsWithClosedAndNoLeak() =
        coreTest("CoreDatagramTests.aReceiveParkedWhenTheConnectionClosesEndsWithClosedAndNoLeak") {
            withCoreServer(options = on, onConnection = { delay(HOLD) }) { server ->
                val client = connectCore(server.port, loops = loops, options = on)
                val channel = client.connection.datagramChannel()
                val parked = async { channel.receive() }
                delay(SETTLE)
                client.connection.close(QuicError.NoError)
                assertIs<DatagramReadResult.Closed>(withTimeout(WAIT) { parked.await() })
                assertFailsWith<QuicCloseException> { send(channel, ByteArray(4)) }
            }
        }

    private suspend fun send(
        channel: ConnectedDatagramChannel,
        bytes: ByteArray,
    ) {
        val buffer: PlatformBuffer = BufferFactory.network().allocate(maxOf(bytes.size, 1))
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        try {
            channel.send(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun receive(channel: ConnectedDatagramChannel): ByteArray =
        withTimeout(WAIT) {
            val received = assertIs<DatagramReadResult.Received>(channel.receive())
            val payload = received.datagram.payload
            val bytes = payload.readByteArray(payload.remaining())
            payload.freeIfNeeded()
            bytes
        }

    /** Echoes every datagram of [channel] back until the connection ends. */
    private suspend fun echo(channel: ConnectedDatagramChannel) {
        while (true) {
            when (val result = channel.receive()) {
                is DatagramReadResult.Received -> {
                    val payload = result.datagram.payload
                    val bytes = payload.readByteArray(payload.remaining())
                    payload.freeIfNeeded()
                    send(channel, bytes)
                }
                is DatagramReadResult.Closed -> return
            }
        }
    }

    private fun payloadOf(
        size: Int,
        seed: Int,
    ): ByteArray = ByteArray(size) { (it * 7 + seed).toByte() }.also { if (size > 1) it[1] = seed.toByte() }

    private companion object {
        const val SERVER_FIRST = 33
        const val ECHOES = 200
        const val QUEUE = 4
        const val SENT = 20
        val WAIT = 10.seconds
        val QUIET = 300.milliseconds
        val SETTLE = 20.milliseconds
        val PACE = 2.milliseconds
        val HOLD = 5.seconds
    }
}
